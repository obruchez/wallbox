package org.bruchez.olivier.wallbox

import java.nio.file.{Path, Paths}
import java.time.{Duration, Instant}
import scala.util.control.Breaks.{break, breakable}
import scala.util.{Success, Try}

object Optimizer {
  private val SecondsBeforeNoSolarProduction = 10 * 60

  case class Status(
      earliestInstantWithNoSolarProductionOpt: Option[Instant] = None,
      carCharging: Boolean = false
  ) {
    // "Night mode"
    def noSolarProduction: Boolean = earliestInstantWithNoSolarProductionOpt match {
      case None =>
        false

      case Some(earliestInstantWithNoSolarProduction) =>
        val secondsAgo =
          Duration.between(earliestInstantWithNoSolarProduction, Instant.now()).getSeconds
        secondsAgo > SecondsBeforeNoSolarProduction
    }
  }
}

class Optimizer(
    currentPowerConversionJsonFile: Path =
      Paths.get(sys.env("WALLBOX_CURRENT_POWER_CONVERSION_JSON"))
) {

  implicit val currentPowerConversion: CurrentPowerConversion =
    CurrentPowerConversion.loadFrom(currentPowerConversionJsonFile)

  private val kostal = Kostal()
  private val wallbox = Wallbox()
  private val whatwatt = Whatwatt()

  def optimizeRepeatedly(): Unit = {
    val PeriodInMs = 5000

    println("Press Ctrl-C to stop...")

    breakable {
      var status = Optimizer.Status()

      while (true) {
        try {
          status = optimizeOnce(status).get

          Thread.sleep(PeriodInMs)
        } catch {
          case _: InterruptedException =>
            println("\nMonitoring interrupted")
            break()

          case e: Exception =>
            println(s"Unexpected error: ${e.getMessage}")
            Thread.sleep(PeriodInMs)
        }
      }
    }
  }

  private def optimizeOnce(status: Optimizer.Status): Try[Optimizer.Status] =
    wallbox.extendedStatus().flatMap { extendedStatus =>
      if (extendedStatus.status.charging) {
        optimizeOnceWhileCharging(status.copy(carCharging = true))
      } else {
        println(s"Car not charging, nothing to do")
        Success(status.copy(carCharging = false))
      }
    }

  private def optimizeOnceWhileCharging(status: Optimizer.Status): Try[Optimizer.Status] = {
    for {
      solarPowerInWatts <- kostal.outputPowerInWatts()
      whatwattReport <- whatwatt.report()
      // Positive if consumming, negative if injecting
      gridPowerInWatts = whatwattReport.gridPowerInWatts
      wallboxExtendedStatus <- wallbox.extendedStatus()
      chargingPowerInWatts = wallboxExtendedStatus.chargingPowerInWatts
      maxChargingCurrentInAmperes = wallboxExtendedStatus.maxChargingCurrentInAmperes
    } yield {
      println(f"Solar power          : $solarPowerInWatts%.2f W")
      println(f"Grid power           : $gridPowerInWatts%.2f W")
      println(f"Charging power       : $chargingPowerInWatts%.2f W")
      println(f"Max. charging current: $maxChargingCurrentInAmperes A")
      println()

      currentPowerConversion.addObservedValues(
        instant = Instant.now(),
        maxCurrenInAmperes = maxChargingCurrentInAmperes,
        powerInWatts = chargingPowerInWatts
      )

      currentPowerConversion.printAllObservedValuesAndAverages()
      println()

      val maxChargingCurrentInAmperesToSet =
        if (status.noSolarProduction) {
          // Solar panels are not producing electricity => quick charging
          println("Solar panels are not producing electricity, using maximum current value")
          println()

          Wallbox.MaxPowerLimitInAmperes
        } else {
          // Solar panels are producting electricity => find the optimal max. current value
          optimalMaxChargingCurrentInAmperesIfSolarProduction(
            solarPowerInWatts = solarPowerInWatts,
            gridPowerInWatts = gridPowerInWatts,
            chargingPowerInWatts = chargingPowerInWatts,
            maxChargingCurrentInAmperes = maxChargingCurrentInAmperes
          )
        }

      wallbox.setMaxCurrent(maxChargingCurrentInAmperesToSet)

      // Check if the solar panels still produce electricity
      val newEarliestInstantWithNoSolarProductionOpt = if (solarPowerInWatts > 0.0) {
        None
      } else if (status.earliestInstantWithNoSolarProductionOpt.isEmpty) {
        Some(Instant.now)
      } else {
        status.earliestInstantWithNoSolarProductionOpt
      }

      status.copy(earliestInstantWithNoSolarProductionOpt =
        newEarliestInstantWithNoSolarProductionOpt
      )
    }
  }

  private def optimalMaxChargingCurrentInAmperesIfSolarProduction(
      solarPowerInWatts: Double,
      gridPowerInWatts: Double,
      chargingPowerInWatts: Double,
      maxChargingCurrentInAmperes: Int
  ): Int = {

    // Optimal neighbour current (i.e. current value - 1 or current value + 1)
    val optimalNeighbourCurrent =
      if (gridPowerInWatts > 0) {
        // We consume electricity from the grid => decrease the current
        maxChargingCurrentInAmperes - 1
      } else if (gridPowerInWatts < 0) {
        // We inject electricity to the grid => increase the current
        maxChargingCurrentInAmperes + 1
      } else {
        // Do not change the current (unlikely)
        maxChargingCurrentInAmperes
      }

    if (!currentPowerConversion.hasGoodEstimateForCurrent(optimalNeighbourCurrent)) {
      // Explore the neighbouring values if we don't have any estimate for actual charging power for those values
      optimalNeighbourCurrent
    } else {
      // Here we use the estimates we currently have for actual charging power to determine the optimal maximum
      // charging current. If some estimates are missing between the current "max current" value and the optimal "max
      // current" value found by the algorithm below, then, at the next optimization step, we'll detect that we need
      // to go in the other direction again, and we'll explore the neighbouring values again ("exploration mode" above).

      // Estimate total household consumption excluding charger
      val totalHouseholdConsumptionMinusChargerInWatts =
        solarPowerInWatts + gridPowerInWatts - chargingPowerInWatts

      println(
        f"Total household consumption (charger excluded): $totalHouseholdConsumptionMinusChargerInWatts%.2f W"
      )
      println()

      // Estimate how different "max. charging current" values would impact the grid power
      val estimatedGridPowersInWattsByMaxChargingCurrent =
        for (
          tentativeMaxChargingCurrentInAmperes <-
            Wallbox.MinPowerLimitInAmperes to Wallbox.MaxPowerLimitInAmperes
        ) yield {
          currentPowerConversion.powerInWatts(tentativeMaxChargingCurrentInAmperes).map {
            estimatedChargingPowerInWatts =>
              val estimatedGridPowerInWatts =
                totalHouseholdConsumptionMinusChargerInWatts + estimatedChargingPowerInWatts - solarPowerInWatts

              tentativeMaxChargingCurrentInAmperes -> estimatedGridPowerInWatts
          }
        }

      val (optimalMaxChargingCurrentInAmperes, estimatedGridPowerInWatts) =
        estimatedGridPowersInWattsByMaxChargingCurrent.flatten.minBy {
          case (_, estimatedGridPowerInWatts) =>
            // We want to minimize the absolute value of the grid power, i.e. consuming and injecting as little as possible
            math.abs(estimatedGridPowerInWatts)
        }

      println(f"Optimal max. charging current        : $optimalMaxChargingCurrentInAmperes A")
      println(f"Estimated grid power for that current: $estimatedGridPowerInWatts%.2f W")
      println()

      optimalMaxChargingCurrentInAmperes
    }
  }

  def test(): Unit = {
    val PeriodInMs = 1000

    println("Press Ctrl-C to stop...")

    breakable {
      while (true) {
        try {
          val kostalOutputPowerInWatts = kostal.outputPowerInWatts().get
          val whatwattReport = whatwatt.report().get

          val (basicWallboxStatus, extendedWallboxStatus) = (for {
            token <- wallbox.authenticationToken()
            basicStatus <- wallbox.basicStatus(token)
            extendedStatus <- wallbox.extendedStatus(token)
          } yield (basicStatus, extendedStatus)).get

          println(s"Kostal: $kostalOutputPowerInWatts W")
          println(s"Whatwatt: ${whatwattReport.gridPowerInWatts} W")
          // println(s"Basic Wallbox information: ${basicWallboxStatus.rawJsonResponse}")
          println(s"Extended Wallbox information: $extendedWallboxStatus")
          println()

          Thread.sleep(PeriodInMs)
        } catch {
          case _: InterruptedException =>
            println("\nMonitoring interrupted")
            break()

          case e: Exception =>
            println(s"Unexpected error: ${e.getMessage}")
            Thread.sleep(PeriodInMs)
        }
      }
    }
  }
}
