package org.bruchez.olivier.wallbox

import java.time.Instant

case object Logger {
  def log(string: String): Unit = {
    println(s"${Instant.now} - $string")
  }
}
