package com.anjunar.hibernateddl.postgresql

import com.anjunar.hibernateddl.core.*
import com.anjunar.hibernateddl.executor.*
import org.postgresql.ds.PGSimpleDataSource
import java.sql.Connection
import javax.sql.DataSource
import scala.util.Using

class PostgreSqlPreviewSuite extends TestPostgres:
  private val executor = new JdbcMigrationExecutor(PostgreSqlMigrationBackend)
  private val preview = new JdbcMigrationPreview(PostgreSqlMigrationBackend)
  private val public = Some(SqlIdentifier("public"))

  private val id = ColumnModel(SchemaId("P_ID"), SqlIdentifier("id"), SqlType.BigInt, false)
  private val first = ColumnModel(SchemaId("P_FIRST"), SqlIdentifier("first_name"), SqlType.Varchar(100))
  private val last = ColumnModel(SchemaId("P_LAST"), SqlIdentifier("last_name"), SqlType.Varchar(100))
  private val display = ColumnModel(SchemaId("P_DISPLAY"), SqlIdentifier("Display Name"), SqlType.Varchar(255), false)
  private def people(columns: ColumnModel*): TableModel =
    TableModel(SchemaId("P"), QualifiedName(SqlIdentifier("people"), public), id +: columns.toVector, Vector(id.id))
  private val initial = SchemaModel(Vector(people(first, last)))
  private def fill(value: BackfillValue, target: SchemaId = display.id, name: String = "display-v1") =
    Backfill.fillNulls(name, target, BackfillTrigger.BecomesRequired, value)
  private val displayRule = fill(BackfillValue.coalesce(
    BackfillValue.concat(BackfillValue.column(first.id), BackfillValue.literal(" "), BackfillValue.column(last.id)),
    BackfillValue.column(first.id), BackfillValue.literal("Unknown")))

  private def withPeople[A](body: DataSource => A): A =
    withDatabase { ds =>
      executor.migrate(ds, initial)
      execute(ds, "INSERT INTO public.people VALUES (1, 'Ada', 'Lovelace'), (2, 'Grace', NULL), (3, NULL, NULL)")
      body(ds)
    }

  private def check(report: PreviewReport, code: String): PreviewCheck =
    report.checks.find(_.code == code).getOrElse(fail(s"No check $code in ${report.checks}"))

  private def snapshot(ds: DataSource): String =
    scalar(ds, "SELECT concat_ws('|', (SELECT count(*) FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace " +
      "WHERE n.nspname NOT IN ('pg_catalog', 'information_schema', 'pg_toast')), " +
      "(SELECT count(*) FROM pg_namespace), " +
      "(SELECT string_agg(concat_ws(',', id, first_name, last_name), ';' ORDER BY id) FROM public.people), " +
      "(SELECT coalesce(string_agg(revision::text, ','), '') FROM __hibernate_ddl.schema_history))")

  test("a preview of an empty database plans creation and leaves the database without any history") {
    withDatabase { ds =>
      val report = preview.preview(ds, initial)
      assertEquals((report.outcome, report.mode, report.complete), (PreviewOutcome.Ready, Some(PlanMode.Migration), true))
      assertEquals(report.steps.map(_.kind), Vector("ddl"))
      assertEquals(scalar(ds, "SELECT to_regnamespace('__hibernate_ddl') IS NULL AND to_regclass('public.people') IS NULL"), "t")
      assertEquals(executor.migrate(ds, initial).statementCount, 1)
      assertEquals(scalar(ds, "SELECT statements[1] FROM __hibernate_ddl.schema_history"), report.steps.head.sql)
    }
  }

  test("preview and executor plan the same steps; a preview changes neither schema, data nor history") {
    withPeople { ds =>
      val target = SchemaModel(Vector(people(first.copy(name = SqlIdentifier("given_name")), last, display)))
      val before = snapshot(ds)
      val report = preview.preview(ds, target, Vector(displayRule))
      assertEquals(snapshot(ds), before)
      assertEquals(report.outcome, PreviewOutcome.Ready, PreviewRendering.text(report))
      assertEquals(report.steps.map(_.kind), Vector("ddl", "ddl", "fill", "null check", "ddl"))
      assertEquals(report.steps(2).parameterTypes, Vector("text", "text"))
      assert(!PreviewRendering.json(report).contains("Unknown"), "a backfill constant must not appear in the report")
      executor.migrate(ds, target, Vector(displayRule))
      assertEquals(scalar(ds, "SELECT array_to_string(statements, E'\\n') FROM __hibernate_ddl.schema_history WHERE revision = 2"),
        report.steps.filterNot(_.kind == "null check").map(_.sql).mkString("\n"))
    }
  }

  test("missing approvals show with their steps; an unsupported change leaves an incomplete, blocked plan") {
    withPeople { ds =>
      val dropped = preview.preview(ds, SchemaModel(Vector(people(first))))
      assertEquals(dropped.outcome, PreviewOutcome.Blocked)
      assertEquals(dropped.steps.map(step => (step.approval, step.approved)), Vector(Some("drop:P_LAST") -> false))
      assertEquals(dropped.missingApprovals, Vector("drop:P_LAST"))
      assertEquals(dropped.findings.map(f => (f.code, f.step)), Vector(PlanProblem.ApprovalMissing -> Some(1)))
      val retyped = preview.preview(ds, SchemaModel(Vector(people(first.copy(dataType = SqlType.Text), last))))
      assertEquals((retyped.outcome, retyped.complete, retyped.steps), (PreviewOutcome.Blocked, false, Vector.empty))
      assert(retyped.notes.exists(_.contains("acceptManualMigration")), retyped.notes)
    }
  }

  test("drift, a broken history and a changed backfill block even an applied target") {
    withPeople { ds =>
      assertEquals(preview.preview(ds, initial).outcome, PreviewOutcome.Ready)
      execute(ds, "ALTER TABLE public.people ADD COLUMN extra text")
      val drift = preview.preview(ds, initial)
      assertEquals((drift.mode, check(drift, PreviewCheck.SchemaMatches).status), (Some(PlanMode.NoChange), CheckStatus.Failed))
      execute(ds, "ALTER TABLE public.people DROP COLUMN extra")
      val required = SchemaModel(Vector(people(first, last, display)))
      executor.migrate(ds, required, Vector(displayRule))
      val changed = preview.preview(ds, required, Vector(fill(BackfillValue.literal("x"))))
      assertEquals(changed.findings.map(_.code), Vector(PlanProblem.BackfillChanged))
      execute(ds, "UPDATE __hibernate_ddl.schema_history SET model = jsonb_set(model, '{tables,0,name}', '\"persons\"')")
      val broken = preview.preview(ds, required)
      assertEquals((broken.outcome, check(broken, PreviewCheck.HistoryConsistent).status), (PreviewOutcome.Blocked, CheckStatus.Failed))
    }
  }

  test("checks compare structurally: correct ones pass, changed ones fail, unknown forms stay undecided") {
    val level = ColumnModel(SchemaId("P_LEVEL"), SqlIdentifier("level"), SqlType.SmallInt, check = Some(ColumnCheck.Range(0, 2)))
    val model = SchemaModel(Vector(people(first, last, level)))
    val name = PostgreSqlDialect.checkName(level.id, level.check.get).value
    withDatabase { ds =>
      executor.migrate(ds, model)
      assertEquals(check(preview.preview(ds, model), PreviewCheck.SchemaMatches).status, CheckStatus.Passed)
      execute(ds, s"ALTER TABLE public.people DROP CONSTRAINT $name, ADD CONSTRAINT $name CHECK (level BETWEEN 0 AND 5)")
      assertEquals(check(preview.preview(ds, model), PreviewCheck.SchemaMatches).status, CheckStatus.Failed)
      execute(ds, s"ALTER TABLE public.people DROP CONSTRAINT $name, ADD CONSTRAINT $name CHECK (level IN (0, 1, 2))")
      val undecided = preview.preview(ds, model)
      assertEquals((undecided.outcome, check(undecided, PreviewCheck.SchemaMatches).status),
        (PreviewOutcome.Incomplete, CheckStatus.Inconclusive))
    }
  }

  test("adoption and manual migration are previewed by the executor's rules") {
    withDatabase { ds =>
      execute(ds, "CREATE TABLE public.people (id bigint PRIMARY KEY, first_name varchar(100), last_name varchar(100))")
      val refused = preview.preview(ds, initial)
      assertEquals((refused.mode, refused.findings.map(_.code)), (Some(PlanMode.Adoption), Vector(PlanProblem.AdoptionNotEnabled)))
      val adopting = new JdbcMigrationPreview(PostgreSqlMigrationBackend, ExecutionOptions(adoptExistingSchema = true))
      assertEquals(adopting.preview(ds, initial).outcome, PreviewOutcome.Ready)
    }
    withPeople { ds =>
      val renamed = SchemaModel(Vector(people(first, last).copy(name = QualifiedName(SqlIdentifier("persons"), public))))
      val manual = new JdbcMigrationPreview(PostgreSqlMigrationBackend,
        ExecutionOptions(acceptManualMigration = Some(SchemaFingerprint.of(renamed))))
      val report = manual.preview(ds, renamed)
      assertEquals((report.mode, check(report, PreviewCheck.RelationsAbsent).status), (Some(PlanMode.ManualMigration), CheckStatus.Failed))
      execute(ds, "ALTER TABLE public.people RENAME TO persons")
      assertEquals(manual.preview(ds, renamed).outcome, PreviewOutcome.Ready)
    }
  }

  test("a column that becomes required needs a backfill for its NULLs; the projection keeps values and sees NULL sources") {
    withPeople { ds =>
      val target = SchemaModel(Vector(people(first, last, display)))
      val without = preview.preview(ds, target)
      assertEquals((without.outcome, check(without, PreviewCheck.NoNulls).status), (PreviewOutcome.Blocked, CheckStatus.Failed))
      val partial = preview.preview(ds, target, Vector(fill(BackfillValue.concat(BackfillValue.column(first.id),
        BackfillValue.column(last.id)))))
      assertEquals(check(partial, PreviewCheck.NoNulls).status, CheckStatus.Failed)
      val counted = preview.preview(ds, target, Vector(displayRule), PreviewOptions(includeExactCounts = true))
      assertEquals((counted.outcome, check(counted, PreviewCheck.NoNulls).rows), (PreviewOutcome.Ready, RowCount.Exact(0)))
      val tightened = preview.preview(ds, SchemaModel(Vector(people(first.copy(nullable = false), last))),
        previewOptions = PreviewOptions(includeExactCounts = true))
      assertEquals(check(tightened, PreviewCheck.NoNulls).rows, RowCount.Exact(1))
      assertEquals(check(preview.preview(ds, target, Vector(displayRule)), PreviewCheck.NoNulls).rows, RowCount.Unknown)
    }
    withDatabase { ds =>
      executor.migrate(ds, initial)
      assertEquals(preview.preview(ds, SchemaModel(Vector(people(first, last, display)))).outcome, PreviewOutcome.Ready)
    }
  }

  test("a fill that creates duplicates, missing references or check violations blocks; NULL keys never do") {
    withPeople { ds =>
      val unique = people(first, last, display).copy(uniqueKeys = Vector(UniqueKeyModel(Vector(display.id))))
      val same = preview.preview(ds, SchemaModel(Vector(unique)), Vector(fill(BackfillValue.literal("same"))))
      assertEquals(check(same, PreviewCheck.NoDuplicates).status, CheckStatus.Failed)
      assertEquals(check(preview.preview(ds, SchemaModel(Vector(unique)), Vector(displayRule)), PreviewCheck.NoDuplicates).status,
        CheckStatus.Passed)
      val pair = people(first, last).copy(uniqueKeys = Vector(UniqueKeyModel(Vector(first.id, last.id))))
      execute(ds, "INSERT INTO public.people VALUES (4, 'Grace', NULL)")
      assertEquals(check(preview.preview(ds, SchemaModel(Vector(pair))), PreviewCheck.NoDuplicates).status, CheckStatus.Passed)
      execute(ds, "INSERT INTO public.people VALUES (5, 'Ada', 'Lovelace')")
      assertEquals(check(preview.preview(ds, SchemaModel(Vector(pair)), previewOptions = PreviewOptions(includeExactCounts = true)),
        PreviewCheck.NoDuplicates).rows, RowCount.Exact(2))
      val teamId = ColumnModel(SchemaId("T_ID"), SqlIdentifier("id"), SqlType.BigInt, false)
      val teams = TableModel(SchemaId("T"), QualifiedName(SqlIdentifier("teams"), public), Vector(teamId), Vector(teamId.id))
      val team = ColumnModel(SchemaId("P_TEAM"), SqlIdentifier("team_id"), SqlType.BigInt, false)
      val member = people(first, last, team).copy(foreignKeys = Vector(ForeignKeyModel(Vector(team.id), teams.id, teams.primaryKey)))
      val dangling = preview.preview(ds, SchemaModel(Vector(member, teams)), Vector(fill(BackfillValue.literal(7L), team.id, "team-v1")))
      assertEquals(check(dangling, PreviewCheck.ReferencesResolve).status, CheckStatus.Failed)
      val checked = people(first, last, display.copy(check = Some(ColumnCheck.AllowedValues(Vector("Ada Lovelace", "Grace")))))
      val violated = preview.preview(ds, SchemaModel(Vector(checked)), Vector(displayRule))
      assertEquals(check(violated, PreviewCheck.CheckHolds).status, CheckStatus.Failed)
    }
  }

  test("sources dropped after the fill and renamed ones are read under their current names") {
    withPeople { ds =>
      val v3 = SchemaModel(Vector(people(display)))
      val approved = new JdbcMigrationPreview(PostgreSqlMigrationBackend,
        ExecutionOptions(approvals = Set(Approval.Drop(first.id), Approval.Drop(last.id))))
      assertEquals(approved.preview(ds, v3, Vector(displayRule)).outcome, PreviewOutcome.Ready)
      val renamed = SchemaModel(Vector(people(first.copy(name = SqlIdentifier("given_name")), last, display)))
      assertEquals(preview.preview(ds, renamed, Vector(displayRule)).outcome, PreviewOutcome.Ready)
      assertEquals(scalar(ds, "SELECT count(*) FROM pg_attribute WHERE attrelid = 'public.people'::regclass AND attnum > 0"), "3")
    }
  }

  test("a read-only role suffices; missing privileges and lock timeouts leave the preview incomplete") {
    val role = "previewer_" + java.util.UUID.randomUUID().toString.replace("-", "").take(12)
    try
      withPeople { ds =>
        execute(ds, s"CREATE ROLE $role LOGIN PASSWORD 'preview'; GRANT USAGE ON SCHEMA __hibernate_ddl TO $role; " +
          s"GRANT SELECT ON __hibernate_ddl.schema_history, __hibernate_ddl.backfill_history TO $role")
        val reader = new PGSimpleDataSource()
        reader.setUrl(ds.asInstanceOf[PGSimpleDataSource].getUrl)
        reader.setUser(role)
        reader.setPassword("preview")
        val target = SchemaModel(Vector(people(first, last, display)))
        val denied = preview.preview(reader, target, Vector(displayRule))
        assertEquals((denied.outcome, check(denied, PreviewCheck.NoNulls).status), (PreviewOutcome.Incomplete, CheckStatus.Inconclusive))
        execute(ds, s"GRANT SELECT ON public.people TO $role")
        assertEquals(preview.preview(reader, target, Vector(displayRule)).outcome, PreviewOutcome.Ready)
        Using.resource(ds.getConnection) { holder =>
          holder.setAutoCommit(false)
          Using.resource(holder.createStatement())(_.execute("LOCK TABLE public.people IN ACCESS EXCLUSIVE MODE"))
          val impatient = new JdbcMigrationPreview(PostgreSqlMigrationBackend, ExecutionOptions(lockTimeoutMillis = 200))
          val twoChecks = SchemaModel(Vector(people(first, last, display).copy(uniqueKeys = Vector(UniqueKeyModel(Vector(display.id))))))
          val blocked = impatient.preview(ds, twoChecks, Vector(displayRule))
          holder.rollback()
          assertEquals(blocked.outcome, PreviewOutcome.Incomplete)
          assertEquals(blocked.checks.map(_.status).filterNot(_ == CheckStatus.Passed).distinct,
            Vector(CheckStatus.Inconclusive, CheckStatus.NotRun))
        }
      }
    finally execute(maintenance, s"DROP ROLE IF EXISTS $role")
  }

  test("the connection comes back as it arrived, and a database changed after a ready preview still stops the migration") {
    withPeople { ds =>
      val target = SchemaModel(Vector(people(first.copy(nullable = false), last)))
      execute(ds, "UPDATE public.people SET first_name = 'Anon' WHERE first_name IS NULL")
      var used: Connection = null
      val tracking = new PGSimpleDataSource():
        override def getConnection(): Connection =
          used = ds.getConnection()
          used.setTransactionIsolation(Connection.TRANSACTION_SERIALIZABLE)
          val proxy = java.lang.reflect.Proxy.newProxyInstance(classOf[Connection].getClassLoader, Array(classOf[Connection]),
            (_, method, args) =>
              if method.getName == "close" then null
              else method.invoke(used, Option(args).getOrElse(Array.empty[AnyRef])*))
          proxy.asInstanceOf[Connection]
      assertEquals(preview.preview(tracking, target).outcome, PreviewOutcome.Ready)
      assertEquals((used.getAutoCommit, used.getTransactionIsolation, used.isReadOnly),
        (true, Connection.TRANSACTION_SERIALIZABLE, false))
      assertEquals(Using.resource(used.createStatement())(s => Using.resource(s.executeQuery("SHOW transaction_read_only")) { r =>
        r.next(); r.getString(1) }), "off")
      used.close()
      execute(ds, "INSERT INTO public.people VALUES (9, NULL, NULL)")
      assert(intercept[MigrationException](executor.migrate(ds, target)).getMessage.contains("becomes required"))
    }
  }

  test("text and JSON show the same findings and checks; JSON is versioned") {
    withPeople { ds =>
      val report = preview.preview(ds, SchemaModel(Vector(people(first))))
      val json = PreviewRendering.json(report)
      val text = PreviewRendering.text(report)
      assert(json.startsWith("{\"format\":1,"), json)
      assert(json.contains("\"outcome\":\"Blocked\""), json)
      (report.findings.map(_.code) ++ report.checks.map(_.code)).foreach { code =>
        assert(json.contains(s"\"$code\"") && text.contains(code), code)
      }
      assert(text.startsWith("Result: BLOCKED"), text)
    }
  }
