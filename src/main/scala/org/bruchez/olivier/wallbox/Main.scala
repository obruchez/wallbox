package org.bruchez.olivier.wallbox

object Main {
  def main(args: Array[String]): Unit = {
    val emailNotifierOpt = EmailNotifier()
    emailNotifierOpt.foreach(
      _.sendEmail(
        subject = "Wallbox optimizer started",
        body = "The container has started.",
        throttle = false
      )
    )

    val optimizer = new Optimizer(emailNotifierOpt = emailNotifierOpt)

    // optimizer.test()
    optimizer.optimizeRepeatedly()
  }
}
