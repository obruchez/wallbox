package org.bruchez.olivier.wallbox

import java.time.{Duration, Instant}

case class ChargingSessionTracker(
    startTime: Instant,
    lastMeasurementOpt: Option[ChargingSessionTracker.Measurement] = None,
    solarEnergyInWh: Double = 0.0,
    chargingEnergyInWh: Double = 0.0,
    houseEnergyInWh: Double = 0.0,
    gridEnergyInWh: Double = 0.0
) {

  def addMeasurement(
      instant: Instant,
      solarPowerInWatts: Double,
      chargingPowerInWatts: Double,
      gridPowerInWatts: Double
  ): ChargingSessionTracker = {
    val housePowerInWatts = solarPowerInWatts + gridPowerInWatts - chargingPowerInWatts

    lastMeasurementOpt match {
      case None =>
        copy(lastMeasurementOpt =
          Some(
            ChargingSessionTracker.Measurement(
              instant,
              solarPowerInWatts,
              chargingPowerInWatts,
              housePowerInWatts,
              gridPowerInWatts
            )
          )
        )

      case Some(last) =>
        val durationInHours = Duration.between(last.instant, instant).toMillis / 3600000.0

        // Trapezoidal integration: average of two consecutive measurements * duration
        copy(
          lastMeasurementOpt = Some(
            ChargingSessionTracker.Measurement(
              instant,
              solarPowerInWatts,
              chargingPowerInWatts,
              housePowerInWatts,
              gridPowerInWatts
            )
          ),
          solarEnergyInWh =
            solarEnergyInWh + (last.solarPowerInWatts + solarPowerInWatts) / 2.0 * durationInHours,
          chargingEnergyInWh =
            chargingEnergyInWh + (last.chargingPowerInWatts + chargingPowerInWatts) / 2.0 * durationInHours,
          houseEnergyInWh =
            houseEnergyInWh + (last.housePowerInWatts + housePowerInWatts) / 2.0 * durationInHours,
          gridEnergyInWh =
            gridEnergyInWh + (last.gridPowerInWatts + gridPowerInWatts) / 2.0 * durationInHours
        )
    }
  }

  def summary: String = {
    val duration = Duration.between(startTime, Instant.now())
    val hours = duration.toHours
    val minutes = duration.toMinutes % 60

    val durationStr = if (hours > 0) s"${hours}h ${minutes}m" else s"${minutes}m"

    f"""Session duration: $durationStr
       |Solar energy produced : ${solarEnergyInWh / 1000.0}%.2f kWh
       |Energy consumed by car: ${chargingEnergyInWh / 1000.0}%.2f kWh
       |House consumption     : ${houseEnergyInWh / 1000.0}%.2f kWh
       |Grid energy (imported): ${gridEnergyInWh / 1000.0}%.2f kWh""".stripMargin
  }
}

object ChargingSessionTracker {
  case class Measurement(
      instant: Instant,
      solarPowerInWatts: Double,
      chargingPowerInWatts: Double,
      housePowerInWatts: Double,
      gridPowerInWatts: Double
  )
}
