name := "wallbox"

version := "1.0"

scalaVersion := "2.13.18"

libraryDependencies += "com.beachape" %% "enumeratum" % "1.9.4"
libraryDependencies += "com.microsoft.playwright" % "playwright" % "1.58.0"
libraryDependencies += "com.google.apis" % "google-api-services-sheets" % "v4-rev20251110-2.0.0"
libraryDependencies += "com.google.auth" % "google-auth-library-oauth2-http" % "1.42.1"
libraryDependencies += "com.sun.mail" % "javax.mail" % "1.6.2"

libraryDependencies ++= Seq(
  "io.circe" %% "circe-core" % "0.14.15",
  "io.circe" %% "circe-generic" % "0.14.15",
  "io.circe" %% "circe-parser" % "0.14.15"
)

scalafmtOnCompile := true

Compile / mainClass := Some("org.bruchez.olivier.wallbox.Main")
assembly / mainClass := Some("org.bruchez.olivier.wallbox.WallboxToSheets")
assembly / assemblyJarName := "wallbox-to-sheets.jar"

assembly / assemblyMergeStrategy := {
  case PathList("META-INF", "MANIFEST.MF") => MergeStrategy.discard
  case PathList("META-INF", "services", _*) => MergeStrategy.concat
  case PathList("META-INF", _*) => MergeStrategy.discard
  case "module-info.class" => MergeStrategy.discard
  case x if x.endsWith(".proto") => MergeStrategy.first
  case x => MergeStrategy.defaultMergeStrategy(x)
}

enablePlugins(JavaAppPackaging, DockerPlugin)

dockerBaseImage := "eclipse-temurin:11-jre"
dockerRepository := Some("obruchez")
//dockerExposedPorts := Seq(8080)
dockerEnvVars := Map(
  "KOSTAL_HOST" -> "",
  "KOSTAL_PASSWORD" -> "",
  "WALLBOX_USERNAME" -> "",
  "WALLBOX_PASSWORD" -> "",
  "WALLBOX_CHARGER_ID" -> "",
  "WALLBOX_CURRENT_POWER_CONVERSION_JSON" -> "",
  "WHATWATT_HOST" -> "",
  "GMAIL_APP_PASSWORD" -> ""
)
dockerBuildOptions ++= Seq("--platform", "linux/amd64,linux/arm64")

Docker / maintainer := "olivier@bruchez.org"
Docker / packageName := "wallbox"
Docker / version := "1.0.6"
