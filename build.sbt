name := "wallbox"

version := "1.0"

scalaVersion := "2.13.18"

libraryDependencies += "com.beachape" %% "enumeratum" % "1.9.4"
libraryDependencies += "com.microsoft.playwright" % "playwright" % "1.58.0"

libraryDependencies ++= Seq(
  "io.circe" %% "circe-core" % "0.14.15",
  "io.circe" %% "circe-generic" % "0.14.15",
  "io.circe" %% "circe-parser" % "0.14.15"
)

scalafmtOnCompile := true

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
  "WHATWATT_HOST" -> ""
)
dockerBuildOptions ++= Seq("--platform", "linux/amd64,linux/arm64")

Docker / maintainer := "olivier@bruchez.org"
Docker / packageName := "wallbox"
Docker / version := "1.0.5"
