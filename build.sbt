name := "QueryTest"

version := "0.1"

scalaVersion := "2.12.3"


val akkaTyped = "com.typesafe.akka" %% "akka-typed" % "2.5.4"
val akkaActors = "com.typesafe.akka" %% "akka-actor"  % "2.5.6"
val akkaStreams ="com.typesafe.akka" %% "akka-stream" % "2.5.6"

libraryDependencies ++= Seq(
  "com.typesafe.akka" %% "akka-http" % "10.0.10",
  "com.typesafe.akka" %% "akka-http-testkit" % "10.0.10" % Test,
//  "com.github.scopt"           %% "scopt"           % "3.6.0",
  "com.typesafe.scala-logging" %% "scala-logging"   % "3.5.0",
  "ch.qos.logback"             %  "logback-classic" % "1.1.7",
//  akkaTyped,
//  "de.heikoseeberger"          %% "akka-http-json4s" % "1.18.0",
  "com.typesafe.akka" %% "akka-http-spray-json" % "10.0.10",
  akkaActors,
  akkaStreams,
  "org.scalatest"              %% "scalatest"       % "3.0.1"   % Test,
  "org.scalamock"              %% "scalamock-scalatest-support" % "3.5.0" % Test
)


