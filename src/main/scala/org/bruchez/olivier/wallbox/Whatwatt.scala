package org.bruchez.olivier.wallbox

import io.circe.parser._

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.time.format.DateTimeFormatter
import java.time.{Duration, ZonedDateTime}
import scala.util.Try

object Whatwatt {
  case class Report(
      dateTime: ZonedDateTime,
      inPowerInWatts: Double,
      outPowerInWatts: Double
  ) {
    // Assumption: we either have: inPowerInWatts >= 0 and outPowerInWatts  = 0 (consumption/import)
    //                         or: inPowerInWatts  = 0 and outPowerInWatts >= 0 (injection/export)
    //
    // gridPowerInWatts = inPowerInWatts (if inPowerInWatts >= 0) or -outPowerInWatts (if outPowerInWatts >= 0)
    lazy val gridPowerInWatts: Double = inPowerInWatts - outPowerInWatts
  }

  def apply(): Whatwatt = Whatwatt(host = sys.env("WHATWATT_HOST"))
}

case class Whatwatt(host: String) {
  private val httpClient = HttpClient
    .newBuilder()
    .connectTimeout(Duration.ofSeconds(10))
    .build()

  def report(): Try[Whatwatt.Report] = {
    Try {
      val request = HttpRequest
        .newBuilder()
        .uri(URI.create(s"http://$host/api/v1/report"))
        .timeout(Duration.ofSeconds(30))
        .version(HttpClient.Version.HTTP_1_1)
        .GET()
        .build()

      val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())

      if (response.statusCode() != 200) {
        throw new RuntimeException(s"HTTP request failed with status: ${response.statusCode()}")
      }

      parseResponse(response.body())
    }.flatten
  }

  private def parseResponse(jsonString: String): Try[Whatwatt.Report] = {
    Try {
      parse(jsonString) match {
        case Right(json) =>
          val cursor = json.hcursor

          // Extract date_time
          val dateTimeStr = cursor.downField("report").downField("date_time").as[String] match {
            case Right(value) => value
            case Left(error)  => throw new RuntimeException(s"Failed to extract date_time: $error")
          }

          val dateTime = ZonedDateTime.parse(dateTimeStr, DateTimeFormatter.ISO_ZONED_DATE_TIME)

          // Extract positive active instantaneous power
          val positiveActive = cursor
            .downField("report")
            .downField("instantaneous_power")
            .downField("active")
            .downField("positive")
            .downField("total")
            .as[Double] match {
            case Right(value) => value
            case Left(error) =>
              throw new RuntimeException(s"Failed to extract positive active power: $error")
          }

          // Extract negative active instantaneous power
          val negativeActive = cursor
            .downField("report")
            .downField("instantaneous_power")
            .downField("active")
            .downField("negative")
            .downField("total")
            .as[Double] match {
            case Right(value) => value
            case Left(error) =>
              throw new RuntimeException(s"Failed to extract negative active power: $error")
          }

          Whatwatt.Report(
            dateTime = dateTime,
            inPowerInWatts = positiveActive * 1000.0,
            outPowerInWatts = negativeActive * 1000.0
          )

        case Left(error) =>
          throw new RuntimeException(s"Failed to parse JSON: $error")
      }
    }
  }
}
