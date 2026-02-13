package org.bruchez.olivier.wallbox

import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.sheets.v4.{Sheets, SheetsScopes}
import com.google.api.services.sheets.v4.model._
import com.google.auth.http.HttpCredentialsAdapter
import com.google.auth.oauth2.ServiceAccountCredentials

import java.io.FileInputStream
import java.util.Collections
import scala.jdk.CollectionConverters._

object GoogleSheetsImporter {

  private val ApplicationName = "WallboxSheetsImporter"

  case class SheetRow(date: String, energy: Double, durationMinutes: Long)

  def main(args: Array[String]): Unit = {
    val config = Config.load()
    importToSheets(
      config.wallboxCsvPath,
      config.googleServiceAccountKeyFile,
      config.googleSpreadsheetId
    )
  }

  def importToSheets(
      csvFile: String,
      keyFile: String,
      spreadsheetId: String
  ): Unit = {
    // 1. Parse CSV
    val sessions = WallboxCsvParser.parseFile(csvFile) match {
      case scala.util.Success(s) =>
        println(s"Parsed ${s.length} sessions from CSV")
        s
      case scala.util.Failure(e) =>
        System.err.println(s"Failed to parse CSV: ${e.getMessage}")
        System.exit(1)
        Nil
    }

    // 2. Convert to sheet rows
    val csvRows = sessions.map { session =>
      SheetRow(
        date = session.start.toLocalDate.toString,
        energy = session.energy,
        durationMinutes = parseChargingTime(session.chargingTime)
      )
    }

    // 3. Build Sheets service
    val sheetsService = buildSheetsService(keyFile)

    // 4. Fetch sheet metadata (first sheet name + numeric ID)
    val spreadsheet =
      sheetsService.spreadsheets().get(spreadsheetId).execute()
    val firstSheet = spreadsheet.getSheets.asScala.head
    val sheetName = firstSheet.getProperties.getTitle
    val sheetId = firstSheet.getProperties.getSheetId

    println(s"Using sheet: '$sheetName' (id=$sheetId)")

    // 5. Read existing rows from A2:C
    val existingRange = s"'$sheetName'!A2:C"
    val existingResponse = sheetsService
      .spreadsheets()
      .values()
      .get(spreadsheetId, existingRange)
      .execute()

    val existingValues: List[java.util.List[AnyRef]] =
      Option(existingResponse.getValues)
        .map(_.asScala.toList)
        .getOrElse(Nil)

    // Track row index (0-based offset from row 2) for each existing row
    val indexedExistingRows = existingValues.zipWithIndex.flatMap { case (row, idx) =>
      parseExistingRow(row).map((_, idx))
    }
    val existingRows = indexedExistingRows.map(_._1)
    println(s"Found ${existingRows.length} existing rows in sheet")

    // 6. Reconcile
    val existingByDate = indexedExistingRows
      .groupBy(_._1.date)
      .view
      .mapValues(_.map { case (row, idx) => (row, idx) })
      .toMap
    val csvByDate = csvRows.groupBy(_.date)

    // Check for mismatches on overlapping dates; collect rows to update
    val rowsToUpdate =
      scala.collection.mutable.Buffer[(Int, SheetRow)]() // (sheet row number 1-indexed, csv row)
    val overlappingDates =
      existingByDate.keySet.intersect(csvByDate.keySet)
    for (date <- overlappingDates) {
      val existingForDate = existingByDate(date).sortBy(_._1.energy)
      val csvForDate = csvByDate(date).sortBy(_.energy)

      val existingTuples =
        existingForDate.map(r => (roundEnergy(r._1.energy), r._1.durationMinutes))
      val csvTuples =
        csvForDate.map(r => (roundEnergy(r.energy), r.durationMinutes))

      if (existingTuples != csvTuples) {
        if (existingForDate.length != csvForDate.length) {
          System.err.println(s"MISMATCH on date $date (different session count)!")
          System.err.println(
            s"  Sheet: ${existingForDate.length} sessions, CSV: ${csvForDate.length} sessions"
          )
          System.err.println("Aborting import.")
          System.exit(1)
        }

        // Check if all pairs are within tolerance (<= 1 kWh and <= 1 minute)
        val pairs = existingForDate.zip(csvForDate)
        val allClose = pairs.forall { case ((existing, _), csv) =>
          math.abs(existing.energy - csv.energy) <= 1.0 &&
            math.abs(existing.durationMinutes - csv.durationMinutes) <= 1
        }

        if (!allClose) {
          System.err.println(s"MISMATCH on date $date!")
          System.err.println(s"  Sheet: $existingTuples")
          System.err.println(s"  CSV:   $csvTuples")
          System.err.println("Aborting import.")
          System.exit(1)
        }

        // Close match: queue updates
        pairs.foreach { case ((_, sheetIdx), csv) =>
          val sheetRowNumber = sheetIdx + 2 // row 2 = index 0
          rowsToUpdate += ((sheetRowNumber, csv))
        }
      }
    }

    // Apply updates for close matches
    if (rowsToUpdate.nonEmpty) {
      println(s"Updating ${rowsToUpdate.length} rows with small differences...")
      for ((sheetRowNumber, row) <- rowsToUpdate) {
        val updateRange = s"'$sheetName'!A$sheetRowNumber:C$sheetRowNumber"
        val updateValues = java.util.Arrays.asList(
          java.util.Arrays.asList(
            row.date.asInstanceOf[AnyRef],
            f"${row.energy}%.2f".asInstanceOf[AnyRef],
            row.durationMinutes.asInstanceOf[AnyRef]
          )
        )
        val updateBody = new ValueRange().setValues(updateValues)
        sheetsService
          .spreadsheets()
          .values()
          .update(spreadsheetId, updateRange, updateBody)
          .setValueInputOption("USER_ENTERED")
          .execute()
        println(
          f"  Updated row $sheetRowNumber: ${row.date}  ${row.energy}%.2f kWh  ${row.durationMinutes} min"
        )
      }
    }

    // New rows = CSV dates not in sheet
    val newRows = csvRows.filter(r => !existingByDate.contains(r.date))

    if (newRows.isEmpty && rowsToUpdate.isEmpty) {
      println("No new rows to insert. Sheet is up to date.")
      return
    }

    if (newRows.isEmpty) {
      println("No new rows to insert.")
      return
    }

    println(s"Inserting ${newRows.length} new rows...")

    // 7. Insert new rows at the correct position to maintain descending date order.
    // Existing dates in sheet order (row 2, 3, ... = newest first).
    val existingDates = existingRows.map(_.date)

    // For each new row, find the 0-based insertion index among existing dates.
    // The sheet is sorted descending, so the row goes before the first existing
    // date that is strictly less than (i.e. older than) the new row's date.
    val newRowsWithInsertIndex = newRows.map { row =>
      val idx = existingDates.indexWhere(_ < row.date)
      val insertIdx = if (idx == -1) existingDates.length else idx
      (insertIdx, row)
    }

    // Group by insertion index and sort rows within each group newest-first.
    // Process groups from largest index to smallest so earlier insertions
    // don't shift the positions of later ones.
    val groupedInsertions = newRowsWithInsertIndex
      .groupBy(_._1)
      .view
      .mapValues(_.map(_._2).sortBy(_.date).reverse)
      .toList
      .sortBy(-_._1)

    for ((insertIdx, rows) <- groupedInsertions) {
      val sheetStartIndex = insertIdx + 1 // +1 for header row (0-indexed for API)

      val insertRequest = new InsertDimensionRequest()
        .setRange(
          new DimensionRange()
            .setSheetId(sheetId)
            .setDimension("ROWS")
            .setStartIndex(sheetStartIndex)
            .setEndIndex(sheetStartIndex + rows.length)
        )
        .setInheritFromBefore(false)

      val batchUpdateRequest = new BatchUpdateSpreadsheetRequest()
        .setRequests(
          Collections.singletonList(
            new Request().setInsertDimension(insertRequest)
          )
        )

      sheetsService
        .spreadsheets()
        .batchUpdate(spreadsheetId, batchUpdateRequest)
        .execute()

      val sheetRowStart = sheetStartIndex + 1 // 1-indexed for A1 notation
      val writeRange =
        s"'$sheetName'!A$sheetRowStart:C${sheetRowStart + rows.length - 1}"
      val writeValues = rows.map { row =>
        java.util.Arrays
          .asList(
            row.date.asInstanceOf[AnyRef],
            f"${row.energy}%.2f".asInstanceOf[AnyRef],
            row.durationMinutes.asInstanceOf[AnyRef]
          )
      }.asJava

      val body = new ValueRange().setValues(writeValues)

      sheetsService
        .spreadsheets()
        .values()
        .update(spreadsheetId, writeRange, body)
        .setValueInputOption("USER_ENTERED")
        .execute()
    }

    println(s"Successfully inserted ${newRows.length} new rows.")
    newRows.sortBy(_.date).reverse.foreach { row =>
      println(
        f"  ${row.date}  ${row.energy}%.2f kWh  ${row.durationMinutes} min"
      )
    }
  }

