import sbt.url

// sbt 2: bare settings in build.sbt are common settings that sbt injects into every
// subproject, so they replace the former `ThisBuild /` settings. A bare setting applies
// everywhere, the root included; what only one module needs belongs to that module.

version := "1.0.0"
organization := "com.anjunar.hibernateddl"
organizationName := "Anjunar"
organizationHomepage := Some(url("https://github.com/anjunar"))

scalaVersion := "3.9.0"
scalacOptions ++= Seq("-deprecation", "-feature", "-unchecked", "-release:17")
javacOptions ++= Seq("--release", "17", "-encoding", "UTF-8")
libraryDependencies += "org.scalameta" %% "munit" % "1.2.0" % Test

// Maven Central: every published module carries this POM metadata. A release is signed and
// staged locally in target/sona-staging by publishSigned; scripts/publish-central.* uploads it.
homepage := Some(url("https://github.com/anjunar/hibernate-ddl-manager"))
description :=
  "Schema evolution from Hibernate metadata: stable schema IDs, a planned diff and a transactional PostgreSQL migration at server start."
licenses := Seq("MIT" -> url("https://opensource.org/licenses/MIT"))
scmInfo := Some(ScmInfo(
  url("https://github.com/anjunar/hibernate-ddl-manager"),
  "scm:git:https://github.com/anjunar/hibernate-ddl-manager.git",
  Some("scm:git:git@github.com:anjunar/hibernate-ddl-manager.git")
))
developers := List(Developer(
  id = "anjunar",
  name = "Patrick Bittner",
  email = "anjunar@gmx.de",
  url = url("https://github.com/anjunar")
))
versionScheme := Some("early-semver")
pomIncludeRepository := { _ => false }
publishMavenStyle := true
publishTo := {
  val centralSnapshots = "https://central.sonatype.com/repository/maven-snapshots/"
  if (isSnapshot.value) Some("central-snapshots" at centralSnapshots)
  else localStaging.value
}

// sbt 2 puts a module on the classpath of its dependents as a JAR by default. The sbt server
// keeps these JARs open, and on Windows packageBin cannot replace an open file by renaming
// (AccessDeniedException on the .tmp rename from the second run on). Class directories avoid
// that; project/build.sbt does the same for the meta-build.
exportJars := false

// Publish rule: a published module depends only on published modules and external
// artifacts, or its POM would point at an artifact that never reaches Maven Central.
// Published: schema-core, schema-hibernate, schema-executor, schema-postgresql,
// schema-integration. Not published (`publish / skip := true`): the root and schema-cli.

lazy val root = (project in file("."))
  .aggregate(schemaCore, schemaHibernate, schemaExecutor, schemaPostgresql, schemaIntegration, schemaCli)
  .settings(
    name := "hibernate-ddl-manager",
    publish / skip := true
  )

lazy val schemaCore = (project in file("modules/schema-core"))
  .settings(
    name := "schema-core",
    description := "Schema model, validation, stable IDs and the migration diff; no Hibernate or JDBC dependency."
  )

lazy val schemaHibernate = (project in file("modules/schema-hibernate"))
  .dependsOn(schemaCore)
  .settings(
    name := "schema-hibernate",
    description := "Reads the target schema from Hibernate boot metadata with @SchemaId identities.",
    libraryDependencies += "org.hibernate.orm" % "hibernate-core" % "7.4.10.Final"
  )

lazy val schemaPostgresql = (project in file("modules/schema-postgresql"))
  // The Hibernate entities of schema-hibernate's tests drive the end-to-end tests.
  .dependsOn(schemaCore, schemaExecutor, schemaHibernate % "test->test")
  .settings(
    name := "schema-postgresql",
    description := "PostgreSQL 14+ dialect: SQL rendering, catalog inspection, locking and migration history.",
    libraryDependencies += "io.zonky.test" % "embedded-postgres" % "2.2.2" % Test,
    Test / fork := true,
    Test / parallelExecution := false
  )

lazy val schemaExecutor = (project in file("modules/schema-executor"))
  .dependsOn(schemaCore)
  .settings(
    name := "schema-executor",
    description := "Transactional migration executor, shared planner, history, backfills and read-only preview."
  )

lazy val schemaIntegration = (project in file("modules/schema-integration"))
  .dependsOn(schemaHibernate % "compile->compile;test->test", schemaExecutor,
    schemaPostgresql % "compile->compile;test->test")
  .settings(
    name := "schema-integration",
    description := "Runs the migration when Hibernate builds its SessionFactory, configured by hibernate.ddl_manager.* settings.",
    // Hibernate reads and writes JSON columns through Jackson.
    libraryDependencies += "com.fasterxml.jackson.core" % "jackson-databind" % "2.22.3" % Test,
    Test / fork := true,
    Test / parallelExecution := false
  )

lazy val schemaCli = (project in file("modules/schema-cli"))
  .dependsOn(schemaCore, schemaPostgresql % "compile->compile;test->test")
  .settings(
    name := "schema-cli",
    publish / skip := true,
    // The preview command connects on its own.
    libraryDependencies += "org.postgresql" % "postgresql" % "42.7.10",
    Test / fork := true,
    Test / parallelExecution := false,
    Compile / mainClass := Some("com.anjunar.hibernateddl.cli.Main")
  )

// sbt 2's `test` is incremental and replays unchanged results from the disk cache, even after
// `clean`; `testFull` runs every test, which a release check must.
addCommandAlias("check", ";clean;testFull")
addCommandAlias("demo", "schemaCli/run demo")
