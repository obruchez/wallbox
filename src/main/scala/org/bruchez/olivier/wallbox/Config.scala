package org.bruchez.olivier.wallbox

import io.circe.generic.auto._
import io.circe.parser._
import java.nio.file.{Files, Paths}

case class Config(
    wallboxEmail: String,
    wallboxPassword: String,
    wallboxCsvPath: String,
    googleServiceAccountKeyFile: String,
    googleSpreadsheetId: String
)

object Config {
  private val ConfigFileName = "config.json"

  def load(): Config = {
    val path = Paths.get(ConfigFileName)
    require(
      Files.exists(path),
      s"Configuration file '$ConfigFileName' not found in working directory"
    )
    val json = new String(Files.readAllBytes(path), "UTF-8")
    decode[Config](json) match {
      case Right(config) => config
      case Left(error) =>
        throw new RuntimeException(s"Failed to parse $ConfigFileName: ${error.getMessage}")
    }
  }
}
