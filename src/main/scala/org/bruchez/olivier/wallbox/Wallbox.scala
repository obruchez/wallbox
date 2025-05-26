package org.bruchez.olivier.wallbox

import io.circe._
import io.circe.parser._

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.Base64
import scala.util.{Failure, Success, Try}

object Wallbox {
  def apply(): Wallbox = Wallbox(
    username = sys.env("WALLBOX_USERNAME"),
    password = sys.env("WALLBOX_PASSWORD"),
    chargerId = sys.env("WALLBOX_CHARGER_ID")
  )

  def test(): Unit = {
    val wallbox = Wallbox()

    val unitTry =
      for {
        token <- wallbox.authenticationToken()
        _ <- wallbox.updateCharger(token, "maxChargingCurrent", Json.fromInt(21))
      } yield ()

    unitTry match {
      case Success(_) =>
        println("Update successful")

      case Failure(t) =>
        println(s"Failure: ${t.getMessage}")
    }
  }
}

case class Wallbox(username: String, password: String, chargerId: String) {

  private val BASEURL = "https://api.wall-box.com/"
  private val URL_AUTHENTICATION = "auth/token/user"
  private val URL_CHARGER = "v2/charger/"
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

  def authenticationToken(): Try[String] = {
    Try {
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
  }

  def updateCharger(token: String, key: String, value: Json): Try[String] = {
    Try {
      val client = createHttpClient()

      val jsonPayload = Json.obj(key -> value).noSpaces

      val request = createRequestBuilder(BASEURL + URL_CHARGER + chargerId)
        .header("Authorization", s"Bearer $token")
        .PUT(HttpRequest.BodyPublishers.ofString(jsonPayload))
        .build()

      val response = client.send(request, HttpResponse.BodyHandlers.ofString())
      checkResponseStatus(response, "Update charger")
    }
  }

  // See https://github.com/SKB-CGN/wallbox

  /*
let password = 'YourPassword';
let email = 'you@email.com';
let charger_id = 'idOfTheCharger';
let wallbox_token = '';
let conn_timeout = 3000;

const BASEURL = 'https://api.wall-box.com/';
const URL_AUTHENTICATION = 'auth/token/user';
const URL_CHARGER = 'v2/charger/';
const URL_CHARGER_CONTROL = 'v3/chargers/';
const URL_CHARGER_MODES = 'v4/chargers/';
const URL_CHARGER_ACTION = '/remote-action';
const URL_STATUS = 'chargers/status/';
const URL_CONFIG = 'chargers/config/';
const URL_REMOTE_ACTION = '/remote-action/';
const URL_ECO_SMART = '/eco-smart/';
   */

  /*
  Control the Wallbox:

  const options = {
    url: BASEURL + URL_CHARGER + charger_id,
    timeout: conn_timeout,
    method: 'PUT',
    headers: {
        'Authorization': 'Bearer ' + wallbox_token,
        'Accept': 'application/json, text/plain, */ /*',
        'Content-Type': 'application/json;charset=utf-8',
    },
    data: JSON.stringify({
      [key]: value
    })
  }
   */

  // etc.
}
