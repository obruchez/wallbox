package org.bruchez.olivier.wallbox

import java.nio.file.{Path, Paths}
import java.time.Instant
import scala.util.control.Breaks.{break, breakable}
import scala.util.{Success, Try}

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
      while (true) {
        try {
          optimizeOnce().get

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

  private def optimizeOnce(): Try[Unit] =
    wallbox.extendedStatus().flatMap { extendedStatus =>
      if (extendedStatus.status.charging) {
        optimizeOnceWhileCharging()
      } else {
        println(s"Car not charging, nothing to do")
        Success(())
      }
    }

  private def optimizeOnceWhileCharging(): Try[Unit] = {
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

      currentPowerConversion.printObservedCurrentCounts()
      println()

      // Step 1: estimate total household consumption excluding charger
      val totalHouseholdConsumptionMinusChargerInWatts =
        solarPowerInWatts + gridPowerInWatts - chargingPowerInWatts

      println(
        f"Total household consumption (charger excluded): $totalHouseholdConsumptionMinusChargerInWatts%.2f W"
      )
      println()

      // Step 2: estimate how different "max. charging current" values would impact the grid power
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

      // Step 3: actually change the max. charging current of the charger
      wallbox.setMaxCurrent(optimalMaxChargingCurrentInAmperes)

      // TODO: if neighboring current values (i.e. current value - 1 and current value + 1) given by currentPowerConversion.powerInWatts
      //       are None, then just take a -1 or +1 step to explore those values

      // TODO: do we need to ask ourselves the question of whether the solar pannels are producing electricity (solarPowerInWatts > 0)?s
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
