import net.virtualvoid.sbt.graph.Plugin._

lazy val riakTestDocker = (project in file("."))
  .enablePlugins(JavaAppPackaging)
  .settings(
    exportJars := true,
    scalaVersion := "2.11.8",

    organization := "com.basho.riak.test",
    name := "riak-test-docker",
    version := "0.1.0-SNAPSHOT",

    resolvers ++= Seq(
      "Local Maven Repo" at "file:///" + Path.userHome + "/.m2/repository",
      Resolver.sonatypeRepo("snapshots"),
      Resolver.bintrayRepo("hseeberger", "maven")
    ),

    libraryDependencies ++= {
      val scalaLoggingVersion = "2.1.2"
      val jacksonVersion = "2.7.3"
      val akkaVersion = "2.4.4"
      val junitVersion = "4.12"
      val scalaTestVersion = "3.0.0-M15"

      Seq(
        // Logging
        "com.typesafe.scala-logging" %% "scala-logging-slf4j" % scalaLoggingVersion,

        // JUnit
        "junit" % "junit" % junitVersion,

        // Jackson JSON
        "com.fasterxml.jackson.module" %% "jackson-module-scala" % jacksonVersion,

        // Akka
        "com.typesafe.akka" %% "akka-stream" % akkaVersion,
        "com.typesafe.akka" %% "akka-http-core" % akkaVersion,

        "com.basho.riak.protobuf" % "riak-pb" % "2.1.1.1-SNAPSHOT",

        // Akka HTTP Docker
        "com.jbrisbin.docker" %% "akka-http-docker" % "0.1.0-SNAPSHOT",

        // Testing
        "org.hamcrest" % "hamcrest-library" % "1.3" % "test",
        "org.scalatest" %% "scalatest" % scalaTestVersion % "test",
        "ch.qos.logback" % "logback-classic" % "1.1.7" % "test"
      )
    },

    ivyScala := ivyScala.value map {
      _.copy(overrideScalaVersion = true)
    },

    graphSettings
  )
