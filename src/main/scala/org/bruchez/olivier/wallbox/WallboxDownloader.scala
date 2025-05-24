import com.microsoft.playwright._
import com.microsoft.playwright.options.WaitForSelectorState
import java.nio.file.Paths
import scala.util.{Try, Using}

// TODO: cleanup

object WallboxDownloader {

  private val email = "..."
  private val password = "..."

  /*def main(args: Array[String]): Unit = {
    val result = downloadWallboxSessions()
    result match {
      case scala.util.Success(_) =>
        println(s"Process completed - check for $outputFile")
        System.exit(0) // Force clean exit
      case scala.util.Failure(exception) =>
        println(s"Failed to download sessions: ${exception.getMessage}")
        exception.printStackTrace()
        System.exit(1)
    }
  }*/

  def downloadWallboxSessions(): Try[Unit] = Try {
    val playwright = Playwright.create()
    var browser: Browser = null
    var context: BrowserContext = null
    var page: Page = null

    val tempFile =
      try {
        browser = playwright
          .chromium()
          .launch(
            new BrowserType.LaunchOptions().setHeadless(true)
          )

        context = browser.newContext(
          new Browser.NewContextOptions().setAcceptDownloads(true)
        )

        page = context.newPage()

        performLogin(page)
        downloadSessions(page)

      } finally {
        // Close resources gracefully, ignoring any errors during cleanup
        if (page != null) {
          try { page.close() }
          catch { case _: Exception => /* ignore */ }
        }
        if (context != null) {
          try { context.close() }
          catch { case _: Exception => /* ignore */ }
        }
        if (browser != null) {
          try { browser.close() }
          catch { case _: Exception => /* ignore */ }
        }
        try { playwright.close() }
        catch { case _: Exception => /* ignore */ }
      }

    // Parse the temporary file
    WallboxCsvParser.parseFile(tempFile.toString) match {
      case scala.util.Success(sessions) =>
        println(s"Parsed ${sessions.length} sessions from temporary file")
        // Analyze the data
        val stats = WallboxCsvParser.calculateStats(sessions)
        println(stats.summary)

      case scala.util.Failure(exception) =>
        println(s"Failed to parse CSV: ${exception.getMessage}")
    }

    // Optionally clean up the temp file
    java.nio.file.Files.deleteIfExists(tempFile)

  }

  private def performLogin(page: Page): Unit = {
    println("Navigating to login page...")
    page.navigate("https://my.wallbox.com/login")

    println("Clicking login button...")
    page.click(".login-button")

    println("Filling email...")
    page.waitForSelector("[data-test-id=\"emailInput\"]")
    page.fill("[data-test-id=\"emailInput\"]", email)

    println("Clicking email CTA button...")
    page.click("[data-test-id=\"emailCtaButton\"]")

    println("Filling password...")
    page.waitForSelector("[data-test-id=\"passwordInput\"]")
    page.fill("[data-test-id=\"passwordInput\"]", password)

    println("Clicking login CTA...")
    page.click("[data-test-id=\"loginCta\"]")

    println("Waiting for dashboard...")
    page.waitForURL("**/dashboard**", new Page.WaitForURLOptions().setTimeout(10000))
  }

  private def downloadSessions(page: Page): java.nio.file.Path = {
    println("Navigating to sessions page...")
    page.navigate("https://my.wallbox.com/sessions")

    println("Clicking export button...")
    page.click("button:has-text(\"Export\")")

    println("Clicking download CTA...")
    page.click("[data-test-id=\"downloadCta\"]")

    println("Waiting for download...")
    val download = page.waitForDownload(() => {
      // Download should start automatically after clicking downloadCta
    })

    // println(s"Saving download as $outputFile...")
    // download.saveAs(Paths.get(outputFile))

    val tempFile = java.nio.file.Files.createTempFile("wallbox_sessions_", ".csv")
    println(s"Saving download to temporary file: $tempFile")
    download.saveAs(tempFile)

    println("Download completed successfully!")
    tempFile
  }
}
