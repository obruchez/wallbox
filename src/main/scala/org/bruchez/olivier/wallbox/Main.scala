package org.bruchez.olivier.wallbox

object Main {
  def main(args: Array[String]): Unit = {
    val optimizer = new Optimizer()

    // optimizer.test()
    optimizer.optimizeRepeatedly()
  }
}
