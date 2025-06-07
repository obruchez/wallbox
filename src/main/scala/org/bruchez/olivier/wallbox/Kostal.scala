package org.bruchez.olivier.wallbox

import io.circe._
import io.circe.parser._

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.security.{MessageDigest, SecureRandom}
import java.time.Duration
import java.util.Base64
import javax.crypto.spec.{GCMParameterSpec, PBEKeySpec, SecretKeySpec}
import javax.crypto.{Cipher, Mac, SecretKeyFactory}
import scala.util.Try

object Kostal {
  def apply(): Kostal = Kostal(host = sys.env("KOSTAL_HOST"), password = sys.env("KOSTAL_PASSWORD"))
}

case class Kostal(host: String, password: String) {
  private val client = HttpClient
    .newBuilder()
    .connectTimeout(Duration.ofSeconds(10))
    .build()

  private val baseUrl = s"http://$host/api/v1"
  private var sessionId: Option[String] = None

  def outputPowerInWatts(): Try[Double] = {
    for {
      _ <- login()
      power <- getOutputPowerInWatts()
    } yield power
  }

  private def login(): Try[Unit] = {
    val clientNonce = new Array[Byte](12)
    new SecureRandom().nextBytes(clientNonce)
    val clientNonceB64 = Base64.getEncoder.encodeToString(clientNonce)

    for {
      authStart <- startAuth(clientNonceB64)
      authFinish <- finishAuth(authStart, clientNonceB64)
      _ <- createSession(authStart, authFinish, clientNonceB64)
    } yield ()
  }

  private case class AuthStartResponse(
      nonce: String,
      transactionId: String,
      salt: String,
      rounds: Int
  )

  private case class AuthFinishResponse(
      token: String,
      signature: String
  )

  private def startAuth(clientNonceB64: String): Try[AuthStartResponse] = Try {
    val requestBody = Json
      .obj(
        "username" -> Json.fromString("user"),
        "nonce" -> Json.fromString(clientNonceB64)
      )
      .noSpaces

    val request = HttpRequest
      .newBuilder()
      .uri(URI.create(s"$baseUrl/auth/start"))
      .header("Content-Type", "application/json")
      .POST(HttpRequest.BodyPublishers.ofString(requestBody))
      .build()

    val response = client.send(request, HttpResponse.BodyHandlers.ofString())

    if (response.statusCode() != 200) {
      throw new RuntimeException(s"Auth start failed: ${response.statusCode()}")
    }

    val json = parse(response.body()).getOrElse(throw new RuntimeException("Invalid JSON"))
    val cursor = json.hcursor

    AuthStartResponse(
      nonce =
        cursor.downField("nonce").as[String].getOrElse(throw new RuntimeException("Missing nonce")),
      transactionId = cursor
        .downField("transactionId")
        .as[String]
        .getOrElse(throw new RuntimeException("Missing transactionId")),
      salt =
        cursor.downField("salt").as[String].getOrElse(throw new RuntimeException("Missing salt")),
      rounds =
        cursor.downField("rounds").as[Int].getOrElse(throw new RuntimeException("Missing rounds"))
    )
  }

  private def finishAuth(
      authStart: AuthStartResponse,
      clientNonceB64: String
  ): Try[AuthFinishResponse] = Try {
    val serverNonce = Base64.getDecoder.decode(authStart.nonce)
    val transactionId = Base64.getDecoder.decode(authStart.transactionId)
    val salt = Base64.getDecoder.decode(authStart.salt)

    // PBKDF2
    val spec = new PBEKeySpec(password.toCharArray, salt, authStart.rounds, 256)
    val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
    val saltedPasswd = factory.generateSecret(spec).getEncoded

    // Client key and stored key
    val clientKey = hmacSha256(saltedPasswd, "Client Key".getBytes("UTF-8"))
    val storedKey = sha256(clientKey)

    // Auth message - this must match the Python implementation exactly
    val authMsg =
      s"n=user,r=$clientNonceB64,r=${authStart.nonce},s=${authStart.salt},i=${authStart.rounds},c=biws,r=${authStart.nonce}"

    // Client signature and proof
    val clientSignature = hmacSha256(storedKey, authMsg.getBytes("UTF-8"))
    val clientProof = clientKey.zip(clientSignature).map { case (a, b) => (a ^ b).toByte }

    // Server signature for verification
    val serverKey = hmacSha256(saltedPasswd, "Server Key".getBytes("UTF-8"))
    val expectedServerSignature = hmacSha256(serverKey, authMsg.getBytes("UTF-8"))

    val requestBody = Json
      .obj(
        "transactionId" -> Json.fromString(authStart.transactionId),
        "proof" -> Json.fromString(Base64.getEncoder.encodeToString(clientProof))
      )
      .noSpaces

    val request = HttpRequest
      .newBuilder()
      .uri(URI.create(s"$baseUrl/auth/finish"))
      .header("Content-Type", "application/json")
      .POST(HttpRequest.BodyPublishers.ofString(requestBody))
      .build()

    val response = client.send(request, HttpResponse.BodyHandlers.ofString())

    if (response.statusCode() != 200) {
      throw new RuntimeException(
        s"Auth finish failed: ${response.statusCode()} - ${response.body()}"
      )
    }

    val json = parse(response.body()).getOrElse(throw new RuntimeException("Invalid JSON"))
    val cursor = json.hcursor

    val token =
      cursor.downField("token").as[String].getOrElse(throw new RuntimeException("Missing token"))
    val signature = cursor
      .downField("signature")
      .as[String]
      .getOrElse(throw new RuntimeException("Missing signature"))
    val receivedSignature = Base64.getDecoder.decode(signature)

    if (!java.util.Arrays.equals(receivedSignature, expectedServerSignature)) {
      throw new RuntimeException("Server signature mismatch")
    }

    AuthFinishResponse(token, signature)
  }

  private def createSession(
      authStart: AuthStartResponse,
      authFinish: AuthFinishResponse,
      clientNonceB64: String
  ): Try[Unit] = Try {
    // Recreate the stored key (same as in finishAuth)
    val salt = Base64.getDecoder.decode(authStart.salt)
    val spec = new PBEKeySpec(password.toCharArray, salt, authStart.rounds, 256)
    val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
    val saltedPasswd = factory.generateSecret(spec).getEncoded
    val clientKey = hmacSha256(saltedPasswd, "Client Key".getBytes("UTF-8"))
    val storedKey = sha256(clientKey)

    // Recreate the auth message (same as in finishAuth)
    val authMsg =
      s"n=user,r=$clientNonceB64,r=${authStart.nonce},s=${authStart.salt},i=${authStart.rounds},c=biws,r=${authStart.nonce}"

    // Create session key following Python implementation exactly
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(new SecretKeySpec(storedKey, "HmacSHA256"))
    mac.update("Session Key".getBytes("UTF-8"))
    mac.update(authMsg.getBytes("UTF-8"))
    mac.update(clientKey)
    val protocolKey = mac.doFinal()

    // Generate session nonce (16 bytes for AES-GCM)
    val sessionNonce = new Array[Byte](16)
    new SecureRandom().nextBytes(sessionNonce)

    // Encrypt the token using AES-GCM
    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    val gcmSpec = new GCMParameterSpec(128, sessionNonce) // 128-bit auth tag
    cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(protocolKey, "AES"), gcmSpec)

    val tokenBytes = authFinish.token.getBytes("UTF-8")
    val encrypted = cipher.doFinal(tokenBytes)

    // For AES-GCM, doFinal returns ciphertext + auth tag concatenated
    // The auth tag is the last 16 bytes
    val cipherText = encrypted.dropRight(16)
    val authTag = encrypted.takeRight(16)

    val requestBody = Json
      .obj(
        "iv" -> Json.fromString(Base64.getEncoder.encodeToString(sessionNonce)),
        "tag" -> Json.fromString(Base64.getEncoder.encodeToString(authTag)),
        "transactionId" -> Json.fromString(authStart.transactionId),
        "payload" -> Json.fromString(Base64.getEncoder.encodeToString(cipherText))
      )
      .noSpaces

    val request = HttpRequest
      .newBuilder()
      .uri(URI.create(s"$baseUrl/auth/create_session"))
      .header("Content-Type", "application/json")
      .POST(HttpRequest.BodyPublishers.ofString(requestBody))
      .build()

    val response = client.send(request, HttpResponse.BodyHandlers.ofString())

    if (response.statusCode() != 200) {
      throw new RuntimeException(
        s"Session creation failed: ${response.statusCode()} - ${response.body()}"
      )
    }

    val json = parse(response.body()).getOrElse(throw new RuntimeException("Invalid JSON"))
    val cursor = json.hcursor

    sessionId = Some(
      cursor
        .downField("sessionId")
        .as[String]
        .getOrElse(throw new RuntimeException("Missing sessionId"))
    )
  }

  private def getOutputPowerInWatts(): Try[Double] = Try {
    val sid = sessionId.getOrElse(throw new RuntimeException("Not logged in"))

    val request = HttpRequest
      .newBuilder()
      .uri(URI.create(s"$baseUrl/processdata/devices:local:ac/InvOut_P"))
      .header("Authorization", s"Session $sid")
      .GET()
      .build()

    val response = client.send(request, HttpResponse.BodyHandlers.ofString())

    if (response.statusCode() != 200) {
      throw new RuntimeException(s"Failed to get power data: ${response.statusCode()}")
    }

    val json = parse(response.body()).getOrElse(throw new RuntimeException("Invalid JSON"))
    val cursor = json.hcursor

    cursor.downArray
      .downField("processdata")
      .downArray
      .downField("value")
      .as[Double]
      .getOrElse(throw new RuntimeException("Could not parse power value"))
  }

  private def hmacSha256(key: Array[Byte], data: Array[Byte]): Array[Byte] = {
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(new SecretKeySpec(key, "HmacSHA256"))
    mac.doFinal(data)
  }

  private def sha256(data: Array[Byte]): Array[Byte] = {
    val digest = MessageDigest.getInstance("SHA-256")
    digest.digest(data)
  }
}
