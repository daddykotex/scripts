scalaVersion := "2.13.1"

name := "scripts"
organization := "ch.epfl.scala"
version := "1.0"

libraryDependencies ++= Seq(
  "org.typelevel" %% "cats-core"           % "2.1.1",
  "co.fs2"        %% "fs2-core"            % "2.3.0",
  "org.http4s"    %% "http4s-blaze-client" % "0.21.2",
  "org.http4s"    %% "http4s-circe"        % "0.21.2",
  "org.http4s"    %% "http4s-dsl"          % "0.21.2"
)

val circeVersion = "0.13.0"

libraryDependencies ++= Seq(
  "io.circe" %% "circe-core",
  "io.circe" %% "circe-generic",
  "io.circe" %% "circe-parser",
  "io.circe" %% "circe-fs2"
).map(_ % circeVersion)
