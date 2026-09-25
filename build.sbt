ThisBuild / organization := "com.anjunar.hibernateddl"
ThisBuild / version := "1.0.0"
ThisBuild / scalaVersion := "3.9.0"

lazy val commonSettings = Seq(
  scalacOptions ++= Seq("-deprecation", "-feature", "-unchecked", "-release:17"),
  javacOptions ++= Seq("--release", "17", "-encoding", "UTF-8"),
  libraryDependencies += "org.scalameta" %% "munit" % "1.2.0" % Test
)

lazy val root = (project in file("."))
  .aggregate(schemaCore, schemaHibernate, schemaExecutor, schemaPostgresql, schemaIntegration, schemaCli)
  .settings(
    name := "hibernate-ddl-manager",
    publish / skip := true
  )

lazy val schemaCore = (project in file("modules/schema-core"))
  .settings(commonSettings)
  .settings(name := "schema-core")

lazy val schemaHibernate = (project in file("modules/schema-hibernate"))
  .dependsOn(schemaCore)
  .settings(commonSettings)
  .settings(
    name := "schema-hibernate",
    libraryDependencies += "org.hibernate.orm" % "hibernate-core" % "7.4.10.Final"
  )

lazy val schemaPostgresql = (project in file("modules/schema-postgresql"))
  // The Hibernate entities of schema-hibernate's tests drive the end-to-end tests.
  .dependsOn(schemaCore, schemaExecutor, schemaHibernate % "test->test")
  .settings(commonSettings)
  .settings(
    name := "schema-postgresql",
    libraryDependencies += "io.zonky.test" % "embedded-postgres" % "2.2.2" % Test,
    Test / fork := true,
    Test / parallelExecution := false
  )

lazy val schemaExecutor = (project in file("modules/schema-executor"))
  .dependsOn(schemaCore)
  .settings(commonSettings)
  .settings(name := "schema-executor")

lazy val schemaIntegration = (project in file("modules/schema-integration"))
  .dependsOn(schemaHibernate % "compile->compile;test->test", schemaExecutor,
    schemaPostgresql % "compile->compile;test->test")
  .settings(commonSettings)
  .settings(
    name := "schema-integration",
    // Hibernate reads and writes JSON columns through Jackson.
    libraryDependencies += "com.fasterxml.jackson.core" % "jackson-databind" % "2.22.3" % Test,
    Test / fork := true,
    Test / parallelExecution := false
  )

lazy val schemaCli = (project in file("modules/schema-cli"))
  .dependsOn(schemaCore, schemaPostgresql)
  .settings(commonSettings)
  .settings(
    name := "schema-cli",
    publish / skip := true,
    Compile / mainClass := Some("com.anjunar.hibernateddl.cli.Main")
  )

addCommandAlias("check", ";clean;test")
addCommandAlias("demo", "schemaCli/run demo")
