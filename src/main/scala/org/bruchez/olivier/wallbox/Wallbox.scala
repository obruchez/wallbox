package org.bruchez.olivier.wallbox

import scala.util.{Failure, Success, Try}
import java.util.Base64
import java.nio.charset.StandardCharsets
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.net.URI
import java.time.Duration
import io.circe._
import io.circe.parser._

object Wallbox {
  def main(args: Array[String]): Unit = {
    val wallbox =
      Wallbox(username = "...", password = "...", chargerId = "...")

    val unitTry =
      for {
        token <- wallbox.authenticationToken()
        response <- wallbox.updateCharger(token, "maxChargingCurrent", Json.fromInt(21))
        _ <- Try { println(s"response: $response") }
      } yield ()

    unitTry match {
      case Success(_) => println("Update successful")
      case Failure(t) => println(s"Failure: ${t.getMessage}")
    }
  }
}

case class Wallbox(username: String, password: String, chargerId: String) {

  private val BASEURL = "https://api.wall-box.com/"
  private val URL_AUTHENTICATION = "auth/token/user"
  private val URL_CHARGER = "v2/charger/"
  private val conn_timeout = Duration.ofMillis(3000)

  def authenticationToken(): Try[String] = {
    Try {
      val client = HttpClient
        .newBuilder()
        .connectTimeout(conn_timeout)
        .build()

      val credentials = s"$username:$password"
      val encodedCredentials = Base64.getEncoder.encodeToString(
        credentials.getBytes(StandardCharsets.UTF_8)
      )

      val request = HttpRequest
        .newBuilder()
        .uri(URI.create(BASEURL + URL_AUTHENTICATION))
        .timeout(conn_timeout)
        .header("Authorization", s"Basic $encodedCredentials")
        .header("Accept", "application/json, text/plain, */*")
        .header("Content-Type", "application/json;charset=utf-8")
        .POST(HttpRequest.BodyPublishers.ofString(""))
        .build()

      val response = client.send(request, HttpResponse.BodyHandlers.ofString())

      if (response.statusCode() == 200) {
        parse(response.body()) match {
          case Right(json) =>
            json.hcursor.downField("jwt").as[String] match {
              case Right(jwt) => jwt
              case Left(_)    => throw new RuntimeException("JWT token not found in response")
            }
          case Left(error) =>
            throw new RuntimeException(s"Failed to parse JSON response: ${error.getMessage}")
        }
      } else {
        throw new RuntimeException(
          s"Authentication failed with status: ${response.statusCode()}, body: ${response.body()}"
        )
      }
    }
  }

  def updateCharger(token: String, key: String, value: Json): Try[String] = {
    Try {
      val client = HttpClient
        .newBuilder()
        .connectTimeout(conn_timeout)
        .build()

      val jsonPayload = Json.obj(key -> value).noSpaces

      val request = HttpRequest
        .newBuilder()
        .uri(URI.create(BASEURL + URL_CHARGER + chargerId))
        .timeout(conn_timeout)
        .header("Authorization", s"Bearer $token")
        .header("Accept", "application/json, text/plain, */*")
        .header("Content-Type", "application/json;charset=utf-8")
        .PUT(HttpRequest.BodyPublishers.ofString(jsonPayload))
        .build()

      val response = client.send(request, HttpResponse.BodyHandlers.ofString())

      if (response.statusCode() >= 200 && response.statusCode() < 300) {
        response.body()
      } else {
        throw new RuntimeException(
          s"Update charger failed with status: ${response.statusCode()}, body: ${response.body()}"
        )
      }
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
