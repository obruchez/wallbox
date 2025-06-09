package org.bruchez.olivier.wallbox

import java.nio.file.Path
import java.time.Instant
import scala.collection.mutable
import scala.util.Try

// Keep a log of (max. charging current, charging power) pairs to estimate the power from the current during the
// optimization phase. In practice, the function is not strictly linear, even approximately, as it will plateau after
// some value of the max. charging current.

case class CurrentPowerConversion(
    maxObservedValuesPerMaxCurrent: Int,
    timeToLiveInSeconds: Int,
    decayLambda: Double
) {
  import CurrentPowerConversion.ObservedPower

  private val observedValues = mutable.Map[Int, List[ObservedPower]]()

  // TODO: refine this (with age of values, etc.)
  def hasGoodEstimateForCurrent(maxCurrenInAmperes: Int): Boolean =
    observedValues.get(maxCurrenInAmperes).exists(_.size > 5)

  def addObservedValues(
      instant: Instant,
      maxCurrenInAmperes: Int,
      powerInWatts: Double
  ): Unit = {
    val observedPower = ObservedPower(instant, powerInWatts)
    val pastObservedPowers = observedValues.getOrElse(maxCurrenInAmperes, Nil)
    observedValues(maxCurrenInAmperes) = observedPower :: pastObservedPowers

    garbageCollect()
  }

  def powerInWatts(maxCurrenInAmperes: Int): Option[Double] =
    observedValues.get(maxCurrenInAmperes).map { observedPowers =>
      // This is guaranteed by the garbage collecting step
      assert(observedPowers.nonEmpty)

      // val simpleAverage = observedPowers.map(_.powerInWatts).sum / observedPowers.size.toDouble
      val weightedAverage = this.weightedAverage(observedPowers)

      weightedAverage
    }

  // def powerInWatts(maxCurrenInAmperes: Int): Option[Double] = {
  //  // For now, use some heuristics; our ID.3 uses max 16 A, with 3 phases and 220 V per phase
  //  Some(math.min(maxCurrenInAmperes, 16.0) * (3 * 220))
  // }

  def saveTo(jsonFile: Path): Unit = {
    // TODO: serialize values to JSON file
  }

  def printObservedCurrentCounts(): Unit = {
    val countsAsString =
      observedValues.toSeq.sortBy(_._1).map(kv => s"${kv._1} A -> ${kv._2.size}").mkString(", ")
    println(s"Observed currents: $countsAsString")
  }

  def printAllObservedValuesAndAverages(): Unit =
    observedValues.toSeq.sortBy(_._1).foreach { case (maxCurrenInAmperes, observedPowers) =>
      val average = weightedAverage(observedPowers)
      val timeOrTimes = if (observedPowers.size > 1) "times" else "time"
      println(
        f"$maxCurrenInAmperes A observed ${observedPowers.size} $timeOrTimes with a weighted average of $average%.2f W:"
      )

      observedPowers.sortBy(_.instant.getEpochSecond).reverse.foreach { observedPower =>
        println(f" - ${observedPower.instant}: ${observedPower.powerInWatts}%.2f W")
      }
    }

  // Compute an exponentially decaying weighted average (to give more importance to recent observations)
  private def weightedAverage(observedPowers: Seq[ObservedPower]): Double = {
    val mostRecentEpochInSeconds = observedPowers.map(_.instant.getEpochSecond).max

    val weightsAndProducts = observedPowers.map { observedPower =>
      val ageInSeconds = mostRecentEpochInSeconds - observedPower.instant.getEpochSecond
      val ageInMinutes = ageInSeconds.toDouble / 60.0

      val weight = this.weight(ageInMinutes)

      (weight, weight * observedPower.powerInWatts)
    }

    weightsAndProducts.map(_._2).sum / weightsAndProducts.map(_._1).sum
  }

  private def weight(ageInMinutes: Double): Double =
    math.exp(-decayLambda * ageInMinutes)

  private def garbageCollect(): Unit = {
    val now = Instant.now

    // Keep only recent values after the following threshold
    val threshold = now.minusSeconds(timeToLiveInSeconds.toLong)

    for ((maxCurrenInAmperes, observedPowers) <- observedValues) {
      // Filter out old values and keep at most maxObservedValuesPerMaxCurrent values
      val recentObservedPowers =
        observedPowers.filter(_.instant.isAfter(threshold)).take(maxObservedValuesPerMaxCurrent)

      // Let old values "die", they'll be re-explored if needed (in the "exploration mode")

      if (recentObservedPowers.nonEmpty) {
        observedValues.update(maxCurrenInAmperes, recentObservedPowers)
      } else {
        observedValues.remove(maxCurrenInAmperes)
      }
    }
  }
}

object CurrentPowerConversion {
  private case class ObservedPower(instant: Instant, powerInWatts: Double)

  private val DefaultMaxObservedValuesPerMaxCurrent = 1024
  // Don't keep values too long
  private val DefaultTimeToLiveInSeconds = 5 * 60
  // A value of 0.23 will give a weight of 0.1 to observed values that are 10 minutes old
  private val DefaultDecayLambda = 0.23

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
