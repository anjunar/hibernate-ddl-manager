package com.anjunar.hibernateddl.postgresql

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import org.postgresql.ds.PGSimpleDataSource
import java.nio.file.Files
import java.util.UUID
import javax.sql.DataSource
import scala.util.Using

/** Runs against real PostgreSQL: the server named by the JDBC URL in HIBERNATE_DDL_TEST_POSTGRES,
  * for example in the cloud, or otherwise a temporary embedded cluster. Every test uses its own
  * temporary database, so an existing server keeps its other databases untouched.
  */
trait TestPostgres extends munit.FunSuite:
  private var embedded: EmbeddedPostgres = null
  private var database: Option[String] => DataSource = null

  override def beforeAll(): Unit =
    database = sys.env.get("HIBERNATE_DDL_TEST_POSTGRES") match
      case Some(url) => name =>
        val dataSource = new PGSimpleDataSource()
        dataSource.setUrl(url)
        name.foreach(dataSource.setDatabaseName)
        dataSource
      case None =>
        val target = java.nio.file.Path.of("target").toAbsolutePath
        Files.createDirectories(target)
        embedded =
          try EmbeddedPostgres.builder()
            .setDataDirectory(Files.createTempDirectory(target, "executor-pg-"))
            .setServerConfig("listen_addresses", "127.0.0.1")
            .setServerConfig("synchronous_commit", "on")
            .setPort(0)
            .start()
          catch case error: Exception => throw new IllegalStateException(
            "Embedded PostgreSQL did not start (it refuses to run as root). " +
              "Set HIBERNATE_DDL_TEST_POSTGRES to the JDBC URL of an existing server instead.", error)
        name => name.fold(embedded.getPostgresDatabase)(embedded.getDatabase("postgres", _))

  override def afterAll(): Unit =
    if embedded != null then embedded.close()

  protected def withDatabase[A](body: DataSource => A): A =
    val name = "executor_" + UUID.randomUUID().toString.replace("-", "")
    execute(database(None), s"CREATE DATABASE $name")
    try body(database(Some(name)))
    finally execute(database(None), s"DROP DATABASE $name WITH (FORCE)")

  protected def execute(ds: DataSource, sql: String): Unit =
    Using.resource(ds.getConnection) { connection =>
      Using.resource(connection.createStatement())(_.execute(sql))
    }

  protected def scalar(ds: DataSource, sql: String): String =
    Using.resource(ds.getConnection) { connection =>
      Using.resource(connection.createStatement()) { statement =>
        Using.resource(statement.executeQuery(sql)) { rows =>
          assert(rows.next())
          rows.getString(1)
        }
      }
    }