  private def buildSheetsService(keyFilePath: String): Sheets = {
    val httpTransport = GoogleNetHttpTransport.newTrustedTransport()
    val jsonFactory = GsonFactory.getDefaultInstance

    val credentials = ServiceAccountCredentials
      .fromStream(new FileInputStream(keyFilePath))
      .createScoped(Collections.singleton(SheetsScopes.SPREADSHEETS))

    new Sheets.Builder(
      httpTransport,
      jsonFactory,
      new HttpCredentialsAdapter(credentials)
    ).setApplicationName(ApplicationName).build()
  }

  private def parseExistingRow(
      row: java.util.List[AnyRef]
  ): Option[SheetRow] = {
    if (row.size() >= 3) {
      scala.util.Try {
        val date = row.get(0).toString.trim
        val energy = row.get(1).toString.trim.replace(",", ".").toDouble
        val duration = row.get(2).toString.trim.replace(",", ".").toDouble.toLong
        SheetRow(date, energy, duration)
      }.toOption
    } else {
      None
    }
  }

  private def parseChargingTime(chargingTime: String): Long = {
    val parts = chargingTime.split(":")
    val hours = parts(0).toLong
    val minutes = parts(1).toLong
    val seconds = parts(2).toLong
    hours * 60 + minutes + (if (seconds >= 30) 1 else 0)
  }

  private def roundEnergy(energy: Double): String = f"$energy%.2f"
}
