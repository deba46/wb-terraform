// We use sbt-dynver to auto-compute the version, so we MUST NOT set `version` here.
// See: https://github.com/sbt/sbt-dynver
ThisBuild / dynverSonatypeSnapshots := true // add `-SNAPSHOT` to versions from non-master branches
ThisBuild / dynverSeparator := "-"

ThisBuild / organization := "it.agilelab"
ThisBuild / scalaVersion := "2.13.8"
ThisBuild / scalacOptions := Seq("-unchecked", "-deprecation", "-Ywarn-unused")

// https://www.scala-sbt.org/1.x/docs/Publishing.html#Version+scheme
ThisBuild / versionScheme := Some("semver-spec")

ThisBuild / parallelExecution := false

lazy val framework = (project in file("framework"))
  .settings(
    name := "spin-framework",
    libraryDependencies ++= Dependencies.frameworkDependencies,
    libraryDependencies ++= Dependencies.testDependencies,
    libraryDependencies ++= Dependencies.http4sDependencies,
    libraryDependencies ++= Dependencies.circeDependencies
  )
  .settings(
    Compile / guardrailTasks := GuardrailHelpers.createGuardrailTasks((Compile / sourceDirectory).value / "openapi") {
      openApiFile =>
        List(
          ScalaServer(
            openApiFile.file,
            pkg = "it.agilelab.spinframework.app.api.generated",
            framework = "http4s",
            tracing = false
          )
        )
    },
    coverageExcludedPackages := "it.agilelab.spinframework.app.api.generated.*"
  )

lazy val spmock = (project in file("spmock"))
  .settings(
    name := "spin-spmock",
    libraryDependencies ++= Dependencies.testDependencies
  )
  .dependsOn(framework)

lazy val terraform = (project in file("terraform"))
  .settings(
    name := "terraform-provisioner",
    libraryDependencies ++= Dependencies.testDependencies,
    Compile / mainClass := Some("it.agilelab.provisioners.Main")
  )
  .enablePlugins(JavaAppPackaging)
  .dependsOn(framework)

lazy val root = (project in file("."))
  .aggregate(framework, spmock, terraform)
  .settings(
    name := "witboost-mesh-provisioning-terraform-specificprovisioner"
  )