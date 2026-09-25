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
      "ALTER TABLE public.users ADD PRIMARY KEY (username)" -> "primary key",
      "ALTER TABLE public.users ALTER COLUMN username SET DEFAULT 'anonymous'" -> "unsupported default",
      "CREATE INDEX users_login_idx ON public.users(username)" -> "unexpected index (\"username\")",
      "ALTER TABLE public.users ADD CHECK (username <> '')" -> "unexpected check constraint",
      "ALTER TABLE public.users ADD EXCLUDE (username WITH =)" -> "unsupported unmodeled constraints",
      "CREATE FUNCTION public.keep() RETURNS trigger LANGUAGE plpgsql AS $$BEGIN RETURN NEW; END$$; " +
        "CREATE TRIGGER users_keep BEFORE INSERT ON public.users FOR EACH ROW EXECUTE FUNCTION public.keep()" ->
        "unsupported triggers"
    ).foreach { (unsupported, message) =>
      withDatabase { ds =>
        fixture(ds)
        execute(ds, unsupported)
        val error = intercept[MigrationException](executor.migrate(ds, target))
        assert(error.getMessage.contains(message), error.getMessage)
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

  test("further types round-trip data and are verified on restart") {
    withDatabase { ds =>
      val columns = Vector(
        ColumnModel(SchemaId("T_ID"), SqlIdentifier("id"), SqlType.SmallInt, nullable = false),
        ColumnModel(SchemaId("T_CODE"), SqlIdentifier("code"), SqlType.Char(3)),
        ColumnModel(SchemaId("T_PRICE"), SqlIdentifier("price"), SqlType.Numeric(10, 2)),
        ColumnModel(SchemaId("T_TOTAL"), SqlIdentifier("total"), SqlType.Numeric(38, 0)),
        ColumnModel(SchemaId("T_OPENS"), SqlIdentifier("opens_at"), SqlType.Time(0)),
        ColumnModel(SchemaId("T_WEIGHT"), SqlIdentifier("weight"), SqlType.Real),
        ColumnModel(SchemaId("T_RATIO"), SqlIdentifier("ratio"), SqlType.DoublePrecision),
        ColumnModel(SchemaId("T_DAY"), SqlIdentifier("day"), SqlType.Date),
        ColumnModel(SchemaId("T_DATA"), SqlIdentifier("data"), SqlType.Binary)
      )
      val typed = SchemaModel(Vector(TableModel(SchemaId("T"), QualifiedName(SqlIdentifier("typed"), Some(SqlIdentifier("public"))),
        columns, Vector(columns.head.id))))
      assertEquals(executor.migrate(ds, typed).status, MigrationStatus.Applied)
      execute(ds, "INSERT INTO public.typed VALUES (1, 'EUR', 12.345, 99999999999999999999, '08:30:15', 1.5, 0.25, " +
        "'2026-09-24', '\\xcafe')")
      assertEquals(scalar(ds, "SELECT concat_ws(' ', code, price, total, opens_at, weight, ratio, day, data) FROM public.typed"),
        "EUR 12.35 99999999999999999999 08:30:15 1.5 0.25 2026-09-24 \\xcafe")
      assertEquals(executor.migrate(ds, typed).status, MigrationStatus.AlreadyApplied)
      execute(ds, "ALTER TABLE public.typed ALTER COLUMN price TYPE numeric(12,2)")
      val error = intercept[MigrationException](executor.migrate(ds, typed))
      assert(error.getMessage.contains("expected Numeric(10,2)"), error.getMessage)
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

  private val customerId = ColumnModel(SchemaId("CUSTOMER_ID"), SqlIdentifier("id"), SqlType.BigInt, false)
  private val customers = TableModel(SchemaId("CUSTOMER"), QualifiedName(SqlIdentifier("customer"), Some(SqlIdentifier("crm"))),
    Vector(customerId), Vector(customerId.id))
  private val invoiceId = ColumnModel(SchemaId("INVOICE_ID"), SqlIdentifier("id"), SqlType.BigInt, false)
  private val owner = ColumnModel(SchemaId("INVOICE_OWNER"), SqlIdentifier("customer_id"), SqlType.BigInt, false)
  private val correction = ColumnModel(SchemaId("INVOICE_CORRECTION"), SqlIdentifier("correction_id"), SqlType.BigInt)
  private val invoices = TableModel(SchemaId("INVOICE"), QualifiedName(SqlIdentifier("invoice"), Some(SqlIdentifier("public"))),
    Vector(invoiceId, owner, correction), Vector(invoiceId.id), Vector(
      ForeignKeyModel(Vector(owner.id), customers.id, customers.primaryKey),
      ForeignKeyModel(Vector(correction.id), SchemaId("INVOICE"), Vector(invoiceId.id))
    ))
  private val billing = SchemaModel(Vector(invoices, customers))

  private def billingFixture(ds: DataSource): Unit =
    execute(ds, "CREATE SCHEMA crm")
    assertEquals(executor.migrate(ds, billing), MigrationResult(1, MigrationStatus.Applied, 4))
    execute(ds, "INSERT INTO crm.customer VALUES (1)")
    execute(ds, "INSERT INTO public.invoice VALUES (10, 1, NULL), (11, 1, 10)")

  test("foreign keys across schemas and to the own table are created, enforced and verified on restart") {
    withDatabase { ds =>
      billingFixture(ds)
      intercept[java.sql.SQLException](execute(ds, "INSERT INTO public.invoice VALUES (12, 2, NULL)"))
      intercept[java.sql.SQLException](execute(ds, "INSERT INTO public.invoice VALUES (12, 1, 99)"))
      assertEquals(executor.migrate(ds, billing), MigrationResult(1, MigrationStatus.AlreadyApplied, 0))
    }
  }

  test("renaming referenced tables and key columns keeps foreign keys and data") {
    withDatabase { ds =>
      billingFixture(ds)
      val renamed = SchemaModel(Vector(
        invoices.copy(columns = Vector(invoiceId, owner.copy(name = SqlIdentifier("client_id")), correction)),
        customers.copy(name = customers.name.copy(name = SqlIdentifier("client")),
          columns = Vector(customerId.copy(name = SqlIdentifier("client_no"))))
      ))
      assertEquals(executor.migrate(ds, renamed), MigrationResult(2, MigrationStatus.Applied, 3))
      assertEquals(scalar(ds, "SELECT count(*) FROM public.invoice i JOIN crm.client c ON c.client_no = i.client_id"), "2")
      intercept[java.sql.SQLException](execute(ds, "INSERT INTO public.invoice VALUES (12, 2, NULL)"))
    }
  }

  test("a new association adds a nullable column and its foreign key to a populated table") {
    withDatabase { ds =>
      billingFixture(ds)
      val approver = ColumnModel(SchemaId("INVOICE_APPROVER"), SqlIdentifier("approver_id"), SqlType.BigInt)
      val approved = SchemaModel(Vector(invoices.copy(columns = invoices.columns :+ approver,
        foreignKeys = invoices.foreignKeys :+ ForeignKeyModel(Vector(approver.id), customers.id, customers.primaryKey)), customers))
      assertEquals(executor.migrate(ds, approved), MigrationResult(2, MigrationStatus.Applied, 2))
      execute(ds, "UPDATE public.invoice SET approver_id = 1")
      intercept[java.sql.SQLException](execute(ds, "UPDATE public.invoice SET approver_id = 2"))
    }
  }

  test("missing, extra and cascading foreign keys and references from unmodeled tables block the start") {
    Vector(
      "ALTER TABLE public.invoice DROP CONSTRAINT invoice_customer_id_fkey" -> "is missing from",
      "ALTER TABLE public.invoice ADD FOREIGN KEY (customer_id) REFERENCES crm.customer (id)" -> "unexpected foreign key",
      "ALTER TABLE public.invoice DROP CONSTRAINT invoice_customer_id_fkey, " +
        "ADD FOREIGN KEY (customer_id) REFERENCES crm.customer (id) ON DELETE CASCADE" -> "unsupported ON DELETE action",
      "CREATE TABLE public.audit (customer_id bigint REFERENCES crm.customer (id))" -> "unmodeled table \"public\".\"audit\""
    ).foreach { (change, message) =>
      withDatabase { ds =>
        billingFixture(ds)
        execute(ds, change)
        val error = intercept[MigrationException](executor.migrate(ds, billing))
        assert(error.getMessage.contains(message), error.getMessage)
      }
    }
  }

  private val tenant = ColumnModel(SchemaId("USER_TENANT"), SqlIdentifier("tenant"), SqlType.Text)
  private val uniqueUsers = SchemaModel(Vector(users.copy(columns = users.columns :+ tenant,
    uniqueKeys = Vector(UniqueKeyModel(Vector(login.id)), UniqueKeyModel(Vector(tenant.id, login.id))))))

  test("unique keys are created with the table, enforced and verified on restart") {
    withDatabase { ds =>
      fixture(ds, uniqueUsers)
      intercept[java.sql.SQLException](execute(ds, "INSERT INTO public.users(username) VALUES ('patrick')"))
      assertEquals(executor.migrate(ds, uniqueUsers), MigrationResult(1, MigrationStatus.AlreadyApplied, 0))
    }
  }

  test("a unique key added to a populated table keeps its rows; duplicate rows roll the migration back") {
    val keyed = SchemaModel(Vector(users.copy(uniqueKeys = Vector(UniqueKeyModel(Vector(login.id))))))
    withDatabase { ds =>
      fixture(ds)
      assertEquals(executor.migrate(ds, keyed), MigrationResult(2, MigrationStatus.Applied, 1))
      intercept[java.sql.SQLException](execute(ds, "INSERT INTO public.users VALUES ('patrick')"))
    }
    withDatabase { ds =>
      fixture(ds)
      execute(ds, "INSERT INTO public.users VALUES ('patrick')")
      assertEquals(intercept[MigrationException](executor.migrate(ds, keyed)).state, FailureState.RolledBack)
      assertEquals(revisions(ds), "1")
      assertEquals(scalar(ds, "SELECT count(*) FROM public.users"), "2")
    }
  }

  test("renaming a table and its unique columns keeps the unique keys") {
    withDatabase { ds =>
      fixture(ds, uniqueUsers)
      val renamedUnique = SchemaModel(Vector(uniqueUsers.tables.head.copy(name = renamed.name,
        columns = Vector(login.copy(name = SqlIdentifier("login_name")), tenant.copy(name = SqlIdentifier("tenant_id"))))))
      assertEquals(executor.migrate(ds, renamedUnique), MigrationResult(2, MigrationStatus.Applied, 3))
      intercept[java.sql.SQLException](execute(ds, "INSERT INTO public.accounts(login_name) VALUES ('patrick')"))
      assertEquals(executor.migrate(ds, renamedUnique).status, MigrationStatus.AlreadyApplied)
    }
  }

  test("missing, extra, reordered, deferrable, covering and index-only unique keys block the start") {
    Vector(
      "ALTER TABLE public.users DROP CONSTRAINT users_username_key" -> "unique key (\"username\") is missing",
      "ALTER TABLE public.users ADD UNIQUE (tenant)" -> "unexpected unique key (\"tenant\")",
      "ALTER TABLE public.users DROP CONSTRAINT users_tenant_username_key, ADD UNIQUE (username, tenant)" ->
        "unexpected unique key (\"username\", \"tenant\")",
      "ALTER TABLE public.users DROP CONSTRAINT users_username_key, ADD UNIQUE (username) DEFERRABLE" ->
        "unsupported deferrable checking",
      "ALTER TABLE public.users DROP CONSTRAINT users_username_key, ADD UNIQUE (username) INCLUDE (tenant)" ->
        "unsupported INCLUDE columns",
      "ALTER TABLE public.users DROP CONSTRAINT users_username_key; " +
        "CREATE UNIQUE INDEX users_login ON public.users (username)" -> "unsupported uniqueness without a unique constraint"
    ).foreach { (change, message) =>
      withDatabase { ds =>
        fixture(ds, uniqueUsers)
        execute(ds, change)
        val error = intercept[MigrationException](executor.migrate(ds, uniqueUsers))
        assert(error.getMessage.contains(message), error.getMessage)
      }
    }
  }

  test("a NULLS NOT DISTINCT unique key blocks the start on PostgreSQL 15 and newer") {
    withDatabase { ds =>
      assume(scalar(ds, "SHOW server_version_num").toInt >= 150000, "NULLS NOT DISTINCT needs PostgreSQL 15")
      fixture(ds, uniqueUsers)
      execute(ds, "ALTER TABLE public.users DROP CONSTRAINT users_username_key, ADD UNIQUE NULLS NOT DISTINCT (username)")
      val error = intercept[MigrationException](executor.migrate(ds, uniqueUsers))
      assert(error.getMessage.contains("unsupported NULLS NOT DISTINCT"), error.getMessage)
    }
  }

  private val indexedUsers = SchemaModel(Vector(users.copy(columns = users.columns :+ tenant, indexes = Vector(
    IndexModel(Vector(IndexColumn(login.id))),
    IndexModel(Vector(IndexColumn(tenant.id), IndexColumn(login.id, descending = true)))
  ))))

  test("indexes are created after their table and verified on restart") {
    withDatabase { ds =>
      assertEquals(executor.migrate(ds, indexedUsers), MigrationResult(1, MigrationStatus.Applied, 3))
      assertEquals(scalar(ds, "SELECT count(*) FROM pg_indexes WHERE schemaname = 'public' AND tablename = 'users'"), "2")
      assertEquals(executor.migrate(ds, indexedUsers), MigrationResult(1, MigrationStatus.AlreadyApplied, 0))
    }
  }

  test("an index added to a populated table keeps its rows, and renames keep indexes") {
    withDatabase { ds =>
      fixture(ds)
      assertEquals(executor.migrate(ds, indexedUsers), MigrationResult(2, MigrationStatus.Applied, 3))
      val renamedIndexed = SchemaModel(Vector(indexedUsers.tables.head.copy(name = renamed.name,
        columns = Vector(login.copy(name = SqlIdentifier("login_name")), tenant.copy(name = SqlIdentifier("tenant_id"))))))
      assertEquals(executor.migrate(ds, renamedIndexed), MigrationResult(3, MigrationStatus.Applied, 3))
      assertEquals(scalar(ds, "SELECT login_name FROM public.accounts"), "patrick")
      assertEquals(executor.migrate(ds, renamedIndexed).status, MigrationStatus.AlreadyApplied)
    }
  }

  test("missing, extra, reordered and non-plain indexes block the start") {
    Vector(
      "DROP INDEX public.users_username_idx" -> "index (\"username\") is missing",
      "CREATE INDEX ON public.users (tenant)" -> "unexpected index (\"tenant\")",
      "DROP INDEX public.users_username_idx; CREATE INDEX ON public.users (username DESC)" ->
        "unexpected index (\"username\" DESC)",
      "DROP INDEX public.users_username_idx; CREATE INDEX ON public.users (username) WHERE tenant IS NULL" ->
        "unsupported partial predicate",
      "DROP INDEX public.users_username_idx; CREATE INDEX ON public.users (lower(username))" -> "unsupported expressions",
      "DROP INDEX public.users_username_idx; CREATE INDEX ON public.users USING hash (username)" ->
        "unsupported access method hash",
      "DROP INDEX public.users_username_idx; CREATE INDEX ON public.users (username) INCLUDE (tenant)" ->
        "unsupported INCLUDE columns",
      "DROP INDEX public.users_username_idx; CREATE INDEX ON public.users (username varchar_pattern_ops)" ->
        "unsupported operator class",
      "DROP INDEX public.users_username_idx; CREATE INDEX ON public.users (username COLLATE \"C\")" ->
        "unsupported collation",
      "DROP INDEX public.users_username_idx; CREATE INDEX ON public.users (username NULLS FIRST)" ->
        "unsupported NULLS ordering"
    ).foreach { (change, message) =>
      withDatabase { ds =>
        executor.migrate(ds, indexedUsers)
        execute(ds, change)
        val error = intercept[MigrationException](executor.migrate(ds, indexedUsers))
        assert(error.getMessage.contains(message), s"$change: ${error.getMessage}")
      }
    }
  }

  private val status = ColumnModel(SchemaId("USER_STATUS"), SqlIdentifier("status"), SqlType.Varchar(10),
    check = Some(ColumnCheck.AllowedValues(Vector("NEW", "ACTIVE"))))
  private val level = ColumnModel(SchemaId("USER_LEVEL"), SqlIdentifier("level"), SqlType.SmallInt,
    check = Some(ColumnCheck.Range(0, 2)))
  private val checkedUsers = SchemaModel(Vector(users.copy(columns = users.columns ++ Vector(status, level))))
  private def withChecks(statusCheck: Option[ColumnCheck], levelCheck: Option[ColumnCheck] = level.check) =
    SchemaModel(Vector(users.copy(columns = users.columns ++ Vector(status.copy(check = statusCheck), level.copy(check = levelCheck)))))

  test("column checks are created, enforced and verified on restart") {
    withDatabase { ds =>
      fixture(ds, checkedUsers)
      execute(ds, "UPDATE public.users SET status = 'ACTIVE', level = 2")
      intercept[java.sql.SQLException](execute(ds, "UPDATE public.users SET status = 'GONE'"))
      intercept[java.sql.SQLException](execute(ds, "UPDATE public.users SET level = 3"))
      assertEquals(executor.migrate(ds, checkedUsers), MigrationResult(1, MigrationStatus.AlreadyApplied, 0))
    }
  }

  test("an extended enum widens the check; a narrowed one is refused by existing rows; renames keep checks") {
    withDatabase { ds =>
      fixture(ds, checkedUsers)
      execute(ds, "UPDATE public.users SET status = 'ACTIVE'")
      val extended = withChecks(Some(ColumnCheck.AllowedValues(Vector("NEW", "ACTIVE", "BLOCKED"))))
      assertEquals(executor.migrate(ds, extended), MigrationResult(2, MigrationStatus.Applied, 1))
      execute(ds, "UPDATE public.users SET status = 'BLOCKED'")
      val narrowed = withChecks(Some(ColumnCheck.AllowedValues(Vector("NEW", "ACTIVE"))), Some(ColumnCheck.Range(0, 1)))
      assertEquals(intercept[MigrationException](executor.migrate(ds, narrowed)).state, FailureState.RolledBack)
      assertEquals(revisions(ds), "2")
      val renamedChecked = SchemaModel(Vector(extended.tables.head.copy(columns = extended.tables.head.columns.map(c =>
        if c.id == status.id then c.copy(name = SqlIdentifier("state")) else c))))
      assertEquals(executor.migrate(ds, renamedChecked), MigrationResult(3, MigrationStatus.Applied, 1))
      intercept[java.sql.SQLException](execute(ds, "UPDATE public.users SET state = 'GONE'"))
      val unchecked = SchemaModel(Vector(renamedChecked.tables.head.copy(columns = renamedChecked.tables.head.columns.map(_.copy(check = None)))))
      assertEquals(executor.migrate(ds, unchecked), MigrationResult(4, MigrationStatus.Applied, 2))
      execute(ds, "UPDATE public.users SET state = 'GONE', level = 7")
    }
  }

  test("missing, foreign and NOT VALID check constraints block the start") {
    val statusName = PostgreSqlDialect.checkName(status.id, status.check.get).value
    Vector(
      s"ALTER TABLE public.users DROP CONSTRAINT $statusName" -> "is missing from",
      s"ALTER TABLE public.users DROP CONSTRAINT $statusName, ADD CONSTRAINT $statusName CHECK (level > 0)" ->
        "covers (\"level\"); expected (\"status\")",
      s"ALTER TABLE public.users DROP CONSTRAINT $statusName, " +
        s"ADD CONSTRAINT $statusName CHECK (status IN ('NEW', 'ACTIVE')) NOT VALID" -> "unsupported NOT VALID state"
    ).foreach { (change, message) =>
      withDatabase { ds =>
        fixture(ds, checkedUsers)
        execute(ds, change)
        val error = intercept[MigrationException](executor.migrate(ds, checkedUsers))
        assert(error.getMessage.contains(message), s"$change: ${error.getMessage}")
      }
    }
  }

  test("Hibernate entities with associations, collections, inheritance, enums, keys, indexes and generated keys migrate end to end") {
    import com.anjunar.hibernateddl.hibernate.*
    val model = TestMetadata.read(classOf[Article], classOf[Label], classOf[Invoice], classOf[LegacyCustomer],
      classOf[Account], classOf[Shipment], classOf[Measurement], classOf[Letter], classOf[Purchase],
      classOf[Generated], classOf[Ticket], classOf[Voucher], classOf[Animal], classOf[Cat], classOf[Dog],
      classOf[Vehicle], classOf[Car], classOf[Payment], classOf[CardPayment], classOf[TransferPayment])
      .fold(errors => fail(errors.mkString("\n")), identity)
    withDatabase { ds =>
      val result = executor.migrate(ds, model)
      assertEquals(result.status, MigrationStatus.Applied)
      assert(result.statementCount > model.tables.size, result)
      execute(ds, "INSERT INTO public.article VALUES (1); INSERT INTO public.label VALUES (7); " +
        "INSERT INTO public.article_keyword VALUES (1, 'scala'); INSERT INTO public.article_label VALUES (1, 7); " +
        "INSERT INTO public.article_statuses VALUES (1, 'Sent')")
      intercept[java.sql.SQLException](execute(ds, "INSERT INTO public.article_label VALUES (1, 8)"))
      intercept[java.sql.SQLException](execute(ds, "INSERT INTO public.article_statuses VALUES (1, 'Lost')"))
      assertEquals(scalar(ds, "SELECT nextval('public.voucher_numbers') || ',' || nextval('public.generated_seq')"), "100,1")
      execute(ds, "INSERT INTO public.ticket DEFAULT VALUES")
      assertEquals(scalar(ds, "SELECT id FROM public.ticket"), "1")
      execute(ds, "INSERT INTO public.animal (dtype, id, name, lives) VALUES ('Cat', 1, 'Tom', 9); " +
        "INSERT INTO public.vehicle (id, wheels) VALUES (1, 4); INSERT INTO public.car (id, seats) VALUES (1, 5); " +
        "INSERT INTO public.cardpayment (id, amount, card) VALUES (nextval('public.payment_seq'), 10, 'visa')")
      intercept[java.sql.SQLException](execute(ds, "INSERT INTO public.animal (dtype, id) VALUES ('Horse', 2)"))
      intercept[java.sql.SQLException](execute(ds, "INSERT INTO public.car (id, seats) VALUES (2, 5)"))
      assertEquals(executor.migrate(ds, model), MigrationResult(1, MigrationStatus.AlreadyApplied, 0))
    }
  }

  private val keySequence = SequenceModel(SchemaId("USER_SEQUENCE"), QualifiedName(SqlIdentifier("users_SEQ"), Some(SqlIdentifier("public"))), 1, 50)
  private val sequenced = SchemaModel(Vector(users), Vector(keySequence))

  test("sequences are created, renamed with their current value and verified on restart") {
    withDatabase { ds =>
      assertEquals(executor.migrate(ds, sequenced), MigrationResult(1, MigrationStatus.Applied, 2))
      assertEquals(scalar(ds, "SELECT nextval('public.\"users_SEQ\"') || ',' || nextval('public.\"users_SEQ\"')"), "1,51")
      val renamedSequence = SchemaModel(Vector(users), Vector(keySequence.copy(name = keySequence.name.copy(name = SqlIdentifier("accounts_SEQ")))))
      assertEquals(executor.migrate(ds, renamedSequence), MigrationResult(2, MigrationStatus.Applied, 1))
      assertEquals(scalar(ds, "SELECT nextval('public.\"accounts_SEQ\"')"), "101")
      assertEquals(executor.migrate(ds, renamedSequence).status, MigrationStatus.AlreadyApplied)
    }
  }

  test("a missing, changed, cycling or column-owned sequence blocks the start") {
    Vector(
      "DROP SEQUENCE public.\"users_SEQ\"" -> "sequence \"public\".\"users_SEQ\" does not exist",
      "ALTER SEQUENCE public.\"users_SEQ\" INCREMENT BY 1" -> "has increment 1; expected 50",
      "ALTER SEQUENCE public.\"users_SEQ\" CYCLE" -> "has cycling true; expected false",
      "ALTER SEQUENCE public.\"users_SEQ\" AS integer" -> "has type integer; expected bigint",
      "ALTER SEQUENCE public.\"users_SEQ\" OWNED BY public.users.username" -> "unsupported ownership by a column",
      "DROP SEQUENCE public.\"users_SEQ\"; CREATE TABLE public.\"users_SEQ\" (x int)" -> "is not a sequence"
    ).foreach { (change, message) =>
      withDatabase { ds =>
        executor.migrate(ds, sequenced)
        execute(ds, change)
        val error = intercept[MigrationException](executor.migrate(ds, sequenced))
        assert(error.getMessage.contains(message), s"$change: ${error.getMessage}")
      }
    }
  }

  test("identity columns generate keys, keep explicit values and are verified") {
    val key = ColumnModel(SchemaId("TICKET_ID"), SqlIdentifier("id"), SqlType.BigInt, nullable = false, identity = true)
    val subject = ColumnModel(SchemaId("TICKET_SUBJECT"), SqlIdentifier("subject"), SqlType.Text)
    val tickets = SchemaModel(Vector(TableModel(SchemaId("TICKET"), QualifiedName(SqlIdentifier("ticket"), Some(SqlIdentifier("public"))),
      Vector(key, subject), Vector(key.id))))
    withDatabase { ds =>
      assertEquals(executor.migrate(ds, tickets).status, MigrationStatus.Applied)
      execute(ds, "INSERT INTO public.ticket (subject) VALUES ('first'); INSERT INTO public.ticket VALUES (100, 'explicit')")
      assertEquals(scalar(ds, "SELECT string_agg(id::text, ',' ORDER BY id) FROM public.ticket"), "1,100")
      assertEquals(executor.migrate(ds, tickets).status, MigrationStatus.AlreadyApplied)
      execute(ds, "ALTER TABLE public.ticket ALTER COLUMN id SET GENERATED ALWAYS")
      assert(intercept[MigrationException](executor.migrate(ds, tickets)).getMessage.contains("GENERATED ALWAYS identity"))
      execute(ds, "ALTER TABLE public.ticket ALTER COLUMN id DROP IDENTITY")
      assert(intercept[MigrationException](executor.migrate(ds, tickets)).getMessage.contains("identity=false; expected true"))
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
