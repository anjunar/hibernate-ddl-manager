package com.anjunar.hibernateddl.cli

import com.anjunar.hibernateddl.core.*
import com.anjunar.hibernateddl.executor.*
import com.anjunar.hibernateddl.postgresql.{PostgreSqlMigrationBackend, TestPostgres}
import org.postgresql.ds.PGSimpleDataSource
import java.nio.file.Files
import javax.sql.DataSource

class PreviewCommandSuite extends TestPostgres:
  private val id = ColumnModel(SchemaId("P_ID"), SqlIdentifier("id"), SqlType.BigInt, false)
  private val nick = ColumnModel(SchemaId("P_NICK"), SqlIdentifier("nick"), SqlType.Text)
  private def people(columns: ColumnModel*) = SchemaModel(Vector(TableModel(SchemaId("P"),
    QualifiedName(SqlIdentifier("people"), Some(SqlIdentifier("public"))), id +: columns.toVector, Vector(id.id))))
  private val rule = Backfill.fillNulls("nick-v1", nick.id, BackfillTrigger.BecomesRequired, BackfillValue.literal("secret"))

  private def file(content: String) =
    val path = Files.createTempFile("preview", ".json")
    Files.writeString(path, content)
    path.toString

  private def env(ds: DataSource): Map[String, String] =
    val source = ds.asInstanceOf[PGSimpleDataSource]
    Map("HIBERNATE_DDL_PREVIEW_JDBC_URL" -> source.getUrl, "HIBERNATE_DDL_PREVIEW_USER" -> source.getUser,
      "HIBERNATE_DDL_PREVIEW_PASSWORD" -> Option(source.getPassword).getOrElse(""))

  test("exit codes and output: ready, blocked and incomplete reports; JSON alone on stdout without secrets") {
    withDatabase { ds =>
      new JdbcMigrationExecutor(PostgreSqlMigrationBackend).migrate(ds, people(nick))
      execute(ds, "INSERT INTO public.people VALUES (1, NULL)")
      val required = file(SchemaModelJson.encode(people(nick.copy(nullable = false))))
      val backfills = file(BackfillJson.encode(Vector(rule)))
      val blocked = PreviewCommand.run(Vector("--target", required), env(ds))
      assertEquals((blocked.exitCode, blocked.stderr), (PreviewCommand.Blocked, ""))
      assert(blocked.stdout.startsWith("Result: BLOCKED"), blocked.stdout)
      val ready = PreviewCommand.run(Vector("--target", required, "--backfills", backfills, "--format", "json", "--counts"), env(ds))
      assertEquals(ready.exitCode, PreviewCommand.Ready, ready.stdout)
      assert(ready.stdout.startsWith("{\"format\":1,") && ready.stdout.endsWith("}"), ready.stdout)
      Vector("secret", env(ds)("HIBERNATE_DDL_PREVIEW_PASSWORD"), "HIBERNATE_DDL").filter(_.nonEmpty).foreach { secret =>
        assert(!ready.stdout.contains(secret), secret)
      }
      val schemaOnly = PreviewCommand.run(Vector("--target", required, "--backfills", backfills, "--data-checks", "none"), env(ds))
      assertEquals(schemaOnly.exitCode, PreviewCommand.Incomplete, schemaOnly.stdout)
      val dropped = PreviewCommand.run(Vector("--target", file(SchemaModelJson.encode(people())), "--approval", "drop:P_NICK"), env(ds))
      assertEquals(dropped.exitCode, PreviewCommand.Ready, dropped.stdout)
      assertEquals(scalar(ds, "SELECT count(*) FROM public.people WHERE nick IS NULL"), "1")
    }
  }

  test("invalid calls and inputs fail with code 2 before any database access") {
    val target = file(SchemaModelJson.encode(people(nick)))
    Vector(
      Vector("--target") -> "Unknown or incomplete argument",
      Vector("--backfills", target) -> "--target is required",
      Vector("--target", file("{}")) -> "Target",
      Vector("--target", target, "--backfills", file("""{"format":9,"backfills":[]}""")) -> "Unsupported backfill format",
      Vector("--target", target, "--approval", "delete:x") -> "Invalid approval",
      Vector("--target", target) -> "HIBERNATE_DDL_PREVIEW_JDBC_URL is not set"
    ).foreach { (args, message) =>
      val outcome = PreviewCommand.run(args, Map.empty)
      assertEquals((outcome.exitCode, outcome.stdout), (PreviewCommand.Failure, ""), args.toString)
      assert(outcome.stderr.contains(message), s"$args: ${outcome.stderr}")
    }
    val unreachable = PreviewCommand.run(Vector("--target", target),
      Map("HIBERNATE_DDL_PREVIEW_JDBC_URL" -> "jdbc:postgresql://127.0.0.1:1/none", "HIBERNATE_DDL_PREVIEW_PASSWORD" -> "hidden"))
    assertEquals(unreachable.exitCode, PreviewCommand.Failure)
    assert(!unreachable.stderr.contains("hidden"), unreachable.stderr)
  }
