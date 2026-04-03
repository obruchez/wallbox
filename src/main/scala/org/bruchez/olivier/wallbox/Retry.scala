package org.bruchez.olivier.wallbox

import org.bruchez.olivier.wallbox.Logger.log

import scala.annotation.tailrec
import scala.util.{Failure, Success, Try}

object Retry {
  private val DefaultMaxRetries = 3
  private val DefaultDelayMs = 2000L

  def withRetry[T](
      maxRetries: Int = DefaultMaxRetries,
      delayMs: Long = DefaultDelayMs
  )(f: => Try[T]): Try[T] = {
    @tailrec
    def attempt(remaining: Int, lastFailure: Failure[T]): Try[T] =
      if (remaining <= 0) {
        lastFailure
      } else {
        log(s"Retrying after error: ${lastFailure.exception.getMessage} ($remaining retries left)")
        Thread.sleep(delayMs)
        f match {
          case success @ Success(_) => success
          case failure @ Failure(_) => attempt(remaining - 1, failure)
        }
      }

    f match {
      case success @ Success(_) => success
      case failure @ Failure(_) => attempt(maxRetries, failure)
    }
  }
}
