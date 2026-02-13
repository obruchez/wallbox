package org.bruchez.olivier.wallbox

import java.nio.file.Paths

object WallboxToSheets {

  def main(args: Array[String]): Unit = {
    val config = Config.load()

    // 1. Download wallbox.csv to the base of the repository
    val csvPath = Paths.get("wallbox.csv")
    println("=== Downloading Wallbox sessions ===")
    val result = WallboxDownloader.downloadWallboxSessions(
      config.wallboxEmail,
      config.wallboxPassword,
      Some(csvPath)
    )
    result match {
      case scala.util.Success(_) =>
        println(s"Download completed: $csvPath")
      case scala.util.Failure(exception) =>
        System.err.println(
          s"Failed to download sessions: ${exception.getMessage}"
        )
        exception.printStackTrace()
        System.exit(1)
    }

    // 2. Import into Google Sheets
    println()
    println("=== Importing to Google Sheets ===")
    GoogleSheetsImporter.importToSheets(
      csvPath.toAbsolutePath.toString,
      config.googleServiceAccountKeyFile,
      config.googleSpreadsheetId
    )

    println()
    println("=== Done ===")
  }
}
