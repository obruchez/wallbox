package org.bruchez.olivier.wallbox

import java.time.Instant
import java.util.Properties
import javax.mail._
import javax.mail.internet._

object EmailNotifier {
  private val MinSecondsBetweenEmails = 3600 // 1 hour

  private val GmailAddress = "olivier.bruchez@gmail.com"

  def apply(): Option[EmailNotifier] =
    scala.util.Try(sys.env("GMAIL_APP_PASSWORD")).toOption.filter(_.nonEmpty).map { password =>
      new EmailNotifier(password)
    }
}

class EmailNotifier(appPassword: String) {
  private var lastEmailSentAt: Option[Instant] = None

  def sendEmail(subject: String, body: String, throttle: Boolean = true): Unit = {
    if (throttle) {
      val now = Instant.now()
      val throttled = lastEmailSentAt.exists { lastSent =>
        java.time.Duration.between(lastSent, now).getSeconds < EmailNotifier.MinSecondsBetweenEmails
      }

      if (throttled) {
        Logger.log("Email throttled (already sent one recently)")
        return
      }
    }

    try {
      val props = new Properties()
      props.put("mail.smtp.auth", "true")
      props.put("mail.smtp.starttls.enable", "true")
      props.put("mail.smtp.host", "smtp.gmail.com")
      props.put("mail.smtp.port", "587")

      val session = Session.getInstance(
        props,
        new Authenticator {
          override def getPasswordAuthentication: PasswordAuthentication =
            new PasswordAuthentication(EmailNotifier.GmailAddress, appPassword)
        }
      )

      val message = new MimeMessage(session)
      message.setFrom(new InternetAddress(EmailNotifier.GmailAddress))
      message.setRecipient(
        Message.RecipientType.TO,
        new InternetAddress(EmailNotifier.GmailAddress)
      )
      message.setSubject(subject)
      message.setText(body)

      Transport.send(message)

      if (throttle) lastEmailSentAt = Some(Instant.now())
      Logger.log(s"Email sent: $subject")
    } catch {
      case e: Exception =>
        Logger.log(s"Failed to send error email: ${e.getMessage}")
    }
  }
}
