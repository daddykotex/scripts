
scalaVersion := "2.13.1"

name := "scripts"
organization := "ch.epfl.scala"
version := "1.0"

libraryDependencies ++= Seq(
  "org.typelevel" %% "cats-core"           % "2.0.0",
  "co.fs2"        %% "fs2-core"            % "2.2.2",
  "org.http4s"    %% "http4s-blaze-client" % "0.21.0",
  "org.http4s"    %% "http4s-circe"        % "0.21.0",
  "org.http4s"    %% "http4s-dsl"          % "0.21.0"
)

val circeVersion = "0.13.0"

libraryDependencies ++= Seq(
  "io.circe" %% "circe-core",
  "io.circe" %% "circe-generic",
  "io.circe" %% "circe-parser",
  "io.circe" %% "circe-fs2"
).map(_ % circeVersion)
