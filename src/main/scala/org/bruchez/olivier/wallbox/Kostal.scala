package org.bruchez.olivier.wallbox

import scala.util.Try

case class Kostal(username: String, password: String) {
  // Returns instant production in kW (?)
  def production(): Try[Double] = ???
}
