package org.bruchez.olivier.wallbox

import scala.util.control.Breaks._

object Main {
  def main(args: Array[String]): Unit = {
    val whatwatt = Whatwatt("192.168.50.252")

    val PeriodInMs = 1000

    println("Starting Whatwatt monitoring (press Ctrl-C to stop)...")

    breakable {
      while (true) {
        try {
          val report = whatwatt.report().get
          println(s"Report: $report")
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

    println("Whatwatt monitoring stopped")
  }
}
