ThisBuild / scalaVersion := "3.3.8"
ThisBuild / organization := "lab.yjgbg"
ThisBuild / version := "0.1.0-SNAPSHOT"
ThisBuild / scalacOptions ++= Seq("-deprecation", "-feature", "-unchecked")

val http4sVersion = "0.23.37"
val circeVersion = "0.14.16"
val apispecVersion = "0.11.10"
val scribeVersion = "3.19.0"

lazy val root = project.in(file(".")).settings(
  name := "sprout-http",
  libraryDependencies ++= Seq(
    "org.typelevel" %% "cats-effect" % "3.7.1",
    "org.http4s" %% "http4s-ember-server" % http4sVersion,
    "org.http4s" %% "http4s-circe" % http4sVersion,
    "io.circe" %% "circe-core" % circeVersion,
    "io.circe" %% "circe-parser" % circeVersion,
    "com.softwaremill.sttp.apispec" %% "openapi-model" % apispecVersion,
    "com.softwaremill.sttp.apispec" %% "openapi-circe" % apispecVersion,
    "com.outr" %% "scribe" % scribeVersion,
    "com.outr" %% "scribe-slf4j2" % scribeVersion,
    "org.typelevel" %% "munit-cats-effect" % "2.2.0" % Test,
    "org.http4s" %% "http4s-ember-client" % http4sVersion % Test,
    "io.swagger.parser.v3" % "swagger-parser-v3" % "2.1.48" % Test
  ),
  Test / fork := true,
  Test / javaOptions += "-XX:ActiveProcessorCount=4"
)

lazy val example = project.in(file("example")).dependsOn(root).settings(
  name := "sprout-http-example",
  publish / skip := true,
  outputStrategy := Some(StdoutOutput),
  Compile / run / fork := true,
  Compile / mainClass := Some("sproutexample.Main")
)
