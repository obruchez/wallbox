package org.bruchez.olivier.wallbox

import io.circe._
import io.circe.parser._

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.Base64
import scala.util.Try

// See https://github.com/SKB-CGN/wallbox

object Wallbox {
  def apply(): Wallbox = Wallbox(
    username = sys.env("WALLBOX_USERNAME"),
    password = sys.env("WALLBOX_PASSWORD"),
    chargerId = sys.env("WALLBOX_CHARGER_ID")
  )

  case class BasicStatus(rawJsonResponse: String)

  // TODO: charging_power => "charging_power": 10.9445 (kW)
  // TODO: last_sync => "last_sync": "2025-05-27 20:32:28"
  // TODO: "status_id" (e.g. 194 charging, 181 Waiting for car demand, etc.)
  // TODO: "config_data"."max_charging_current" (e.g. 21)
  case class ExtendedStatus(rawJsonResponse: String)
}

case class Wallbox(username: String, password: String, chargerId: String) {

  private val BASEURL = "https://api.wall-box.com/"
  private val URL_AUTHENTICATION = "auth/token/user"
  private val URL_CHARGER = "v2/charger/"
  private val URL_STATUS = "chargers/status/"

  private val conn_timeout = Duration.ofMillis(3000)

  private def createHttpClient(): HttpClient = {
    HttpClient
      .newBuilder()
      .connectTimeout(conn_timeout)
      .build()
  }

  private def createRequestBuilder(uri: String): HttpRequest.Builder = {
    HttpRequest
      .newBuilder()
      .uri(URI.create(uri))
      .timeout(conn_timeout)
      .header("Accept", "application/json, text/plain, */*")
      .header("Content-Type", "application/json;charset=utf-8")
  }

  private def checkResponseStatus(response: HttpResponse[String], operation: String): String = {
    if (response.statusCode() >= 200 && response.statusCode() < 300) {
      response.body()
    } else {
      throw new RuntimeException(
        s"$operation failed with status: ${response.statusCode()}, body: ${response.body()}"
      )
    }
  }

  def authenticationToken(): Try[String] = Try {
    val client = createHttpClient()

    val credentials = s"$username:$password"
    val encodedCredentials = Base64.getEncoder.encodeToString(
      credentials.getBytes(StandardCharsets.UTF_8)
    )

    val request = createRequestBuilder(BASEURL + URL_AUTHENTICATION)
      .header("Authorization", s"Basic $encodedCredentials")
      .POST(HttpRequest.BodyPublishers.ofString(""))
      .build()

    val response = client.send(request, HttpResponse.BodyHandlers.ofString())
    val responseBody = checkResponseStatus(response, "Authentication")

    parse(responseBody) match {
      case Right(json) =>
        json.hcursor.downField("jwt").as[String] match {
          case Right(jwt) => jwt
          case Left(_)    => throw new RuntimeException("JWT token not found in response")
        }
      case Left(error) =>
        throw new RuntimeException(s"Failed to parse JSON response: ${error.getMessage}")
    }
  }


  private def updateCharger(token: String, key: String, value: Json): Try[String] = Try {
    val client = createHttpClient()

    val jsonPayload = Json.obj(key -> value).noSpaces

    val request = createRequestBuilder(BASEURL + URL_CHARGER + chargerId)
      .header("Authorization", s"Bearer $token")
      .PUT(HttpRequest.BodyPublishers.ofString(jsonPayload))
      .build()

    val response = client.send(request, HttpResponse.BodyHandlers.ofString())
    checkResponseStatus(response, "Update charger")
  }

  def setMaxCurrent(token: String, maxCurrenInAmperes: Int): Try[Unit] =
    updateCharger(token, "maxChargingCurrent", Json.fromInt(maxCurrenInAmperes)).map(_ => ())

  // We don't need anything from the basic status at the moment; everything we need is in the extended status
  def basicStatus(token: String): Try[Wallbox.BasicStatus] = Try {
    val client = createHttpClient()

    val request = createRequestBuilder(BASEURL + URL_CHARGER + chargerId)
      .header("Authorization", s"Bearer $token")
      .GET()
      .build()

    val response = client.send(request, HttpResponse.BodyHandlers.ofString())
    val body = checkResponseStatus(response, "Basic status")

    Wallbox.BasicStatus(body)
  }

  def extendedStatus(token: String): Try[Wallbox.ExtendedStatus] = Try {
    val client = createHttpClient()

    val request = createRequestBuilder(BASEURL + URL_STATUS + chargerId)
      .header("Authorization", s"Bearer $token")
      .GET()
      .build()

    val response = client.send(request, HttpResponse.BodyHandlers.ofString())
    val body = checkResponseStatus(response, "Extended status")

    Wallbox.ExtendedStatus(body)
  }
}
