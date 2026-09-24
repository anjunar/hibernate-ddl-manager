package io.github.hibernateddl.postgresql

import io.github.hibernateddl.core.*
import io.github.hibernateddl.executor.*
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.{Callable, CountDownLatch, Executors, TimeUnit}
import javax.sql.DataSource
import scala.util.Using

/** Runs against a temporary real PostgreSQL cluster; never uses a configured database. */
class PostgreSqlExecutorSuite extends munit.FunSuite:
  private var postgres: EmbeddedPostgres = null
  private val executor = new JdbcMigrationExecutor(PostgreSqlMigrationBackend)

  override def beforeAll(): Unit =
    val target = java.nio.file.Path.of("target").toAbsolutePath
    Files.createDirectories(target)
    postgres = EmbeddedPostgres.builder()
      .setDataDirectory(Files.createTempDirectory(target, "executor-pg-"))
      .setServerConfig("listen_addresses", "127.0.0.1")
      .setServerConfig("synchronous_commit", "on")
      .setPort(0)
      .start()

  override def afterAll(): Unit =
    if postgres != null then postgres.close()

  private def withDatabase[A](body: DataSource => A): A =
    val name = "executor_" + UUID.randomUUID().toString.replace("-", "")
    execute(postgres.getPostgresDatabase, s"CREATE DATABASE $name")
    try body(postgres.getDatabase("postgres", name))
    finally execute(postgres.getPostgresDatabase, s"DROP DATABASE $name WITH (FORCE)")

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
  private val request = MigrationRequest("001-user-login", SchemaSnapshot(1, 0, SchemaModel(Vector(users))),
    SchemaSnapshot(1, 1, SchemaModel(Vector(renamed))))

  private def fixture(ds: DataSource): Unit =
    execute(ds, "CREATE TABLE public.users (username varchar(100) NOT NULL)")
    execute(ds, "INSERT INTO public.users VALUES ('patrick')")

  private def noHistory(ds: DataSource): Unit =
    assertEquals(scalar(ds, "SELECT to_regclass('__hibernate_ddl.schema_history') IS NULL"), "t")

  test("server migration commits table and column renames with data and history, then is idempotent") {
    withDatabase { ds =>
      fixture(ds)
      val result = executor.migrate(ds, request)
      assertEquals(result.status, MigrationStatus.Applied)
      assertEquals(result.statementCount, 2)
      assertEquals(scalar(ds, "SELECT login_name FROM public.accounts"), "patrick")
      assertEquals(scalar(ds, "SELECT to_revision FROM __hibernate_ddl.schema_history"), "1")
      assertEquals(executor.migrate(ds, request).status, MigrationStatus.AlreadyApplied)
      assertEquals(scalar(ds, "SELECT count(*) FROM __hibernate_ddl.schema_history"), "1")
    }
  }

  test("first server start creates a table and records the initial revision") {
    withDatabase { ds =>
      val initial = MigrationRequest("001-create-users", SchemaSnapshot(1, 0, SchemaModel(Vector.empty)),
        SchemaSnapshot(1, 1, SchemaModel(Vector(users))))
      val result = executor.migrate(ds, initial)
      assertEquals(result.status, MigrationStatus.Applied)
      assertEquals(result.statementCount, 1)
      execute(ds, "INSERT INTO public.users(username) VALUES ('created')")
      assertEquals(scalar(ds, "SELECT username FROM public.users"), "created")
      assertEquals(executor.migrate(ds, initial).status, MigrationStatus.AlreadyApplied)
      assertEquals(scalar(ds, "SELECT count(*) FROM __hibernate_ddl.schema_history"), "1")
    }
  }

  test("server adds a nullable column to a populated table without losing data") {
    withDatabase { ds =>
      fixture(ds)
      val biography = ColumnModel(SchemaId("USER_BIO"), SqlIdentifier("biography"), SqlType.Text)
      val expanded = MigrationRequest("001-add-bio", request.previous,
        SchemaSnapshot(1, 1, SchemaModel(Vector(users.copy(columns = users.columns :+ biography)))))
      assertEquals(executor.migrate(ds, expanded).statementCount, 1)
      assertEquals(scalar(ds, "SELECT username FROM public.users WHERE biography IS NULL"), "patrick")
      assertEquals(scalar(ds, "SELECT to_revision FROM __hibernate_ddl.schema_history"), "1")
    }
  }

  test("adding a non-null column to existing rows is rejected before a database connection") {
    withDatabase { ds =>
      fixture(ds)
      val required = ColumnModel(SchemaId("USER_BIO"), SqlIdentifier("biography"), SqlType.Text, false)
      val unsafe = MigrationRequest("001-required-bio", request.previous,
        SchemaSnapshot(1, 1, SchemaModel(Vector(users.copy(columns = users.columns :+ required)))))
      assertEquals(intercept[MigrationException](executor.migrate(ds, unsafe)).state, FailureState.NotStarted)
      assertEquals(scalar(ds, "SELECT username FROM public.users"), "patrick")
      noHistory(ds)
    }
  }

  test("baseline drift aborts before rename and rolls back new history objects") {
    withDatabase { ds =>
      fixture(ds)
      execute(ds, "ALTER TABLE public.users ADD COLUMN unexpected text")
      val error = intercept[MigrationException](executor.migrate(ds, request))
      assertEquals(error.state, FailureState.RolledBack)
      assertEquals(scalar(ds, "SELECT username FROM public.users"), "patrick")
      noHistory(ds)
    }
  }

  test("a later DDL failure rolls back earlier DDL and history atomically") {
    withDatabase { ds =>
      fixture(ds)
      execute(ds, "CREATE TABLE public.z_other (value text)")
      execute(ds, "CREATE SEQUENCE public.occupied")
      val other = TableModel(SchemaId("Z_OTHER"), QualifiedName(SqlIdentifier("z_other"), Some(SqlIdentifier("public"))),
        Vector(ColumnModel(SchemaId("Z_VALUE"), SqlIdentifier("value"), SqlType.Text)))
      val failure = request.copy(
        previous = request.previous.copy(model = SchemaModel(Vector(users, other))),
        target = request.target.copy(model = SchemaModel(Vector(renamed, other.copy(name = other.name.copy(name = SqlIdentifier("occupied"))))))
      )
      val error = intercept[MigrationException](executor.migrate(ds, failure))
      assertEquals(error.state, FailureState.RolledBack)
      assertEquals(scalar(ds, "SELECT username FROM public.users"), "patrick")
      assertEquals(scalar(ds, "SELECT to_regclass('public.accounts') IS NULL"), "t")
      noHistory(ds)
    }
  }

  test("reusing a migration ID with changed content is rejected") {
    withDatabase { ds =>
      fixture(ds)
      executor.migrate(ds, request)
      val changed = request.copy(target = request.target.copy(model = SchemaModel(Vector(
        renamed.copy(name = renamed.name.copy(name = SqlIdentifier("changed")))
      ))))
      intercept[MigrationException](executor.migrate(ds, changed))
      assertEquals(scalar(ds, "SELECT login_name FROM public.accounts"), "patrick")
      assertEquals(scalar(ds, "SELECT count(*) FROM __hibernate_ddl.schema_history"), "1")
    }
  }

  test("successive revisions work and an older server cannot start against a newer schema") {
    withDatabase { ds =>
      fixture(ds)
      executor.migrate(ds, request)
      val next = MigrationRequest("002-user-login", request.target, SchemaSnapshot(1, 2, SchemaModel(Vector(
        renamed.copy(columns = Vector(login.copy(name = SqlIdentifier("handle")))))
      )))
      assertEquals(executor.migrate(ds, next).status, MigrationStatus.Applied)
      intercept[MigrationException](executor.migrate(ds, request))
      assertEquals(scalar(ds, "SELECT handle FROM public.accounts"), "patrick")
      assertEquals(scalar(ds, "SELECT count(*) FROM __hibernate_ddl.schema_history"), "2")
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
            executor.migrate(ds, request)
        ))
        start.countDown()
        val statuses = runs.map(_.get(15, TimeUnit.SECONDS).status).toSet
        assertEquals(statuses, Set(MigrationStatus.Applied, MigrationStatus.AlreadyApplied))
        assertEquals(scalar(ds, "SELECT count(*) FROM __hibernate_ddl.schema_history"), "1")
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
          assertEquals(intercept[MigrationException](impatient.migrate(ds, request)).state, FailureState.RolledBack)
        finally blocker.rollback()
      }
      assertEquals(scalar(ds, "SELECT username FROM public.users"), "patrick")
      noHistory(ds)
      assertEquals(executor.migrate(ds, request).status, MigrationStatus.Applied)
    }
  }

  test("already-applied migrations still reject database drift") {
    withDatabase { ds =>
      fixture(ds)
      executor.migrate(ds, request)
      execute(ds, "ALTER TABLE public.accounts ALTER COLUMN login_name DROP NOT NULL")
      intercept[MigrationException](executor.migrate(ds, request))
      assertEquals(scalar(ds, "SELECT count(*) FROM __hibernate_ddl.schema_history"), "1")
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
        intercept[MigrationException](executor.migrate(ds, request))
        assertEquals(scalar(ds, "SELECT username FROM public.users"), "patrick")
        noHistory(ds)
      }
    }
  }

  test("primary keys are created, survive a key column rename and are verified") {
    withDatabase { ds =>
      val id = ColumnModel(SchemaId("ACCOUNT_ID"), SqlIdentifier("id"), SqlType.BigInt, false)
      val accounts = TableModel(SchemaId("ACCOUNT"), QualifiedName(SqlIdentifier("accounts"), Some(SqlIdentifier("public"))),
        Vector(id, login), Vector(id.id))
      val create = MigrationRequest("001-accounts", SchemaSnapshot(1, 0, SchemaModel(Vector.empty)),
        SchemaSnapshot(1, 1, SchemaModel(Vector(accounts))))
      assertEquals(executor.migrate(ds, create).status, MigrationStatus.Applied)
      execute(ds, "INSERT INTO public.accounts VALUES (1, 'patrick')")
      val renamed = accounts.copy(columns = Vector(id.copy(name = SqlIdentifier("account_id")), login))
      val rename = MigrationRequest("002-account-id", create.target, SchemaSnapshot(1, 2, SchemaModel(Vector(renamed))))
      assertEquals(executor.migrate(ds, rename).status, MigrationStatus.Applied)
      assertEquals(scalar(ds, "SELECT username FROM public.accounts WHERE account_id = 1"), "patrick")
      intercept[java.sql.SQLException](execute(ds, "INSERT INTO public.accounts VALUES (1, 'duplicate')"))
    }
  }

  test("a modeled primary key missing from the database is drift") {
    withDatabase { ds =>
      fixture(ds)
      val keyed = users.copy(primaryKey = Vector(login.id))
      val keyedRequest = MigrationRequest("001-keyed", SchemaSnapshot(1, 0, SchemaModel(Vector(keyed))),
        SchemaSnapshot(1, 1, SchemaModel(Vector(keyed.copy(name = keyed.name.copy(name = SqlIdentifier("accounts")))))))
      val error = intercept[MigrationException](executor.migrate(ds, keyedRequest))
      assert(error.getMessage.contains("primary key"))
      assertEquals(scalar(ds, "SELECT username FROM public.users"), "patrick")
      noHistory(ds)
    }
  }

  test("all supported native types can be verified and unchanged columns survive migration") {
    withDatabase { ds =>
      fixture(ds)
      execute(ds, "ALTER TABLE public.users ADD n integer, ADD big bigint, ADD active boolean, ADD description text")
      val extras = Vector(
        ColumnModel(SchemaId("N"), SqlIdentifier("n"), SqlType.Integer),
        ColumnModel(SchemaId("BIG"), SqlIdentifier("big"), SqlType.BigInt),
        ColumnModel(SchemaId("ACTIVE"), SqlIdentifier("active"), SqlType.Boolean),
        ColumnModel(SchemaId("DESCRIPTION"), SqlIdentifier("description"), SqlType.Text)
      )
      val expanded = request.copy(
        previous = request.previous.copy(model = SchemaModel(Vector(users.copy(columns = users.columns ++ extras)))),
        target = request.target.copy(model = SchemaModel(Vector(renamed.copy(columns = renamed.columns ++ extras))))
      )
      assertEquals(executor.migrate(ds, expanded).status, MigrationStatus.Applied)
      assertEquals(scalar(ds, "SELECT login_name FROM public.accounts"), "patrick")
    }
  }
