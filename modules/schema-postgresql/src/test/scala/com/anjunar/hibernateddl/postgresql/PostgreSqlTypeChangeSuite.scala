package com.anjunar.hibernateddl.postgresql

import com.anjunar.hibernateddl.core.*
import com.anjunar.hibernateddl.executor.*
import java.util.concurrent.{Callable, CountDownLatch, Executors, TimeUnit}
import javax.sql.DataSource

/** The three widenings against real PostgreSQL: values, keys, indexes and checks survive, and
  * everything else fails before or rolls back with the migration.
  */
class PostgreSqlTypeChangeSuite extends TestPostgres:
  private val executor = new JdbcMigrationExecutor(PostgreSqlMigrationBackend)
  private val public = Some(SqlIdentifier("public"))

  private def column(id: String, name: String, dataType: SqlType, nullable: Boolean = true, check: Option[ColumnCheck] = None) =
    ColumnModel(SchemaId(id), SqlIdentifier(name), dataType, nullable, check)

  private val id = column("M_ID", "id", SqlType.BigInt, nullable = false)
  private val name = column("M_NAME", "name", SqlType.Varchar(10))
  private val nick = column("M_NICK", "nick", SqlType.Varchar(10), nullable = false)
  private val visits = column("M_VISITS", "visits", SqlType.Integer)
  private val balance = column("M_BALANCE", "balance", SqlType.Numeric(10, 2), nullable = false)
  private def members(columns: ColumnModel*): TableModel =
    TableModel(SchemaId("M"), QualifiedName(SqlIdentifier("members"), public), id +: columns.toVector, Vector(id.id))
  private val narrow = SchemaModel(Vector(members(name, nick, visits, balance)))
  private val wide = SchemaModel(Vector(members(name.copy(dataType = SqlType.Varchar(40)), nick.copy(dataType = SqlType.Varchar(40)),
    visits.copy(dataType = SqlType.BigInt), balance.copy(dataType = SqlType.Numeric(14, 2)))))

  private def revisions(ds: DataSource): String = scalar(ds, "SELECT count(*) FROM __hibernate_ddl.schema_history")

  /** The column's type as PostgreSQL shows it, with its nullability. */
  private def columnType(ds: DataSource, table: String, column: String): String =
    scalar(ds, s"SELECT format_type(atttypid, atttypmod) || CASE WHEN attnotnull THEN ' not null' ELSE '' END " +
      s"FROM pg_attribute WHERE attrelid = '$table'::regclass AND attname = '$column'")

  test("values survive the three widenings, nullability stays, and larger values fit afterwards") {
    withDatabase { ds =>
      executor.migrate(ds, narrow)
      execute(ds, "INSERT INTO public.members VALUES " +
        "(1, 'Grüße 👋', 'ä', 2147483647, 12345678.91), (2, '', 'x', -2147483648, -0.01), " +
        "(3, NULL, 'y', 0, 0.00), (4, NULL, 'z', NULL, 99999999.99)")
      val before = scalar(ds, "SELECT string_agg(concat_ws('|', id, coalesce(name, 'NULL'), nick, " +
        "coalesce(visits::text, 'NULL'), balance), ';' ORDER BY id) FROM public.members")
      assertEquals(executor.migrate(ds, wide), MigrationResult(2, MigrationStatus.Applied, 4))
      assertEquals(scalar(ds, "SELECT string_agg(concat_ws('|', id, coalesce(name, 'NULL'), nick, " +
        "coalesce(visits::text, 'NULL'), balance), ';' ORDER BY id) FROM public.members"), before)
      assertEquals(before, "1|Grüße 👋|ä|2147483647|12345678.91;2||x|-2147483648|-0.01;3|NULL|y|0|0.00;4|NULL|z|NULL|99999999.99")
      assertEquals(scalar(ds, "SELECT name = '' FROM public.members WHERE id = 2"), "t")
      assertEquals(Vector("name", "nick", "visits", "balance").map(columnType(ds, "public.members", _)),
        Vector("character varying(40)", "character varying(40) not null", "bigint", "numeric(14,2) not null"))
      execute(ds, s"INSERT INTO public.members VALUES (5, '${"é" * 40}', '${"n" * 40}', 3000000000, 999999999999.99)")
      assertEquals(scalar(ds, "SELECT visits + 1 FROM public.members WHERE id = 5"), "3000000001")
      assertEquals(scalar(ds, "SELECT array_to_string(statements, ' | ') FROM __hibernate_ddl.schema_history WHERE revision = 2"),
        Vector("\"balance\" TYPE numeric(14,2)", "\"name\" TYPE varchar(40)", "\"nick\" TYPE varchar(40)", "\"visits\" TYPE bigint")
          .map(change => s"ALTER TABLE \"public\".\"members\" ALTER COLUMN $change;").mkString(" | "))
      assertEquals(executor.migrate(ds, wide), MigrationResult(2, MigrationStatus.AlreadyApplied, 0))
      // The earlier model keeps its fingerprint; only the changed target has a new one.
      assertEquals(scalar(ds, "SELECT string_agg(target_fingerprint, ',' ORDER BY revision) FROM __hibernate_ddl.schema_history"),
        s"${SchemaFingerprint.of(narrow)},${SchemaFingerprint.of(wide)}")
    }
  }

  test("a version jump widens directly to the current type") {
    withDatabase { ds =>
      executor.migrate(ds, narrow)
      execute(ds, "INSERT INTO public.members VALUES (1, 'kept', 'k', 1, 1.50)")
      val latest = SchemaModel(Vector(members(name.copy(dataType = SqlType.Varchar(500)), nick, visits, balance)))
      assertEquals(executor.migrate(ds, latest), MigrationResult(2, MigrationStatus.Applied, 1))
      assertEquals(columnType(ds, "public.members", "name"), "character varying(500)")
      assertEquals(scalar(ds, "SELECT name FROM public.members"), "kept")
    }
  }

  private val code = column("T_CODE", "code", SqlType.Varchar(10))
  private val status = column("T_STATUS", "status", SqlType.Varchar(10),
    check = Some(ColumnCheck.AllowedValues(Vector("Draft", "Sent"))))
  private val level = column("T_LEVEL", "level", SqlType.Integer, check = Some(ColumnCheck.Range(0, 3)))
  private def tickets(columns: ColumnModel*): TableModel =
    TableModel(SchemaId("T"), QualifiedName(SqlIdentifier("tickets"), public), id.copy(id = SchemaId("T_ID")) +: columns.toVector,
      Vector(SchemaId("T_ID")), uniqueKeys = Vector(UniqueKeyModel(Vector(code.id))),
      indexes = Vector(IndexModel(Vector(IndexColumn(code.id), IndexColumn(level.id, descending = true))),
        IndexModel(Vector(IndexColumn(status.id)))))

  test("unique keys, composite and descending indexes and checks stay, and pass the exact comparison") {
    val before = SchemaModel(Vector(tickets(code, status, level)))
    val after = SchemaModel(Vector(tickets(code.copy(dataType = SqlType.Varchar(20)), status.copy(dataType = SqlType.Varchar(20)),
      level.copy(dataType = SqlType.BigInt, check = Some(ColumnCheck.Range(0, 5000000000L))))))
    withDatabase { ds =>
      executor.migrate(ds, before)
      execute(ds, "INSERT INTO public.tickets VALUES (1, 'A-1', 'Draft', 0), (2, 'A-2', 'Sent', 3), (3, NULL, NULL, NULL)")
      assertEquals(executor.migrate(ds, after).status, MigrationStatus.Applied)
      // Before this, the migration compared checks with a probe table and indexes by columns and direction.
      assertEquals(executor.migrate(ds, after).status, MigrationStatus.AlreadyApplied)
      assertEquals(scalar(ds, "SELECT count(*) FROM pg_constraint WHERE conrelid = 'public.tickets'::regclass AND contype = 'c'"), "2")
      assertEquals(scalar(ds, "SELECT bool_and(indisvalid AND indisready) FROM pg_index WHERE indrelid = 'public.tickets'::regclass"), "t")
      assertEquals(scalar(ds, "SELECT string_agg(concat_ws('|', id, code, status, level), ';' ORDER BY id) FROM public.tickets"),
        "1|A-1|Draft|0;2|A-2|Sent|3;3")
      execute(ds, "INSERT INTO public.tickets VALUES (4, 'A-long-code-12', 'Sent', 5000000000)")
      Vector(
        "INSERT INTO public.tickets VALUES (5, 'A-1', NULL, NULL)",
        "INSERT INTO public.tickets VALUES (6, NULL, 'Bogus', NULL)",
        "INSERT INTO public.tickets VALUES (7, NULL, NULL, -1)"
      ).foreach(sql => intercept[java.sql.SQLException](execute(ds, sql)))
    }
  }

  test("renames of table and column with quoted names come first, and the type change uses the new names") {
    val oddName = column("Q_NAME", "Old \"Name\"", SqlType.Varchar(10), check = Some(ColumnCheck.AllowedValues(Vector("a", "b"))))
    def quoted(table: String, columns: ColumnModel*) = SchemaModel(Vector(TableModel(SchemaId("Q"),
      QualifiedName(SqlIdentifier(table), public), id.copy(id = SchemaId("Q_ID")) +: columns.toVector, Vector(SchemaId("Q_ID")))))
    val after = quoted("New \"Case\"", oddName.copy(name = SqlIdentifier("New \"Name\""), dataType = SqlType.Varchar(20)))
    withDatabase { ds =>
      executor.migrate(ds, quoted("Mixed \"Case\"", oddName))
      execute(ds, "INSERT INTO public.\"Mixed \"\"Case\"\"\" VALUES (1, 'b')")
      assertEquals(executor.migrate(ds, after), MigrationResult(2, MigrationStatus.Applied, 5))
      assertEquals(scalar(ds, "SELECT \"New \"\"Name\"\"\" FROM public.\"New \"\"Case\"\"\""), "b")
      assertEquals(columnType(ds, "public.\"New \"\"Case\"\"\"", "New \"Name\""), "character varying(20)")
      assertEquals(executor.migrate(ds, after).status, MigrationStatus.AlreadyApplied)
    }
  }

  test("a view on a retyped column stops the migration before any DDL; nothing is dropped with CASCADE") {
    withDatabase { ds =>
      executor.migrate(ds, narrow)
      execute(ds, "CREATE VIEW public.member_names AS SELECT name FROM public.members")
      execute(ds, "CREATE MATERIALIZED VIEW public.\"Member Stats\" AS SELECT max(name) AS longest FROM public.members")
      execute(ds, "CREATE FUNCTION public.any_name() RETURNS text LANGUAGE sql BEGIN ATOMIC SELECT name FROM public.members LIMIT 1; END")
      val error = intercept[MigrationException](executor.migrate(ds, wide))
      assertEquals(error.state, FailureState.RolledBack)
      assert(error.getMessage.contains("Column 'M_NAME' (public.members.name) cannot change its type while function " +
        "public.any_name, materialized view public.\"Member Stats\", view public.member_names depend on it"), error.getMessage)
      assert(!error.getMessage.contains("M_VISITS"), error.getMessage)
      assertEquals(columnType(ds, "public.members", "name"), "character varying(10)")
      assertEquals(scalar(ds, "SELECT to_regclass('public.member_names') IS NOT NULL"), "t")
      assertEquals(revisions(ds), "1")
      execute(ds, "DROP VIEW public.member_names; DROP MATERIALIZED VIEW public.\"Member Stats\"; DROP FUNCTION public.any_name()")
      assertEquals(executor.migrate(ds, wide).status, MigrationStatus.Applied)
    }
  }

  test("a failure after the type change restores the old type, data, checks and history") {
    val tag = column("T_TAG", "tag", SqlType.Text)
    val before = SchemaModel(Vector(tickets(code, status, level, tag)))
    val after = SchemaModel(Vector(tickets(code, status.copy(dataType = SqlType.Varchar(20)), level, tag)
      .copy(uniqueKeys = Vector(UniqueKeyModel(Vector(code.id)), UniqueKeyModel(Vector(tag.id))))))
    withDatabase { ds =>
      executor.migrate(ds, before)
      execute(ds, "INSERT INTO public.tickets VALUES (1, 'A-1', 'Draft', 1, 'same'), (2, 'A-2', 'Sent', 2, 'same')")
      val error = intercept[MigrationException](executor.migrate(ds, after))
      assertEquals(error.state, FailureState.RolledBack)
      assertEquals(columnType(ds, "public.tickets", "status"), "character varying(10)")
      assertEquals(scalar(ds, "SELECT count(*) FROM pg_constraint WHERE conrelid = 'public.tickets'::regclass AND contype = 'c'"), "2")
      assertEquals(scalar(ds, "SELECT string_agg(status, ',' ORDER BY id) FROM public.tickets"), "Draft,Sent")
      assertEquals(revisions(ds), "1")
      assertEquals(executor.migrate(ds, before).status, MigrationStatus.AlreadyApplied)
    }
  }

  test("key, foreign key and identity columns keep their type, and the refusal changes nothing") {
    val parentId = column("PA_ID", "id", SqlType.Integer, nullable = false)
    val childId = column("CH_ID", "id", SqlType.BigInt, nullable = false)
    val parentRef = column("CH_PARENT", "parent_id", SqlType.Integer)
    val counter = column("CH_COUNTER", "counter", SqlType.Integer, nullable = false).copy(identity = true)
    def model(key: SqlType, count: SqlType) = SchemaModel(Vector(
      TableModel(SchemaId("PA"), QualifiedName(SqlIdentifier("parent"), public), Vector(parentId.copy(dataType = key)),
        Vector(parentId.id)),
      TableModel(SchemaId("CH"), QualifiedName(SqlIdentifier("child"), public),
        Vector(childId, parentRef.copy(dataType = key), counter.copy(dataType = count)), Vector(childId.id),
        foreignKeys = Vector(ForeignKeyModel(Vector(parentRef.id), SchemaId("PA"), Vector(parentId.id))))))
    withDatabase { ds =>
      executor.migrate(ds, model(SqlType.Integer, SqlType.Integer))
      execute(ds, "INSERT INTO public.parent VALUES (1); INSERT INTO public.child (id, parent_id) VALUES (1, 1)")
      val keys = intercept[MigrationException](executor.migrate(ds, model(SqlType.BigInt, SqlType.Integer)))
      assert(keys.getMessage.contains("'PA_ID' from Integer to BigInt is unsupported: the column belongs to a primary key"),
        keys.getMessage)
      assert(keys.getMessage.contains("'CH_PARENT' from Integer to BigInt is unsupported: the column belongs to a foreign key"),
        keys.getMessage)
      val identity = intercept[MigrationException](executor.migrate(ds, model(SqlType.Integer, SqlType.BigInt)))
      assert(identity.getMessage.contains("identity column, whose sequence depends on its type"), identity.getMessage)
      assertEquals(Vector(columnType(ds, "public.parent", "id"), columnType(ds, "public.child", "parent_id"),
        columnType(ds, "public.child", "counter")), Vector("integer not null", "integer", "integer not null"))
      assertEquals(revisions(ds), "1")
    }
  }

  test("two concurrent server starts change the type once") {
    withDatabase { ds =>
      executor.migrate(ds, narrow)
      execute(ds, "INSERT INTO public.members VALUES (1, 'one', 'o', 7, 1.00)")
      val pool = Executors.newFixedThreadPool(2)
      val start = new CountDownLatch(1)
      try
        val runs = Vector.fill(2)(pool.submit(new Callable[MigrationResult]:
          def call(): MigrationResult =
            start.await()
            executor.migrate(ds, wide)
        ))
        start.countDown()
        assertEquals(runs.map(_.get(30, TimeUnit.SECONDS).status).toSet, Set(MigrationStatus.Applied, MigrationStatus.AlreadyApplied))
        assertEquals(revisions(ds), "2")
        assertEquals(scalar(ds, "SELECT visits FROM public.members"), "7")
      finally
        pool.shutdownNow()
        pool.awaitTermination(5, TimeUnit.SECONDS)
    }
  }

  test("widening, filling and requiring a column run in one transaction; a failed fill undoes all of them") {
    val note = column("M_NOTE", "note", SqlType.Varchar(5))
    val before = SchemaModel(Vector(members(name, note)))
    val after = SchemaModel(Vector(members(name, note.copy(dataType = SqlType.Varchar(20), nullable = false))))
    def backfill(value: BackfillValue) = Backfill.fillNulls("note-v1", note.id, BackfillTrigger.BecomesRequired, value)
    withDatabase { ds =>
      executor.migrate(ds, before)
      execute(ds, "INSERT INTO public.members VALUES (1, 'Ada', NULL), (2, NULL, NULL), (3, 'Bob', 'kept')")
      // The source is NULL in row 2, so the fill leaves a NULL and the column cannot become required.
      val failed = intercept[MigrationException](executor.migrate(ds, after, Vector(backfill(BackfillValue.column(name.id)))))
      assertEquals(failed.state, FailureState.RolledBack)
      assert(failed.getMessage.contains("becomes required, but 1 row holds NULL"), failed.getMessage)
      assertEquals(columnType(ds, "public.members", "note"), "character varying(5)")
      assertEquals(scalar(ds, "SELECT count(*) FROM public.members WHERE note IS NULL"), "2")
      assertEquals(scalar(ds, "SELECT count(*) FROM __hibernate_ddl.backfill_history"), "0")
      // Twelve characters fit only after the widening, which therefore runs before the fill.
      val result = executor.migrate(ds, after, Vector(backfill(BackfillValue.literal("twelve chars"))))
      assertEquals(result.status, MigrationStatus.Applied)
      assertEquals(result.backfills, Vector(BackfillOutcome("note-v1", BackfillResult.Executed, Some(2))))
      assertEquals(columnType(ds, "public.members", "note"), "character varying(20) not null")
      assertEquals(scalar(ds, "SELECT string_agg(note, ',' ORDER BY id) FROM public.members"), "twelve chars,twelve chars,kept")
    }
  }

  test("a wider Hibernate mapping reaches the database as type changes and matches what Hibernate itself would create") {
    import com.anjunar.hibernateddl.hibernate.*
    def model(entity: Class[?]) = TestMetadata.read(entity).fold(errors => fail(errors.mkString("\n")), identity)
    val columns = "SELECT string_agg(attname || ' ' || format_type(atttypid, atttypmod) || " +
      "CASE WHEN attnotnull THEN ' not null' ELSE '' END, ', ' ORDER BY attname) " +
      "FROM pg_attribute WHERE attrelid = 'public.membership'::regclass AND attnum > 0 AND NOT attisdropped"
    val hibernate = withDatabase { ds =>
      execute(ds, TestMetadata.createScript(classOf[MembershipAfter]))
      scalar(ds, columns)
    }
    withDatabase { ds =>
      executor.migrate(ds, model(classOf[MembershipBefore]))
      execute(ds, "INSERT INTO public.membership (id, balance, display_name, score, status, tag, visits) " +
        "VALUES (1, 12.50, 'Ada', 3, 'Draft', 't-1', 2147483647)")
      assertEquals(executor.migrate(ds, model(classOf[MembershipAfter])).status, MigrationStatus.Applied)
      assertEquals(scalar(ds, columns), hibernate)
      assertEquals(scalar(ds, "SELECT concat_ws('|', balance, display_name, score, status, tag, visits) FROM public.membership"),
        "12.50|Ada|3|Draft|t-1|2147483647")
      intercept[java.sql.SQLException](execute(ds, "INSERT INTO public.membership (id, balance, status) VALUES (2, 1, 'Bogus')"))
      assertEquals(executor.migrate(ds, model(classOf[MembershipAfter])).status, MigrationStatus.AlreadyApplied)
    }
  }
