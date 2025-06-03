package org.bruchez.olivier.wallbox

import java.nio.file.Path
import scala.collection.mutable
import java.time.LocalDateTime

case class CurrentPowerConversion(
    maxObservedValuesPerMaxCurrent: Int,
    timeToLiveInSeconds: Int,
    decayLambda: Double
) {
  private case class ObservedPower(dateTime: LocalDateTime, powerInWatts: Double)

  private val observedValues = mutable.Map[Int, List[ObservedPower]]()

  def addObservedValues(
      dateTime: LocalDateTime,
      maxCurrenInAmperes: Int,
      powerInWatts: Double
  ): Unit = {
    val observedPower = ObservedPower(dateTime, powerInWatts)
    observedValues(maxCurrenInAmperes) =
      observedPower :: observedValues.getOrElse(maxCurrenInAmperes, Nil)

    // TODO: only keep max N "power" values for each "max current" value
    // TODO: only keep "power values" for a maximum of T time (e.g. 10 minutes)
    // TODO: but always keep at least 1 value
  }

  def powerInWatts(maxCurrenInAmperes: Int): Option[Double] = {
    // TODO: use observed values
    // TODO: use exponential decay weighting (weight(t) = e^(-λ * t)) => give more importance to recently observed values

    // For now, use some heuristics; our ID.3 uses max 16 A, with 3 phases and 220 V per phase
    Some(math.min(maxCurrenInAmperes, 16.0) * (3 * 220))
  }

  def saveTo(jsonFile: Path): Unit = {
    // TODO: serialize values to JSON file
  }
}

object CurrentPowerConversion {
  def loadFrom(
      jsonFile: Path,
      maxObservedValuesPerMaxCurrent: Int,
      timeToLiveInSeconds: Int,
      decayLambda: Double
  ): CurrentPowerConversion = {
    // TODO: load from JSON file
    CurrentPowerConversion(maxObservedValuesPerMaxCurrent, timeToLiveInSeconds, decayLambda)
  }
}
