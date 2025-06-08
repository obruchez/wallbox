package org.bruchez.olivier.wallbox

import scala.util.control.Breaks._

object Main {
  def main(args: Array[String]): Unit = {
    val kostal = Kostal()
    val wallbox = Wallbox()
    val whatwatt = Whatwatt()

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
