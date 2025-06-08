package org.bruchez.olivier.wallbox

import enumeratum._
import io.circe._
import io.circe.parser._
import org.bruchez.olivier.wallbox.Wallbox.ExtendedStatus

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.nio.charset.StandardCharsets
import java.time.format.DateTimeFormatter
import java.time.{Duration, LocalDateTime}
import java.util.Base64
import scala.util.Try

// See https://github.com/SKB-CGN/wallbox

object Wallbox {
  // TODO: can this be dynamically determined?
  val MinPowerLimitInAmperes = 6
  val MaxPowerLimitInAmperes = 32

  def apply(): Wallbox = Wallbox(
    username = sys.env("WALLBOX_USERNAME"),
    password = sys.env("WALLBOX_PASSWORD"),
    chargerId = sys.env("WALLBOX_CHARGER_ID")
  )

  case class BasicStatus(rawJsonResponse: String)

  // TODO: check why lastSync is "old" (only the case if no charging is taking place?)

  case class ExtendedStatus(
      lastSync: LocalDateTime,
      chargingPowerInWatts: Double,
      status: Status,
      maxChargingCurrentInAmperes: Int
  )

  object ExtendedStatus {
    implicit val extendedStatusDecoder: Decoder[ExtendedStatus] = new Decoder[ExtendedStatus] {
      final def apply(c: HCursor): Decoder.Result[ExtendedStatus] = {
        val dateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

        for {
          lastSyncStr <- c.downField("last_sync").as[String]
          lastSync = LocalDateTime.parse(lastSyncStr, dateTimeFormatter)
          chargingPower <- c.downField("charging_power").as[Double]
          statusId <- c.downField("status_id").as[Int]
          maxChargingCurrent <- c.downField("config_data").downField("max_charging_current").as[Int]
        } yield ExtendedStatus(
          lastSync = lastSync,
          chargingPowerInWatts = chargingPower * 1000,
          status = Status.fromId(statusId),
          maxChargingCurrentInAmperes = maxChargingCurrent
        )
      }
    }
  }

  sealed trait Status extends EnumEntry {
    def id: Int
  }

  object Status extends Enum[Status] {
    val values: IndexedSeq[Status] = findValues

    case object Disconnected extends Status { val id = 0 }
    case object Error14 extends Status { val id = 14 }
    case object Error15 extends Status { val id = 15 }
    case object Ready161 extends Status { val id = 161 }
    case object Ready162 extends Status { val id = 162 }
    case object Disconnected163 extends Status { val id = 163 }
    case object Waiting extends Status { val id = 164 }
    case object Locked extends Status { val id = 165 }
    case object Updating extends Status { val id = 166 }
    case object Scheduled177 extends Status { val id = 177 }
    case object Paused extends Status { val id = 178 }
    case object Scheduled179 extends Status { val id = 179 }
    case object WaitingForCarDemand180 extends Status { val id = 180 }
    case object WaitingForCarDemand181 extends Status { val id = 181 }
    case object Paused182 extends Status { val id = 182 }
    case object WaitingInQueueByPowerSharing183 extends Status { val id = 183 }
    case object WaitingInQueueByPowerSharing184 extends Status { val id = 184 }
    case object WaitingInQueueByPowerBoost185 extends Status { val id = 185 }
    case object WaitingInQueueByPowerBoost186 extends Status { val id = 186 }
    case object WaitingMIDFailed extends Status { val id = 187 }
    case object WaitingMIDSafetyMarginExceeded extends Status { val id = 188 }
    case object WaitingInQueueByEcoSmart extends Status { val id = 189 }
    case object Charging193 extends Status { val id = 193 }
    case object Charging194 extends Status { val id = 194 }
    case object Charging195 extends Status { val id = 195 }
    case object Discharging extends Status { val id = 196 }
    case object Locked209 extends Status { val id = 209 }
    case object LockedCarConnected extends Status { val id = 210 }
    case class Unknown(id: Int) extends Status

    def fromId(id: Int): Status = values.find(_.id == id).getOrElse(Unknown(id))
  }

  implicit class StatusOps(status: Status) {
    import Status._

    def charging: Boolean = Set[Status](Charging193, Charging194, Charging195).contains(status)
  }
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

  def setMaxCurrent(maxCurrenInAmperes: Int): Try[Unit] =
    for {
      token <- authenticationToken()
      _ <- setMaxCurrent(token, maxCurrenInAmperes)
    } yield ()

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

  def extendedStatus(): Try[Wallbox.ExtendedStatus] =
    for {
      token <- authenticationToken()
      extendedStatus <- extendedStatus(token)
    } yield extendedStatus

  def extendedStatus(token: String): Try[Wallbox.ExtendedStatus] = Try {
    val client = createHttpClient()

    val request = createRequestBuilder(BASEURL + URL_STATUS + chargerId)
      .header("Authorization", s"Bearer $token")
      .GET()
      .build()

    val response = client.send(request, HttpResponse.BodyHandlers.ofString())
    val body = checkResponseStatus(response, "Extended status")

    val result: Either[Error, ExtendedStatus] = for {
      json <- parse(body)
      status <- json.as[ExtendedStatus]
    } yield status

    result.fold(throw _, identity)
  }
}
