ThisBuild / organization := "com.anjunar.hibernateddl"
ThisBuild / version := "0.1.0-SNAPSHOT"
ThisBuild / scalaVersion := "3.9.0"

lazy val commonSettings = Seq(
  scalacOptions ++= Seq("-deprecation", "-feature", "-unchecked", "-release:17"),
  javacOptions ++= Seq("--release", "17", "-encoding", "UTF-8"),
  libraryDependencies += "org.scalameta" %% "munit" % "1.2.0" % Test
)

lazy val root = (project in file("."))
  .aggregate(schemaCore, schemaHibernate, schemaExecutor, schemaPostgresql, schemaCli)
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
  .dependsOn(schemaCore, schemaExecutor)
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
