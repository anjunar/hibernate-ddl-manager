package com.anjunar.hibernateddl.executor

import com.anjunar.hibernateddl.core.*
import java.sql.Connection
import javax.sql.DataSource
import scala.util.control.NonFatal

/** Shows what the next server start would do, against the database as it is, without changing
  * its schema, data or history. It uses the planner a start uses, inside one transaction that is
  * read-only and REPEATABLE READ on the server, takes no migration lock and no explicit lock,
  * creates nothing and runs no DDL or data change. A preview only describes the state it read;
  * the migration checks everything again under its locks.
  *
  * Invalid inputs and an unusable connection throw; everything found in the database ends up in
  * the report. Once a query fails, the transaction ends and the remaining checks are not run.
  */
final class JdbcMigrationPreview(
    backend: PreviewBackend,
    options: ExecutionOptions = ExecutionOptions()
):
  def preview(
      dataSource: DataSource,
      target: SchemaModel,
      backfills: Vector[Backfill] = Vector.empty,
      previewOptions: PreviewOptions = PreviewOptions()
  ): PreviewReport =
    val targetFingerprint = MigrationPlanner.prepare(backend, options, target, backfills).fold(
      messages => throw new MigrationException(messages.mkString("; "), FailureState.NotStarted), identity)
    val connection = dataSource.getConnection()
    var outcome: Either[Throwable, PreviewReport] = null
    try
      if !connection.getAutoCommit then
        throw new MigrationException("A preview requires a fresh connection with auto-commit enabled", FailureState.NotStarted)
      connection.setAutoCommit(false)
      try outcome = Right(run(connection, target, targetFingerprint, backfills, previewOptions))
      finally
        // Nothing to keep: a read-only transaction ends by rolling back.
        try connection.rollback()
        catch case NonFatal(error) => abort(connection, error)
        try connection.setAutoCommit(true)
        catch case NonFatal(error) => abort(connection, error)
      outcome.toOption.get
    finally
      try connection.close()
      catch case NonFatal(_) => ()

  private def abort(connection: Connection, failure: Throwable): Unit =
    try connection.abort((command: Runnable) => command.run())
    catch case NonFatal(abortError) => failure.addSuppressed(abortError)

  /** Checks run in order; after a failing query the transaction is broken, so the rest are NotRun. */
  private final class Checks(connection: Connection):
    private val results = Vector.newBuilder[PreviewCheck]
    private var broken: Option[String] = None

    def add(check: PreviewCheck): Unit = results += check

    /** Runs a database check, or records it as NotRun once the transaction broke. */
    def run(code: String, description: String, required: Boolean, step: Option[Int] = None, subject: Option[String] = None)(
        body: => PreviewCheck
    ): Unit =
      broken match
        case Some(reason) => results += PreviewCheck(code, description, CheckStatus.NotRun, required, step, subject,
          Vector(s"Not run: $reason"))
        case None =>
          try results += body
          catch case NonFatal(error) =>
            val reason = Option(error.getMessage).getOrElse(error.getClass.getSimpleName)
            broken = Some(s"an earlier check failed ($reason)")
            results += PreviewCheck(code, description, CheckStatus.Inconclusive, required, step, subject,
              Vector(s"The check could not run: $reason"))

    def isBroken: Boolean = broken.nonEmpty
    def result: Vector[PreviewCheck] = results.result()

  private def run(
      connection: Connection,
      target: SchemaModel,
      targetFingerprint: String,
      backfills: Vector[Backfill],
      previewOptions: PreviewOptions
  ): PreviewReport =
    backend.beginReadOnly(connection, options)
    val checks = new Checks(connection)
    val empty = PreviewReport(PreviewReport.Format, java.time.Instant.now(), None, 0, None, targetFingerprint, false,
      Vector.empty, options.allowedRisks.toVector.map(_.toString).sorted, Vector.empty,
      options.approvals.toVector.map(PreviewReport.approval).sorted, Vector.empty, Vector.empty, Vector.empty, Vector.empty,
      Vector.empty)
    var entries = Option.empty[Vector[HistoryEntry]]
    var records = Vector.empty[BackfillRecord]
    checks.run(PreviewCheck.HistoryReadable, "The schema and backfill histories can be read", required = true) {
      entries = backend.readHistoryIfPresent(connection)
      records = backend.readBackfillsIfPresent(connection)
      PreviewCheck(PreviewCheck.HistoryReadable, "The schema and backfill histories can be read", CheckStatus.Passed, true,
        details = Vector(if entries.isEmpty then "No schema history exists yet." else s"${entries.get.size} revisions read."))
    }
    if checks.isBroken then empty.copy(checks = checks.result)
    else MigrationPlanner.verifyHistory(entries.getOrElse(Vector.empty)) match
      case Left(problem) =>
        checks.add(PreviewCheck(PreviewCheck.HistoryConsistent, "The schema history forms one unbroken chain",
          CheckStatus.Failed, true, details = Vector(problem.message)))
        empty.copy(findings = Vector(PreviewFinding(problem.code, problem.message)), checks = checks.result)
      case Right(history) =>
        checks.add(PreviewCheck(PreviewCheck.HistoryConsistent, "The schema history forms one unbroken chain",
          CheckStatus.Passed, true))
        var existing = Vector.empty[QualifiedName]
        if history.isEmpty then checks.run(PreviewCheck.NamesFree, "Which relations of the target exist already", true) {
          existing = backend.existingRelations(connection, target)
          PreviewCheck(PreviewCheck.NamesFree, "Which relations of the target exist already", CheckStatus.Passed, true,
            details = existing.map(name => s"${name.display} exists"))
        }
        val plan = MigrationPlanner.plan(backend, options, history, records, target, targetFingerprint, backfills, existing)
        schemaChecks(connection, plan, checks)
        dataChecks(connection, plan, previewOptions, checks)
        report(empty, plan, checks.result)

  private def report(empty: PreviewReport, plan: MigrationPlan, checks: Vector[PreviewCheck]): PreviewReport =
    val steps = PreviewReport.steps(plan)
    val required = plan.steps.collect { case PlanStep.Statement(_, _, Some(approval), _) => approval }.distinct
    val findings = plan.problems.map { problem =>
      val step = problem.subject.flatMap(subject => steps.find(_.subjects.contains(subject)).map(_.number))
      PreviewFinding(problem.code, problem.message, step, problem.subject)
    }
    empty.copy(
      mode = Some(plan.mode),
      revision = plan.revision,
      previousFingerprint = Option.when(plan.history.nonEmpty)(plan.previousFingerprint),
      complete = plan.complete,
      steps = steps,
      requiredApprovals = required.map(PreviewReport.approval).sorted,
      missingApprovals = required.filterNot(options.approvals.contains).map(PreviewReport.approval).sorted,
      locks = locks(plan),
      findings = findings,
      checks = checks,
      notes = plan.notes ++ Option.when(plan.pending.nonEmpty)(
        s"Backfills that stay pending, because their columns do not become required: ${plan.pending.mkString(", ")}")
    )

  /** The locks the migration will take: every modeled table exclusively once something
    * changes, a shared lock when the target is already applied, and always the migration lock.
    */
  private def locks(plan: MigrationPlan): Vector[PreviewLock] =
    val tables = plan.mode match
      case PlanMode.NoChange => plan.target.tables.map(_.name.display -> "ACCESS SHARE")
      case PlanMode.Migration => (plan.previous.tables ++ plan.target.tables).map(_.name.display).distinct
          .map(_ -> "ACCESS EXCLUSIVE")
      case PlanMode.Adoption | PlanMode.ManualMigration => plan.target.tables.map(_.name.display -> "ACCESS EXCLUSIVE")
    PreviewLock("the migration lock of the database", "transaction advisory lock") +:
      tables.sorted.map(PreviewLock(_, _))

  /** The database must match the model the plan starts from, relations it creates must be
    * free, and a manual migration's removed relations must be gone.
    */
  private def schemaChecks(connection: Connection, plan: MigrationPlan, checks: Checks): Unit =
    def matches(model: SchemaModel, label: String): Unit =
      checks.run(PreviewCheck.SchemaMatches, s"The database matches the $label", required = true) {
        val inspection = backend.inspect(connection, model)
        val status =
          if inspection.differences.nonEmpty then CheckStatus.Failed
          else if inspection.undecided.nonEmpty then CheckStatus.Inconclusive
          else CheckStatus.Passed
        PreviewCheck(PreviewCheck.SchemaMatches, s"The database matches the $label", status, true,
          details = inspection.differences ++ inspection.undecided)
      }
    plan.mode match
      case PlanMode.NoChange => matches(plan.target, "applied schema")
      case PlanMode.Adoption => matches(plan.target, "target schema it adopts")
      case PlanMode.ManualMigration =>
        checks.run(PreviewCheck.RelationsAbsent, "Relations the target no longer has are gone", required = true) {
          val remaining = backend.existingRelations(connection, SchemaModel(
            plan.previous.tables.filter(table => plan.absent.contains(table.name)),
            plan.previous.sequences.filter(sequence => plan.absent.contains(sequence.name))))
          PreviewCheck(PreviewCheck.RelationsAbsent, "Relations the target no longer has are gone",
            if remaining.isEmpty then CheckStatus.Passed else CheckStatus.Failed, true,
            details = remaining.map(name => s"${name.display} still exists"))
        }
        matches(plan.target, "target schema migrated by hand")
      case PlanMode.Migration =>
        if plan.history.nonEmpty then matches(plan.previous, "stored schema")
        val created = plan.operations.flatMap {
          case SchemaOperation.CreateTable(table) => Vector(table.name)
          case SchemaOperation.CreateSequence(sequence) => Vector(sequence.name)
          case SchemaOperation.RenameTable(_, _, to) => Vector(to)
          case SchemaOperation.RenameSequence(_, _, to) => Vector(to)
          case _ => Vector.empty
        }
        if created.nonEmpty then
          checks.run(PreviewCheck.NamesFree, "The names of new and renamed relations are free", required = true) {
            val taken = backend.existingRelations(connection, SchemaModel(
              created.map(name => TableModel(SchemaId(name.display), name, Vector.empty))))
            PreviewCheck(PreviewCheck.NamesFree, "The names of new and renamed relations are free",
              if taken.isEmpty then CheckStatus.Passed else CheckStatus.Failed, true,
              details = taken.map(name => s"${name.display} is taken"))
          }

  /** Data checks come with the plan's steps; see [[PreviewDataChecks]]. Each table a backfill
    * fills also gets an optional, estimated row count.
    */
  private def dataChecks(connection: Connection, plan: MigrationPlan, previewOptions: PreviewOptions, checks: Checks): Unit =
    PreviewDataChecks.planned(plan).foreach { planned =>
      if previewOptions.dataChecks == DataChecks.Skip || plan.mode != PlanMode.Migration || !plan.complete then
        checks.add(PreviewCheck(planned.code, planned.description, CheckStatus.NotRun, true, planned.step, planned.subject,
          Vector(if previewOptions.dataChecks == DataChecks.Skip then "Data checks were not selected."
            else "The plan cannot run, so its data is not checked.")))
      else checks.run(planned.code, planned.description, required = true, planned.step, planned.subject) {
        val rows = backend.dataCheck(connection, planned.query, previewOptions.includeExactCounts)
        val found = rows > 0
        PreviewCheck(planned.code, planned.description, if found then CheckStatus.Failed else CheckStatus.Passed, true,
          planned.step, planned.subject, if found then Vector(planned.failure) else Vector.empty,
          if previewOptions.includeExactCounts then RowCount.Exact(rows) else RowCount.Unknown)
      }
    }
    if previewOptions.dataChecks != DataChecks.Skip && plan.mode == PlanMode.Migration && plan.complete then
      plan.steps.zipWithIndex.collect { case (fill: PlanStep.Fill, index) => (fill, index + 1) }.foreach { (fill, step) =>
        val table = plan.previous.tables.find(_.columns.exists(_.id == fill.column))
          .orElse(plan.target.tables.find(_.columns.exists(_.id == fill.column))).get
        val description = s"Rows of ${table.name.display} that backfill '${fill.backfill.id}' reads"
        checks.run(PreviewCheck.RowEstimate, description, required = false, Some(step), Some(fill.column.value)) {
          val rows = plan.previous.tables.find(_.id == table.id).flatMap(t => backend.estimateRows(connection, t.name))
          PreviewCheck(PreviewCheck.RowEstimate, description, CheckStatus.Passed, false, Some(step), Some(fill.column.value),
            rows = rows.fold(RowCount.Unknown)(RowCount.Estimate(_)))
        }
      }
