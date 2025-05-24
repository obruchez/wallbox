import java.time.{LocalDateTime, Duration}
import java.time.format.DateTimeFormatter
import scala.io.Source
import scala.util.{Try, Using}

// TODO: cleanup

case class WallboxSession(
    organization: String,
    location: String,
    serialNumber: String,
    charger: String,
    userName: String,
    userRole: String,
    subscriptionToChargers: String,
    userEmail: String,
    userRfid: String,
    sessionId: String,
    start: LocalDateTime,
    end: LocalDateTime,
    chargingTime: String,
    energy: Double,
    midEnergy: Double,
    costPerKwh: Double,
    currency: String,
    sessionCost: Double,
    sessionType: String,
    fixedFee: String,
    variableFeeType: String,
    variableFeePrice: String,
    amountExclVat: String,
    vatPercentage: String,
    vat: String,
    totalAmount: String
) {
  // Computed properties
  def duration: Duration = Duration.between(start, end)
  def durationInHours: Double = duration.toMinutes / 60.0
  def averagePowerKw: Double = if (durationInHours > 0) energy / durationInHours else 0.0
  def isAnonymous: Boolean = userName.trim == "Anonymous"
  def month: String = start.format(DateTimeFormatter.ofPattern("yyyy-MM"))
  def year: Int = start.getYear
}

object WallboxCsvParser {

  private val dateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

  def parseFile(filename: String): Try[List[WallboxSession]] = {
    Using(Source.fromFile(filename)) { source =>
      val lines = source.getLines().toList
      parseLines(lines)
    }
  }

  def parseContent(csvContent: String): List[WallboxSession] = {
    val lines = csvContent.split("\n").toList
    parseLines(lines)
  }

  private def parseLines(lines: List[String]): List[WallboxSession] = {
    lines match {
      case header :: dataLines =>
        dataLines.flatMap(parseLine)
      case _ =>
        List.empty
    }
  }

  private def parseLine(line: String): Option[WallboxSession] = {
    Try {
      val fields = parseCsvLine(line)

      if (fields.length >= 26) {
        WallboxSession(
          organization = cleanString(fields(0)),
          location = cleanString(fields(1)),
          serialNumber = cleanString(fields(2)),
          charger = cleanString(fields(3)),
          userName = cleanString(fields(4)),
          userRole = cleanString(fields(5)),
          subscriptionToChargers = cleanString(fields(6)),
          userEmail = cleanString(fields(7)),
          userRfid = cleanString(fields(8)),
          sessionId = cleanString(fields(9)),
          start = LocalDateTime.parse(cleanString(fields(10)), dateFormatter),
          end = LocalDateTime.parse(cleanString(fields(11)), dateFormatter),
          chargingTime = cleanString(fields(12)),
          energy = parseDouble(fields(13)),
          midEnergy = parseDouble(fields(14)),
          costPerKwh = parseDouble(fields(15)),
          currency = cleanString(fields(16)),
          sessionCost = parseDouble(fields(17)),
          sessionType = cleanString(fields(18)),
          fixedFee = cleanString(fields(19)),
          variableFeeType = cleanString(fields(20)),
          variableFeePrice = cleanString(fields(21)),
          amountExclVat = cleanString(fields(22)),
          vatPercentage = cleanString(fields(23)),
          vat = cleanString(fields(24)),
          totalAmount = cleanString(fields(25))
        )
      } else {
        throw new IllegalArgumentException(s"Expected 26 fields, got ${fields.length}")
      }
    }.toOption
  }

  private def parseCsvLine(line: String): Array[String] = {
    val fields = scala.collection.mutable.ArrayBuffer[String]()
    val currentField = new StringBuilder()
    var inQuotes = false
    var i = 0

    while (i < line.length) {
      val char = line.charAt(i)

      char match {
        case '"' if !inQuotes =>
          inQuotes = true
        case '"' if inQuotes =>
          if (i + 1 < line.length && line.charAt(i + 1) == '"') {
            // Escaped quote
            currentField.append('"')
            i += 1
          } else {
            inQuotes = false
          }
        case ',' if !inQuotes =>
          fields += currentField.toString()
          currentField.clear()
        case _ =>
          currentField.append(char)
      }
      i += 1
    }

    fields += currentField.toString()
    fields.toArray
  }

