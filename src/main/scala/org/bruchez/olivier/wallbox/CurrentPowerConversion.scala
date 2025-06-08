package org.bruchez.olivier.wallbox

import java.nio.file.Path
import java.time.Instant
import scala.collection.mutable
import scala.util.Try

case class CurrentPowerConversion(
    maxObservedValuesPerMaxCurrent: Int,
    timeToLiveInSeconds: Int,
    decayLambda: Double
) {
  private case class ObservedPower(instant: Instant, powerInWatts: Double)

  private val observedValues = mutable.Map[Int, List[ObservedPower]]()

  def addObservedValues(
      instant: Instant,
      maxCurrenInAmperes: Int,
      powerInWatts: Double
  ): Unit = {
    val observedPower = ObservedPower(instant, powerInWatts)
    observedValues(maxCurrenInAmperes) =
      observedPower :: observedValues.getOrElse(maxCurrenInAmperes, Nil)

    // TODO: only keep max N "power" values for each "max current" value
    // TODO: only keep "power values" for a maximum of T time (e.g. 10 minutes)
    // TODO: but always keep at least 1 value (should this be the actual latest value or an average?)
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

  def printObservedCurrentCounts(): Unit = {
    val countsAsString =
      observedValues.toSeq.sortBy(_._1).map(kv => s"${kv._1} A -> ${kv._2.size}").mkString(", ")
    println(s"Observed currents: $countsAsString")
  }
}

object CurrentPowerConversion {
  private val DefaultMaxObservedValuesPerMaxCurrent = 4096
  private val DefaultTimeToLiveInSeconds = 10 * 60
  private val DefaultDecayLambda = 0.9

  def apply(): CurrentPowerConversion =
    CurrentPowerConversion(
      maxObservedValuesPerMaxCurrent = Try(sys.env("WALLBOX_MAX_OBSERVED_VALUES_PER_MAX_CURRENT"))
        .map(_.toInt)
        .getOrElse(DefaultMaxObservedValuesPerMaxCurrent),
      timeToLiveInSeconds = Try(sys.env("WALLBOX_TIME_TO_LIVE_IN_SECONDS"))
        .map(_.toInt)
        .getOrElse(DefaultTimeToLiveInSeconds),
      decayLambda =
        Try(sys.env("WALLBOX_DECAY_LAMBDA")).map(_.toDouble).getOrElse(DefaultDecayLambda)
    )

  def loadFrom(jsonFile: Path): CurrentPowerConversion = {
    val currentPowerConversion = CurrentPowerConversion()

    // TODO: load from JSON file and call addObservedValues for each value from the JSON file

    currentPowerConversion
  }
}
