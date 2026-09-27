package com.anjunar.hibernateddl.postgresql

import com.anjunar.hibernateddl.core.*
import com.anjunar.hibernateddl.executor.*
import com.anjunar.hibernateddl.hibernate.{TestMetadata, TutorialPost}

import java.sql.SQLException
import javax.sql.DataSource

class NamedTableCheckSuite extends TestPostgres:
  private val publication = TableCheck(SqlIdentifier("ck_blog_post_publication"),
    "(status = 'DRAFT' AND published_at IS NULL) OR (status = 'PUBLISHED' AND published_at IS NOT NULL)")
  private val status = TableCheck(SqlIdentifier("ck_blog_post_status"), "status IN ('DRAFT', 'PUBLISHED')")
  private val table = TableModel(SchemaId("post"),
    QualifiedName(SqlIdentifier("blog_post"), Some(SqlIdentifier("public"))),
    Vector(
      ColumnModel(SchemaId("id"), SqlIdentifier("id"), SqlType.Uuid, false),
      ColumnModel(SchemaId("version"), SqlIdentifier("version"), SqlType.BigInt, false),
      ColumnModel(SchemaId("slug"), SqlIdentifier("slug"), SqlType.Varchar(220), false),
      ColumnModel(SchemaId("title"), SqlIdentifier("title"), SqlType.Varchar(180), false),
      ColumnModel(SchemaId("content"), SqlIdentifier("content"), SqlType.Text, false),
      ColumnModel(SchemaId("status"), SqlIdentifier("status"), SqlType.Varchar(24), false),
      ColumnModel(SchemaId("published"), SqlIdentifier("published_at"), SqlType.TimestampWithTimeZone(6))
    ), primaryKey = Vector(SchemaId("id")),
    uniqueKeys = Vector(UniqueKeyModel(Vector(SchemaId("slug")))),
    checks = Vector(status, publication))
  private val model = SchemaModel(Vector(table))
  private val executor = JdbcMigrationExecutor(PostgreSqlMigrationBackend)
  private val adopt = JdbcMigrationExecutor(PostgreSqlMigrationBackend, ExecutionOptions(adoptExistingSchema = true))
  private val legacy = """CREATE TABLE public.blog_post (
    id uuid PRIMARY KEY, version bigint NOT NULL, slug varchar(220) NOT NULL,
    title varchar(180) NOT NULL, content text NOT NULL, status varchar(24) NOT NULL,
    published_at timestamp(6) with time zone,
    CONSTRAINT uq_blog_post_slug UNIQUE (slug),
    CONSTRAINT ck_blog_post_status CHECK (status IN ('DRAFT', 'PUBLISHED')),
    CONSTRAINT ck_blog_post_publication CHECK (
      (status = 'DRAFT' AND published_at IS NULL)
      OR (status = 'PUBLISHED' AND published_at IS NOT NULL))
  )"""
  private val draft = """INSERT INTO public.blog_post VALUES
    ('93c3d002-a2ba-47db-b2b2-f9d5fd5fcbec', 0, 'first-post', 'Keep this title', 'Keep this body', 'DRAFT', NULL)"""
  private val definitions = """SELECT string_agg(conname || ':' || pg_get_constraintdef(oid), ',' ORDER BY conname)
    FROM pg_constraint WHERE conrelid = 'public.blog_post'::regclass AND contype = 'c'"""

  test("new tables enforce both named checks and repeated startup records no new revision") {
    withDatabase { ds =>
      assertEquals(executor.migrate(ds, model).status, MigrationStatus.Applied)
      execute(ds, draft)
      val failure = intercept[SQLException](execute(ds, "UPDATE public.blog_post SET status = 'PUBLISHED'"))
      assertEquals(failure.getSQLState, "23514")
      execute(ds, "UPDATE public.blog_post SET status = 'PUBLISHED', published_at = CURRENT_TIMESTAMP")
      assertEquals(executor.migrate(ds, model).status, MigrationStatus.AlreadyApplied)
      assertEquals(scalar(ds, "SELECT count(*) FROM __hibernate_ddl.schema_history"), "1")
    }
  }

  test("adopt the chapter 5 table and evolve it without changing its rows or checks") {
    withDatabase { ds =>
      execute(ds, legacy)
      execute(ds, draft)
      val before = scalar(ds, definitions)
      val result = adopt.migrate(ds, model)
      assertEquals(result, MigrationResult(1, MigrationStatus.Adopted, 0))
      val expanded = table.copy(
        columns = table.columns.map(c => if c.id == SchemaId("title") then c.copy(dataType = SqlType.Varchar(240)) else c) :+
          ColumnModel(SchemaId("summary"), SqlIdentifier("summary"), SqlType.Varchar(300)))
      assertEquals(executor.migrate(ds, SchemaModel(Vector(expanded))).status, MigrationStatus.Applied)
      assertEquals(scalar(ds, "SELECT title || ':' || content FROM public.blog_post WHERE summary IS NULL"),
        "Keep this title:Keep this body")
      assertEquals(scalar(ds, definitions), before)
      assertEquals(intercept[SQLException](execute(ds, "UPDATE public.blog_post SET status = 'PUBLISHED'")).getSQLState, "23514")
    }
  }

  test("a same-name weakened predicate is drift and adoption rolls back") {
    withDatabase { ds =>
      execute(ds, legacy)
      execute(ds, "ALTER TABLE public.blog_post DROP CONSTRAINT ck_blog_post_publication")
      execute(ds, "ALTER TABLE public.blog_post ADD CONSTRAINT ck_blog_post_publication CHECK (true)")
      val error = intercept[MigrationException](adopt.migrate(ds, model))
      assertEquals(error.state, FailureState.RolledBack)
      assert(error.getMessage.contains("ck_blog_post_publication"), error.getMessage)
      assertEquals(scalar(ds, "SELECT to_regnamespace('__hibernate_ddl') IS NULL"), "t")
    }
  }

  test("missing and unvalidated checks are refused") {
    withDatabase { ds =>
      execute(ds, legacy)
      execute(ds, "ALTER TABLE public.blog_post DROP CONSTRAINT ck_blog_post_publication")
      assert(intercept[MigrationException](adopt.migrate(ds, model)).getMessage.contains("missing"))
      execute(ds, "ALTER TABLE public.blog_post ADD CONSTRAINT ck_blog_post_publication CHECK (" + publication.expression + ") NOT VALID")
      assert(intercept[MigrationException](adopt.migrate(ds, model)).getMessage.contains("NOT VALID"))
    }
  }

  test("a read-only preview reports unverifiable predicates and creates no history") {
    withDatabase { ds =>
      execute(ds, legacy)
      val report = preview(ds)
      assertEquals(report.outcome, PreviewOutcome.Incomplete)
      assert(PreviewRendering.text(report).contains("read-only preview cannot verify"))
      assertEquals(scalar(ds, "SELECT to_regnamespace('__hibernate_ddl') IS NULL"), "t")
    }
  }

  private def preview(ds: DataSource): PreviewReport =
    JdbcMigrationPreview(PostgreSqlMigrationBackend, ExecutionOptions(adoptExistingSchema = true)).preview(ds, model)

  test("changing a recorded check fails before DDL and leaves the original rule active") {
    withDatabase { ds =>
      executor.migrate(ds, model)
      execute(ds, draft)
      val changed = model.copy(tables = Vector(table.copy(checks = Vector(status))))
      assert(intercept[MigrationException](executor.migrate(ds, changed)).getMessage.contains("manual migration"))
      assertEquals(intercept[SQLException](execute(ds, "UPDATE public.blog_post SET status = 'PUBLISHED'")).getSQLState, "23514")
      assertEquals(scalar(ds, "SELECT count(*) FROM __hibernate_ddl.schema_history"), "1")
    }
  }

  test("the chapter 5 Hibernate mapping adopts the original table after the enum check is renamed") {
    val discovered = TestMetadata.read(classOf[TutorialPost]).fold(errors => fail(errors.mkString("; ")), identity)
    val post = discovered.tables.head
    val stateColumn = post.columns.find(_.name.value == "status").get
    val enumName = PostgreSqlDialect.checkName(stateColumn.id, stateColumn.check.get).value
    withDatabase { ds =>
      execute(ds, legacy)
      execute(ds, draft)
      execute(ds, s"""ALTER TABLE public.blog_post RENAME CONSTRAINT ck_blog_post_status TO "$enumName" """)
      val before = scalar(ds, definitions)
      assertEquals(adopt.migrate(ds, discovered), MigrationResult(1, MigrationStatus.Adopted, 0))
      val next = post.copy(columns = post.columns :+
        ColumnModel(SchemaId("d4f39c20/0ca6e520"), SqlIdentifier("summary"), SqlType.Varchar(300)))
      assertEquals(executor.migrate(ds, SchemaModel(Vector(next))).status, MigrationStatus.Applied)
      assertEquals(scalar(ds, "SELECT title || ':' || content FROM public.blog_post WHERE summary IS NULL"),
        "Keep this title:Keep this body")
      assertEquals(scalar(ds, definitions), before)
      assertEquals(intercept[SQLException](execute(ds, "UPDATE public.blog_post SET status = 'PUBLISHED'")).getSQLState, "23514")
    }
  }