  private def cleanString(value: String): String = {
    value.trim.stripPrefix("\"").stripSuffix("\"")
  }

  private def parseDouble(value: String): Double = {
    val cleaned = cleanString(value).replace(",", ".")
    if (cleaned.isEmpty || cleaned == "0,00" || cleaned == "0.00") 0.0
    else Try(cleaned.toDouble).getOrElse(0.0)
  }

  def analyzeByMonth(sessions: List[WallboxSession]): Map[String, SessionStats] = {
    sessions
      .groupBy(_.month)
      .view
      .mapValues(calculateStats)
      .toMap
  }

  def analyzeByYear(sessions: List[WallboxSession]): Map[Int, SessionStats] = {
    sessions
      .groupBy(_.year)
      .view
      .mapValues(calculateStats)
      .toMap
  }

  def analyzeByUser(sessions: List[WallboxSession]): Map[String, SessionStats] = {
    sessions
      .groupBy(_.userName)
      .view
      .mapValues(calculateStats)
      .toMap
  }

  def calculateStats(sessions: List[WallboxSession]): SessionStats = {
    SessionStats(
      sessionCount = sessions.length,
      totalEnergy = sessions.map(_.energy).sum,
      totalCost = sessions.map(_.sessionCost).sum,
      averageEnergy = sessions.map(_.energy).sum / sessions.length,
      averageCost = sessions.map(_.sessionCost).sum / sessions.length,
      averagePower = sessions.map(_.averagePowerKw).sum / sessions.length,
      totalDurationHours = sessions.map(_.durationInHours).sum
    )
  }
}

case class SessionStats(
    sessionCount: Int,
    totalEnergy: Double,
    totalCost: Double,
    averageEnergy: Double,
    averageCost: Double,
    averagePower: Double,
    totalDurationHours: Double
) {
  def summary: String = {
    f"""Session Statistics:
       |  Sessions: $sessionCount
       |  Total Energy: ${totalEnergy}%.2f kWh
       |  Total Cost: ${totalCost}%.2f CHF
       |  Average Energy per Session: ${averageEnergy}%.2f kWh
       |  Average Cost per Session: ${averageCost}%.2f CHF
       |  Average Power: ${averagePower}%.2f kW
       |  Total Duration: ${totalDurationHours}%.1f hours""".stripMargin
  }
}

/*object WallboxAnalyzer extends App {

  def analyzeFile(filename: String): Unit = {
    WallboxCsvParser.parseFile(filename) match {
      case scala.util.Success(sessions) =>
        println(s"Parsed ${sessions.length} charging sessions")

        // Overall statistics
        val overallStats = WallboxCsvParser.calculateStats(sessions)
        println("\n" + overallStats.summary)

        // Monthly analysis
        println("\n=== Monthly Analysis ===")
        val monthlyStats = WallboxCsvParser.analyzeByMonth(sessions)
        monthlyStats.toSeq.sortBy(_._1).foreach { case (month, stats) =>
          println(s"\n$month:")
          println(stats.summary)
        }

        // Yearly analysis
        println("\n=== Yearly Analysis ===")
        val yearlyStats = WallboxCsvParser.analyzeByYear(sessions)
        yearlyStats.toSeq.sortBy(_._1).foreach { case (year, stats) =>
          println(s"\n$year:")
          println(stats.summary)
        }

        // Recent sessions
        println("\n=== Recent Sessions ===")
        sessions
          .sortBy(_.start.toString)
          .takeRight(5)
          .foreach { session =>
            println(
              f"${session.start} | ${session.energy}%.2f kWh | ${session.sessionCost}%.2f CHF | ${session.averagePowerKw}%.1f kW"
            )
          }

      case scala.util.Failure(exception) =>
        println(s"Failed to parse CSV: ${exception.getMessage}")
        exception.printStackTrace()
    }
  }

  // Usage example
  if (args.nonEmpty) {
    analyzeFile(args(0))
  } else {
    println("Usage: scala WallboxAnalyzer <csv-file>")
  }
}*/
