package com.anjunar.hibernateddl.postgresql

import com.anjunar.hibernateddl.core.*
import com.anjunar.hibernateddl.executor.*
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import org.postgresql.ds.PGSimpleDataSource
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.{Callable, CountDownLatch, Executors, TimeUnit}
import javax.sql.DataSource
import scala.util.Using

/** Runs against real PostgreSQL: the server named by the JDBC URL in HIBERNATE_DDL_TEST_POSTGRES,
  * for example in the cloud, or otherwise a temporary embedded cluster. Every test uses its own
  * temporary database, so an existing server keeps its other databases untouched.
  */
class PostgreSqlExecutorSuite extends munit.FunSuite:
  private var embedded: EmbeddedPostgres = null
  private var database: Option[String] => DataSource = null
  private val executor = new JdbcMigrationExecutor(PostgreSqlMigrationBackend)

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

  private def withDatabase[A](body: DataSource => A): A =
    val name = "executor_" + UUID.randomUUID().toString.replace("-", "")
    execute(database(None), s"CREATE DATABASE $name")
    try body(database(Some(name)))
    finally execute(database(None), s"DROP DATABASE $name WITH (FORCE)")

  private def execute(ds: DataSource, sql: String): Unit =
    Using.resource(ds.getConnection) { connection =>
      Using.resource(connection.createStatement())(_.execute(sql))
    }

  private def scalar(ds: DataSource, sql: String): String =
    Using.resource(ds.getConnection) { connection =>
      Using.resource(connection.createStatement()) { statement =>
        Using.resource(statement.executeQuery(sql)) { rows =>
          assert(rows.next())
          rows.getString(1)
        }
      }
    }

  private val login = ColumnModel(SchemaId("USER_LOGIN"), SqlIdentifier("username"), SqlType.Varchar(100), false)
  private val users = TableModel(SchemaId("USER"), QualifiedName(SqlIdentifier("users"), Some(SqlIdentifier("public"))), Vector(login))
  private val renamed = users.copy(
    name = users.name.copy(name = SqlIdentifier("accounts")),
    columns = Vector(login.copy(name = SqlIdentifier("login_name")))
  )
  private val initial = SchemaModel(Vector(users))
  private val target = SchemaModel(Vector(renamed))

  /** The first server start creates the users table; the fixture then adds a row. */
  private def fixture(ds: DataSource, model: SchemaModel = initial): Unit =
    assertEquals(executor.migrate(ds, model), MigrationResult(1, MigrationStatus.Applied, model.tables.size))
    execute(ds, "INSERT INTO public.users(username) VALUES ('patrick')")

  private def noHistory(ds: DataSource): Unit =
    assertEquals(scalar(ds, "SELECT to_regclass('__hibernate_ddl.schema_history') IS NULL"), "t")

  private def revisions(ds: DataSource): String =
    scalar(ds, "SELECT count(*) FROM __hibernate_ddl.schema_history")

  test("the first start creates tables and stores the applied model; restarting changes nothing") {
    withDatabase { ds =>
      fixture(ds)
      assertEquals(scalar(ds, "SELECT username FROM public.users"), "patrick")
      assertEquals(executor.migrate(ds, initial), MigrationResult(1, MigrationStatus.AlreadyApplied, 0))
      assertEquals(revisions(ds), "1")
      assertEquals(scalar(ds, "SELECT model #>> '{tables,0,columns,0,name}' FROM __hibernate_ddl.schema_history"), "username")
      assertEquals(scalar(ds, "SELECT statements[1] FROM __hibernate_ddl.schema_history"),
        "CREATE TABLE \"public\".\"users\" (\"username\" varchar(100) NOT NULL);")
    }
  }

  test("a later start renames table and column against the stored model and keeps data and history") {
    withDatabase { ds =>
      fixture(ds)
      assertEquals(executor.migrate(ds, target), MigrationResult(2, MigrationStatus.Applied, 2))
      assertEquals(scalar(ds, "SELECT login_name FROM public.accounts"), "patrick")
      assertEquals(scalar(ds, "SELECT string_agg(revision::text, ',' ORDER BY revision) FROM __hibernate_ddl.schema_history"), "1,2")
      assertEquals(executor.migrate(ds, target).status, MigrationStatus.AlreadyApplied)
      assertEquals(revisions(ds), "2")
    }
  }

  test("a server that skipped releases applies every change at once and keeps the data") {
    withDatabase { ds =>
      fixture(ds)
      val biography = ColumnModel(SchemaId("USER_BIO"), SqlIdentifier("biography"), SqlType.Text)
      val latest = SchemaModel(Vector(renamed.copy(columns = Vector(login.copy(name = SqlIdentifier("handle")), biography))))
      assertEquals(executor.migrate(ds, latest), MigrationResult(2, MigrationStatus.Applied, 3))
      assertEquals(scalar(ds, "SELECT handle FROM public.accounts WHERE biography IS NULL"), "patrick")
    }
  }

  test("an older server cannot start after a newer migration and leaves the schema untouched") {
    withDatabase { ds =>
      fixture(ds)
      executor.migrate(ds, target)
      val error = intercept[MigrationException](executor.migrate(ds, initial))
      assertEquals(error.state, FailureState.RolledBack)
      assert(error.getMessage.contains("older schema"), error.getMessage)
      assertEquals(scalar(ds, "SELECT login_name FROM public.accounts"), "patrick")
      assertEquals(revisions(ds), "2")
    }
  }

  test("adding a non-null column to existing rows is refused before DDL") {
    withDatabase { ds =>
      fixture(ds)
      val required = ColumnModel(SchemaId("USER_BIO"), SqlIdentifier("biography"), SqlType.Text, false)
      val unsafe = SchemaModel(Vector(users.copy(columns = users.columns :+ required)))
      assert(intercept[MigrationException](executor.migrate(ds, unsafe)).getMessage.contains("backfill"))
      assertEquals(scalar(ds, "SELECT username FROM public.users"), "patrick")
      assertEquals(revisions(ds), "1")
    }
  }

  test("tables that already exist without history are not adopted") {
    withDatabase { ds =>
      execute(ds, "CREATE TABLE public.users (username varchar(100) NOT NULL)")
      assertEquals(intercept[MigrationException](executor.migrate(ds, initial)).state, FailureState.RolledBack)
      noHistory(ds)
    }
  }

  test("database drift aborts before the rename and rolls back") {
    withDatabase { ds =>
      fixture(ds)
      execute(ds, "ALTER TABLE public.users ADD COLUMN unexpected text")
      val error = intercept[MigrationException](executor.migrate(ds, target))
      assertEquals(error.state, FailureState.RolledBack)
      assert(error.getMessage.contains("Previous schema does not match database"), error.getMessage)
      assertEquals(scalar(ds, "SELECT username FROM public.users"), "patrick")
      assertEquals(revisions(ds), "1")
    }
  }

  test("a later DDL failure rolls back earlier DDL and history atomically") {
    withDatabase { ds =>
      val other = TableModel(SchemaId("Z_OTHER"), QualifiedName(SqlIdentifier("z_other"), Some(SqlIdentifier("public"))),
        Vector(ColumnModel(SchemaId("Z_VALUE"), SqlIdentifier("value"), SqlType.Text)))
      fixture(ds, SchemaModel(Vector(users, other)))
      execute(ds, "CREATE SEQUENCE public.occupied")
      val failure = SchemaModel(Vector(renamed, other.copy(name = other.name.copy(name = SqlIdentifier("occupied")))))
      assertEquals(intercept[MigrationException](executor.migrate(ds, failure)).state, FailureState.RolledBack)
      assertEquals(scalar(ds, "SELECT username FROM public.users"), "patrick")
      assertEquals(scalar(ds, "SELECT to_regclass('public.accounts') IS NULL"), "t")
      assertEquals(revisions(ds), "1")
    }
  }

  test("two concurrent server starts apply a migration exactly once") {
    withDatabase { ds =>
      fixture(ds)
      val pool = Executors.newFixedThreadPool(2)
      val start = new CountDownLatch(1)
      try
        val runs = Vector.fill(2)(pool.submit(new Callable[MigrationResult]:
          def call(): MigrationResult =
            start.await()
            executor.migrate(ds, target)
        ))
        start.countDown()
        val statuses = runs.map(_.get(15, TimeUnit.SECONDS).status).toSet
        assertEquals(statuses, Set(MigrationStatus.Applied, MigrationStatus.AlreadyApplied))
        assertEquals(revisions(ds), "2")
      finally
        pool.shutdownNow()
        pool.awaitTermination(5, TimeUnit.SECONDS)
    }
  }

  test("a competing migration lock times out without changing the schema") {
    withDatabase { ds =>
      fixture(ds)
      Using.resource(ds.getConnection) { blocker =>
        blocker.setAutoCommit(false)
        try
          PostgreSqlMigrationBackend.acquireLock(blocker, ExecutionOptions())
          val impatient = new JdbcMigrationExecutor(PostgreSqlMigrationBackend,
            ExecutionOptions(lockTimeoutMillis = 100, statementTimeoutMillis = 2000))
          assertEquals(intercept[MigrationException](impatient.migrate(ds, target)).state, FailureState.RolledBack)
        finally blocker.rollback()
      }
      assertEquals(scalar(ds, "SELECT username FROM public.users"), "patrick")
      assertEquals(revisions(ds), "1")
      assertEquals(executor.migrate(ds, target).status, MigrationStatus.Applied)
    }
  }

  test("an unchanged model still rejects database drift") {
    withDatabase { ds =>
      fixture(ds)
      execute(ds, "ALTER TABLE public.users ALTER COLUMN username DROP NOT NULL")
      intercept[MigrationException](executor.migrate(ds, initial))
      assertEquals(revisions(ds), "1")
    }
  }

  test("unmodeled constraints and defaults are rejected before DDL") {
    Vector(
      "ALTER TABLE public.users ADD PRIMARY KEY (username)",
      "ALTER TABLE public.users ALTER COLUMN username SET DEFAULT 'anonymous'",
      "CREATE INDEX users_login_idx ON public.users(username)"
    ).foreach { unsupported =>
      withDatabase { ds =>
        fixture(ds)
        execute(ds, unsupported)
        intercept[MigrationException](executor.migrate(ds, target))
        assertEquals(scalar(ds, "SELECT username FROM public.users"), "patrick")
        assertEquals(revisions(ds), "1")
      }
    }
  }

  test("primary keys are created, survive a key column rename and are verified") {
    withDatabase { ds =>
      val id = ColumnModel(SchemaId("ACCOUNT_ID"), SqlIdentifier("id"), SqlType.BigInt, false)
      val accounts = TableModel(SchemaId("ACCOUNT"), QualifiedName(SqlIdentifier("accounts"), Some(SqlIdentifier("public"))),
        Vector(id, login), Vector(id.id))
      assertEquals(executor.migrate(ds, SchemaModel(Vector(accounts))).status, MigrationStatus.Applied)
      execute(ds, "INSERT INTO public.accounts VALUES (1, 'patrick')")
      val renamedKey = accounts.copy(columns = Vector(id.copy(name = SqlIdentifier("account_id")), login))
      assertEquals(executor.migrate(ds, SchemaModel(Vector(renamedKey))).status, MigrationStatus.Applied)
      assertEquals(scalar(ds, "SELECT username FROM public.accounts WHERE account_id = 1"), "patrick")
      intercept[java.sql.SQLException](execute(ds, "INSERT INTO public.accounts VALUES (1, 'duplicate')"))
      execute(ds, "ALTER TABLE public.accounts DROP CONSTRAINT accounts_pkey")
      val error = intercept[MigrationException](executor.migrate(ds, SchemaModel(Vector(renamedKey))))
      assert(error.getMessage.contains("primary key"), error.getMessage)
    }
  }

  test("all supported native types can be verified and unchanged columns survive migration") {
    withDatabase { ds =>
      val extras = Vector(
        ColumnModel(SchemaId("N"), SqlIdentifier("n"), SqlType.Integer),
        ColumnModel(SchemaId("BIG"), SqlIdentifier("big"), SqlType.BigInt),
        ColumnModel(SchemaId("ACTIVE"), SqlIdentifier("active"), SqlType.Boolean),
        ColumnModel(SchemaId("DESCRIPTION"), SqlIdentifier("description"), SqlType.Text),
        ColumnModel(SchemaId("TOKEN"), SqlIdentifier("token"), SqlType.Uuid),
        ColumnModel(SchemaId("SEEN"), SqlIdentifier("seen_at"), SqlType.Timestamp(3)),
        ColumnModel(SchemaId("PAID"), SqlIdentifier("paid_at"), SqlType.TimestampWithTimeZone(6)),
        // Quotes and non-ASCII characters must survive the jsonb round trip of the stored model.
        ColumnModel(SchemaId("NOTE"), SqlIdentifier("Note \"ä\" 🙂"), SqlType.Varchar(20))
      )
      fixture(ds, SchemaModel(Vector(users.copy(columns = users.columns ++ extras))))
      val expanded = SchemaModel(Vector(renamed.copy(columns = renamed.columns ++ extras)))
      assertEquals(executor.migrate(ds, expanded).status, MigrationStatus.Applied)
      assertEquals(scalar(ds, "SELECT login_name FROM public.accounts"), "patrick")
      execute(ds, "UPDATE public.accounts SET token = '8f2c4c1e-3f6b-4a8e-9d3a-2b7c1e5f0a94', " +
        "seen_at = '2026-09-24 12:34:56.789', paid_at = '2026-09-24 12:34:56.123456+02'")
      assertEquals(scalar(ds, "SELECT token::text || ' ' || seen_at::text FROM public.accounts"),
        "8f2c4c1e-3f6b-4a8e-9d3a-2b7c1e5f0a94 2026-09-24 12:34:56.789")
      assertEquals(executor.migrate(ds, expanded).status, MigrationStatus.AlreadyApplied)
    }
  }

  test("a precision PostgreSQL cannot store is refused before a connection") {
    withDatabase { ds =>
      val precise = ColumnModel(SchemaId("SEEN"), SqlIdentifier("seen_at"), SqlType.Timestamp(9))
      val error = intercept[MigrationException](executor.migrate(ds, SchemaModel(Vector(users.copy(columns = users.columns :+ precise)))))
      assertEquals(error.state, FailureState.NotStarted)
      assert(error.getMessage.contains("Table 'USER': table column has TIMESTAMP precision 9"), error.getMessage)
      noHistory(ds)
    }
  }

  test("a different timestamp precision or a timestamp without precision is drift") {
    Vector("timestamp(6)", "timestamp", "timestamp(3) with time zone").foreach { actual =>
      withDatabase { ds =>
        val seen = ColumnModel(SchemaId("SEEN"), SqlIdentifier("seen_at"), SqlType.Timestamp(3))
        fixture(ds, SchemaModel(Vector(users.copy(columns = users.columns :+ seen))))
        execute(ds, s"ALTER TABLE public.users ALTER COLUMN seen_at TYPE $actual")
        val error = intercept[MigrationException](executor.migrate(ds, SchemaModel(Vector(users.copy(columns = users.columns :+ seen)))))
        assert(error.getMessage.contains("expected Timestamp(3)"), error.getMessage)
      }
    }
  }

  test("a tampered stored model blocks the start") {
    withDatabase { ds =>
      fixture(ds)
      execute(ds, "UPDATE __hibernate_ddl.schema_history SET model = jsonb_set(model, '{tables,0,name}', '\"people\"')")
      val error = intercept[MigrationException](executor.migrate(ds, initial))
      assert(error.getMessage.contains("does not match its fingerprint"), error.getMessage)
      assertEquals(revisions(ds), "1")
    }
  }

  test("a history table created by another version is refused, not altered") {
    withDatabase { ds =>
      execute(ds, "CREATE SCHEMA __hibernate_ddl")
      execute(ds, "CREATE TABLE __hibernate_ddl.schema_history (migration_id varchar(200) PRIMARY KEY, to_revision bigint)")
      val error = intercept[MigrationException](executor.migrate(ds, initial))
      assert(error.getMessage.contains("another version"), error.getMessage)
      assertEquals(scalar(ds, "SELECT to_regclass('public.users') IS NULL"), "t")
    }
  }
