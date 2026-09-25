package com.anjunar.hibernateddl.cli

import com.anjunar.hibernateddl.core.*
import com.anjunar.hibernateddl.executor.*
import com.anjunar.hibernateddl.postgresql.PostgreSqlMigrationBackend
import org.postgresql.ds.PGSimpleDataSource
import java.nio.file.{Files, Path}
import scala.util.control.NonFatal

/** `preview`: what the next server start would do to a database, read-only. The target and
  * backfills come from files that `HibernateSchemaMigration.exportTarget` and
  * `exportBackfills` wrote; the connection from environment variables, which are never shown.
  * JSON goes to standard output alone, diagnostics to standard error.
  */
object PreviewCommand:
  final case class Outcome(exitCode: Int, stdout: String, stderr: String)

  val Ready = 0
  val Blocked = 1
  val Failure = 2
  val Incomplete = 3

  val usage: String =
    """preview --target FILE [--backfills FILE] [--format text|json] [--data-checks none|existence] [--counts]
      |        [--approval drop:ID|rename-back:ID|revert:REVISION]... [--adopt-existing-schema]
      |        [--accept-manual-migration FINGERPRINT] [--lock-timeout-millis N] [--statement-timeout-millis N]
      |        Shows what the next server start would do to the database, without changing it.
      |        Connection: HIBERNATE_DDL_PREVIEW_JDBC_URL, HIBERNATE_DDL_PREVIEW_USER, HIBERNATE_DDL_PREVIEW_PASSWORD.
      |        Exit codes: 0 ready, 1 blocked, 2 invalid call or technical error, 3 incomplete.""".stripMargin

  private final case class Arguments(
      target: Option[Path] = None,
      backfills: Option[Path] = None,
      json: Boolean = false,
      preview: PreviewOptions = PreviewOptions(),
      options: ExecutionOptions = ExecutionOptions()
  )

  def run(args: Vector[String], env: Map[String, String]): Outcome =
    try preview(args, env).fold(error => Outcome(Failure, "", s"$error\n$usage"), identity)
    catch case NonFatal(error) => Outcome(Failure, "", s"Preview failed: ${Option(error.getMessage).getOrElse(error.toString)}")

  private def preview(args: Vector[String], env: Map[String, String]): Either[String, Outcome] =
    for
      arguments <- parse(args, Arguments())
      path <- arguments.target.toRight("--target is required")
      target <- read(path).flatMap(SchemaModelJson.decode).left.map(error => s"Target $path: $error")
      backfills <- arguments.backfills.fold(Right(Vector.empty))(path =>
        read(path).flatMap(BackfillJson.decode).left.map(error => s"Backfills $path: $error"))
      url <- env.get("HIBERNATE_DDL_PREVIEW_JDBC_URL").filter(_.nonEmpty).toRight("HIBERNATE_DDL_PREVIEW_JDBC_URL is not set")
    yield
      val dataSource = new PGSimpleDataSource()
      dataSource.setURL(url)
      env.get("HIBERNATE_DDL_PREVIEW_USER").foreach(dataSource.setUser)
      env.get("HIBERNATE_DDL_PREVIEW_PASSWORD").foreach(dataSource.setPassword)
      val report = JdbcMigrationPreview(PostgreSqlMigrationBackend, arguments.options)
        .preview(dataSource, target, backfills, arguments.preview)
      val code = report.outcome match
        case PreviewOutcome.Ready => Ready
        case PreviewOutcome.Blocked => Blocked
        case PreviewOutcome.Incomplete => Incomplete
      Outcome(code, if arguments.json then PreviewRendering.json(report) else PreviewRendering.text(report), "")

  private def read(path: Path): Either[String, String] =
    try Right(Files.readString(path)) catch case NonFatal(error) => Left(s"cannot be read (${error.getMessage})")

  private def parse(args: Vector[String], parsed: Arguments): Either[String, Arguments] = args match
    case Vector() => Right(parsed)
    case "--target" +: path +: rest => parse(rest, parsed.copy(target = Some(Path.of(path))))
    case "--backfills" +: path +: rest => parse(rest, parsed.copy(backfills = Some(Path.of(path))))
    case "--format" +: "text" +: rest => parse(rest, parsed.copy(json = false))
    case "--format" +: "json" +: rest => parse(rest, parsed.copy(json = true))
    case "--data-checks" +: "none" +: rest => parse(rest, parsed.copy(preview = parsed.preview.copy(dataChecks = DataChecks.Skip)))
    case "--data-checks" +: "existence" +: rest =>
      parse(rest, parsed.copy(preview = parsed.preview.copy(dataChecks = DataChecks.Existence)))
    case "--counts" +: rest => parse(rest, parsed.copy(preview = parsed.preview.copy(includeExactCounts = true)))
    case "--adopt-existing-schema" +: rest => parse(rest, parsed.copy(options = parsed.options.copy(adoptExistingSchema = true)))
    case "--accept-manual-migration" +: fingerprint +: rest =>
      parse(rest, parsed.copy(options = parsed.options.copy(acceptManualMigration = Some(fingerprint))))
    case "--approval" +: approval +: rest =>
      val parsedApproval = approval.split(":", 2) match
        case Array("drop", id) if id.nonEmpty => Right(Approval.Drop(SchemaId(id)))
        case Array("rename-back", id) if id.nonEmpty => Right(Approval.RenameBack(SchemaId(id)))
        case Array("revert", revision) if revision.toLongOption.exists(_ > 0) => Right(Approval.Revert(revision.toLong))
        case _ => Left(s"Invalid approval '$approval'")
      parsedApproval.flatMap(value => parse(rest, parsed.copy(options = parsed.options.copy(approvals = parsed.options.approvals + value))))
    case "--lock-timeout-millis" +: value +: rest if value.toIntOption.nonEmpty =>
      parse(rest, parsed.copy(options = parsed.options.copy(lockTimeoutMillis = value.toInt)))
    case "--statement-timeout-millis" +: value +: rest if value.toIntOption.nonEmpty =>
      parse(rest, parsed.copy(options = parsed.options.copy(statementTimeoutMillis = value.toInt)))
    case _ => Left(s"Unknown or incomplete argument: ${args.head}")
