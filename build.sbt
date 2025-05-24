name := "wallbox"

version := "1.0"

scalaVersion := "2.13.16"

libraryDependencies += "com.microsoft.playwright" % "playwright" % "1.52.0"

libraryDependencies ++= Seq(
  "io.circe" %% "circe-core" % "0.14.13",
  "io.circe" %% "circe-generic" % "0.14.13",
  "io.circe" %% "circe-parser" % "0.14.13"
)

scalafmtOnCompile := true
