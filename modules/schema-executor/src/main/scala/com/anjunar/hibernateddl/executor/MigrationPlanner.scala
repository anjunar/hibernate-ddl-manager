package com.anjunar.hibernateddl.executor

import com.anjunar.hibernateddl.core.*

/** A history entry whose model was decoded and verified as part of an unbroken chain. */
final case class AppliedRevision(entry: HistoryEntry, model: SchemaModel)

/** Something that keeps a plan from running, with a stable code and the stable ID it concerns. */
final case class PlanProblem(code: String, message: String, subject: Option[String] = None)

object PlanProblem:
  val HistoryInconsistent = "HISTORY_INCONSISTENT"
  val BackfillHistoryInconsistent = "BACKFILL_HISTORY_INCONSISTENT"
  val BackfillChanged = "BACKFILL_CHANGED"
  val RevertNotApproved = "REVERT_NOT_APPROVED"
  val RetiredId = "RETIRED_ID"
  val AdoptionNotEnabled = "ADOPTION_NOT_ENABLED"
  val AdoptionIncomplete = "ADOPTION_INCOMPLETE"
  val ManualTargetMismatch = "MANUAL_TARGET_MISMATCH"
  val ManualWithoutHistory = "MANUAL_WITHOUT_HISTORY"
  val UnsupportedChange = "UNSUPPORTED_CHANGE"
  val ApprovalMissing = "APPROVAL_MISSING"
  val RiskNotAllowed = "RISK_NOT_ALLOWED"
  val RenderFailed = "RENDER_FAILED"
  val BackfillConflict = "BACKFILL_CONFLICT"
  val BackfillInvalid = "BACKFILL_INVALID"

/** NoChange: the target is applied. Adoption and ManualMigration record the database as it is. */
enum PlanMode:
  case NoChange, Migration, Adoption, ManualMigration

/** One step of a migration, in execution order. */
enum PlanStep:
  /** DDL for one operation; `approval` names what the step needs, `approved` whether it has it. */
  case Statement(operation: SchemaOperation, sql: String, approval: Option[Approval], approved: Boolean)
  /** A backfill filling a column's NULLs. */
  case Fill(backfill: Backfill, column: SchemaId, statement: BoundStatement)
  /** A check that a column has no NULL left before it becomes required. */
  case NoNulls(columnId: SchemaId, column: String, query: String, hint: String)

/** The plan for one server start, computed without the database.
  *
  * `complete` is false when an unsupported change, a rendering failure or an unresolvable
  * backfill left the steps partial; such a plan must never look executable. `problems` block
  * the plan even when its steps are complete, such as a missing approval. `notes` explain a way
  * out. `absent` names relations the database must no longer have, `adopted` and `created` the
  * backfills recorded without running, and `pending` those left pending.
  */
final case class MigrationPlan(
    mode: PlanMode,
    history: Vector[AppliedRevision],
    records: Vector[BackfillRecord],
    target: SchemaModel,
    targetFingerprint: String,
    steps: Vector[PlanStep],
    complete: Boolean,
    problems: Vector[PlanProblem],
    notes: Vector[String] = Vector.empty,
    absent: Vector[QualifiedName] = Vector.empty,
    adopted: Vector[Backfill] = Vector.empty,
    created: Vector[Backfill] = Vector.empty,
    pending: Vector[String] = Vector.empty
):
  def revision: Long = history.lastOption.fold(0L)(_.entry.revision)
  def previous: SchemaModel = history.lastOption.fold(MigrationPlanner.EmptyModel)(_.model)
  def previousFingerprint: String = history.lastOption.fold(MigrationPlanner.EmptyFingerprint)(_.entry.targetFingerprint)
  def executable: Boolean = complete && problems.isEmpty
  def operations: Vector[SchemaOperation] = steps.collect { case step: PlanStep.Statement => step.operation }

/** The planning rules that server starts and previews share. Planning reads nothing from the
  * database: the caller passes the history, the backfill records and which of the target's
  * relations exist.
  */
object MigrationPlanner:
  val EmptyModel: SchemaModel = SchemaModel(Vector.empty)
  val EmptyFingerprint: String = SchemaFingerprint.of(EmptyModel)

  /** Checks everything that does not depend on the database and returns the target fingerprint. */
  def prepare(
      backend: PlanningBackend,
      options: ExecutionOptions,
      target: SchemaModel,
      backfills: Vector[Backfill]
  ): Either[Vector[String], String] =
    val errors = Vector.newBuilder[String]
    val maximumTimeoutMillis = 24 * 60 * 60 * 1000
    if options.lockTimeoutMillis <= 0 || options.lockTimeoutMillis > maximumTimeoutMillis then
      errors += "Lock timeout must be greater than zero and at most 24 hours"
    if options.statementTimeoutMillis <= 0 || options.statementTimeoutMillis > maximumTimeoutMillis then
      errors += "Statement timeout must be greater than zero and at most 24 hours"
    errors ++= (SchemaValidation.validate(target) ++ backend.validate(target)).distinct.map("Target schema: " + _)
    errors ++= BackfillValidation.validate(backfills)
    val messages = errors.result()
    if messages.nonEmpty then Left(messages) else Right(SchemaFingerprint.of(target))

  /** Decodes every stored model and checks that the entries form one unbroken chain. */
  def verifyHistory(entries: Vector[HistoryEntry]): Either[PlanProblem, Vector[AppliedRevision]] =
    entries.foldLeft[Either[PlanProblem, Vector[AppliedRevision]]](Right(Vector.empty)) { (result, entry) =>
      result.flatMap { chain =>
        def corrupt(reason: String) = Left(PlanProblem(PlanProblem.HistoryInconsistent,
          s"Schema history is inconsistent at revision ${entry.revision}: $reason; refusing to migrate"))
        if entry.revision != chain.size + 1 then corrupt(s"expected revision ${chain.size + 1}")
        else if entry.previousFingerprint != chain.lastOption.fold(EmptyFingerprint)(_.entry.targetFingerprint) then
          corrupt("previous fingerprint does not match the preceding revision")
        else SchemaModelJson.decode(entry.model) match
          case Left(error) => corrupt(s"stored model is unreadable ($error)")
          case Right(model) if SchemaFingerprint.of(model) != entry.targetFingerprint =>
            corrupt("stored model does not match its fingerprint")
          case Right(model) => Right(chain :+ AppliedRevision(entry, model))
      }
    }

  /** Plans a start. `existing` lists which of the target's tables and sequences exist; it only
    * matters without history, where it decides between creation and adoption.
    */
  def plan(
      backend: PlanningBackend,
      options: ExecutionOptions,
      history: Vector[AppliedRevision],
      records: Vector[BackfillRecord],
      target: SchemaModel,
      targetFingerprint: String,
      backfills: Vector[Backfill],
      existing: Vector[QualifiedName]
  ): MigrationPlan =
    val base = MigrationPlan(PlanMode.Migration, history, records, target, targetFingerprint, Vector.empty, false, Vector.empty)
    val recordProblems = verifyRecords(history, records)
    if recordProblems.nonEmpty then base.copy(problems = recordProblems)
    else
      val changed = changedBackfills(records, backfills)
      val pending = backfills.filterNot(backfill => records.exists(_.id == backfill.id))
      if targetFingerprint == base.previousFingerprint then
        base.copy(mode = PlanMode.NoChange, complete = true, problems = changed, pending = pending.map(_.id))
      else
        val returning = history.find(_.entry.targetFingerprint == targetFingerprint).filterNot { older =>
          options.approvals.contains(Approval.Revert(older.entry.revision))
        }.map { older =>
          PlanProblem(PlanProblem.RevertNotApproved, s"Target schema equals revision ${older.entry.revision}, but the " +
            s"database is at revision ${base.revision}; a server with an older schema must not start after a newer " +
            s"migration. If returning to it is intended, approve it with Approval.Revert(${older.entry.revision})")
        }
        val problems = changed ++ returning ++ retired(history, target)
        if history.isEmpty && existing.nonEmpty then adoption(base, options, existing, pending, problems)
        else if options.acceptManualMigration.nonEmpty then manual(base, options, pending, problems)
        else migration(backend, options, base, pending, problems)

  private def adoption(
      base: MigrationPlan,
      options: ExecutionOptions,
      existing: Vector[QualifiedName],
      pending: Vector[Backfill],
      problems: Vector[PlanProblem]
  ): MigrationPlan =
    def show(names: Vector[QualifiedName]) = names.map(_.display).distinct.sorted.mkString(", ")
    val target = base.target
    val missing = (target.tables.map(_.name) ++ target.sequences.map(_.name)).filterNot(existing.contains)
    val adoptionProblems =
      if !options.adoptExistingSchema then Vector(PlanProblem(PlanProblem.AdoptionNotEnabled,
        s"The database has no schema history but already contains ${show(existing)} of the target schema; " +
          "enable adoptExistingSchema to adopt a database that matches the target exactly"))
      else if missing.nonEmpty then Vector(PlanProblem(PlanProblem.AdoptionIncomplete,
        s"Adopting the existing schema requires every table and sequence of the target; missing: ${show(missing)}"))
      else Vector.empty
    val required = newlyRequired(EmptyModel, target)
    val (found, left) = pending.partition(backfill => required.contains(backfill.target))
    base.copy(mode = PlanMode.Adoption, complete = true, problems = problems ++ adoptionProblems, adopted = found,
      pending = left.map(_.id))

  private def manual(
      base: MigrationPlan,
      options: ExecutionOptions,
      pending: Vector[Backfill],
      problems: Vector[PlanProblem]
  ): MigrationPlan =
    val accepted = options.acceptManualMigration.get
    val target = base.target
    val manualProblems =
      if accepted != base.targetFingerprint then Vector(PlanProblem(PlanProblem.ManualTargetMismatch,
        s"acceptManualMigration names the target $accepted, but this target is ${base.targetFingerprint}; " +
          "remove the option or name this target"))
      else if base.history.isEmpty then Vector(PlanProblem(PlanProblem.ManualWithoutHistory,
        "A database without schema history has no previous revision to migrate by hand; " +
          "adopt an existing database with adoptExistingSchema instead"))
      else Vector.empty
    val previous = base.previous
    val targetNames = (target.tables.map(_.name) ++ target.sequences.map(_.name)).toSet
    val absent = previous.tables.map(_.name).filterNot(targetNames.contains) ++
      previous.sequences.map(_.name).filterNot(targetNames.contains)
    val required = newlyRequired(previous, target)
    val (found, left) = pending.partition(backfill => required.contains(backfill.target))
    base.copy(mode = PlanMode.ManualMigration, complete = true, problems = problems ++ manualProblems, absent = absent,
      adopted = found, pending = left.map(_.id))

  private def migration(
      backend: PlanningBackend,
      options: ExecutionOptions,
      base: MigrationPlan,
      pending: Vector[Backfill],
      problems: Vector[PlanProblem]
  ): MigrationPlan =
    val history = base.history
    val previous = base.previous
    val target = base.target
    DiffEngine.diff(previous, target) match
      case Left(errors) =>
        base.copy(problems = problems ++ errors.map(PlanProblem(PlanProblem.UnsupportedChange, _)), notes = Vector(
          "To migrate by hand instead, change the database to exactly the target schema and start once with " +
            s"acceptManualMigration = \"${base.targetFingerprint}\""))
      case Right(operations) =>
        def earlier(matches: TableModel => Boolean): Option[Long] =
          history.find(_.model.tables.exists(matches)).map(_.entry.revision)
        def renameBack(kind: String, id: SchemaId, revision: Option[Long]) = revision.map { revision =>
          Approval.RenameBack(id) -> (s"Renaming $kind '${id.value}' back to its name from revision $revision is what an " +
            s"older server would do; if intended, approve it with Approval.RenameBack(\"${id.value}\")")
        }
        def drop(kind: String, id: SchemaId, name: String) =
          Approval.Drop(id) -> (s"Dropping $kind '${id.value}' ($name) deletes its data; if intended, approve it with " +
            s"Approval.Drop(\"${id.value}\")")
        // Every approval an operation needs, with the message that asks for it.
        def needed(operation: SchemaOperation): Vector[(Approval, String)] = operation match
          case SchemaOperation.RenameTable(id, _, to) =>
            renameBack("table", id, earlier(table => table.id == id && table.name == to)).toVector
          case SchemaOperation.RenameColumn(tableId, _, columnId, _, to) =>
            renameBack("column", columnId,
              earlier(table => table.id == tableId && table.columns.exists(c => c.id == columnId && c.name == to))).toVector
          case SchemaOperation.RenameSequence(id, _, to) =>
            renameBack("sequence", id,
              history.find(_.model.sequences.exists(s => s.id == id && s.name == to)).map(_.entry.revision)).toVector
          case SchemaOperation.DropColumn(_, table, columnId, column) =>
            Vector(drop("column", columnId, s"${table.display}.${column.value}"))
          case SchemaOperation.DropTables(tables) => tables.map(table => drop("table", table.tableId, table.table.display))
          case SchemaOperation.DropSequence(id, sequence) => Vector(drop("sequence", id, sequence.display))
          case _ => Vector.empty
        val approvals = operations.map(needed)
        val approvalProblems = approvals.flatten.collect {
          case (approval, message) if !options.approvals.contains(approval) =>
            PlanProblem(PlanProblem.ApprovalMissing, message, Some(subject(approval)))
        }
        val rejectedRisks = operations.map(_.risk).filterNot(options.allowedRisks.contains).distinct
        val riskProblems = Option.when(rejectedRisks.nonEmpty)(PlanProblem(PlanProblem.RiskNotAllowed,
          s"Migration risks are not allowed: ${rejectedRisks.mkString(", ")}")).toVector
        val planned = problems ++ approvalProblems ++ riskProblems
        backend.render(operations) match
          case Left(errors) => base.copy(problems = planned ++ errors.map(PlanProblem(PlanProblem.RenderFailed, _)))
          case Right(statements) if statements.exists(sql => sql == null || sql.trim.isEmpty) ||
              statements.size != operations.size =>
            base.copy(problems = planned :+ PlanProblem(PlanProblem.RenderFailed,
              "Backend must render exactly one non-empty SQL statement per operation"))
          case Right(statements) =>
            val (fills, fillProblems) = planFills(backend, previous, target, operations, pending)
            val created = operations.collect {
              case SchemaOperation.CreateTable(table) => table.columns.filterNot(_.nullable).map(_.id)
            }.flatten.toSet
            val steps = operations.zip(statements).zip(approvals).flatMap { case ((operation, sql), required) =>
              val approval = required.headOption.map(_._1)
              val statement = PlanStep.Statement(operation, sql, approval, required.forall(r => options.approvals.contains(r._1)))
              operation match
                case SchemaOperation.SetNotNull(_, table, columnId, column) =>
                  val fill = fills.get(columnId)
                  val hint = fill.map(step => s"backfill '${step.backfill.id}' left them NULL")
                    .orElse(base.records.find(_.target == columnId).map(record => s"backfill '${record.id}' was recorded " +
                      s"in revision ${record.revision} and does not run again; a new rule for it needs a new ID"))
                    .getOrElse("register a backfill for it or fill the rows before the migration")
                  fill.toVector ++ Vector(PlanStep.NoNulls(columnId, s"${table.display}.${column.value}",
                    backend.nullCount(table, column), hint), statement)
                case _ => Vector(statement)
            }
            val createdBackfills = pending.filter(backfill => created.contains(backfill.target))
            val recorded = (fills.values.map(_.backfill) ++ createdBackfills).map(_.id).toSet
            base.copy(steps = steps, complete = fillProblems.isEmpty, problems = planned ++ fillProblems,
              created = createdBackfills, pending = pending.map(_.id).filterNot(recorded.contains))

  private def subject(approval: Approval): String = approval match
    case Approval.Drop(id) => id.value
    case Approval.RenameBack(id) => id.value
    case Approval.Revert(revision) => revision.toString

  /** Resolves the one pending backfill for each column that becomes required in an existing
    * table. The columns it may read are the target table's, plus the previous ones that are
    * dropped only after the fill.
    */
  private def planFills(
      backend: PlanningBackend,
      previous: SchemaModel,
      target: SchemaModel,
      operations: Vector[SchemaOperation],
      pending: Vector[Backfill]
  ): (Map[SchemaId, PlanStep.Fill], Vector[PlanProblem]) =
    val required = operations.collect { case operation: SchemaOperation.SetNotNull => operation.columnId -> operation }.toMap
    val applicable = pending.filter(backfill => required.contains(backfill.target))
    val conflicts = applicable.groupBy(_.target).toVector.sortBy(_._1.value).collect {
      case (column, rules) if rules.size > 1 =>
        PlanProblem(PlanProblem.BackfillConflict, s"Backfills ${rules.map(rule => s"'${rule.id}'").sorted.mkString(", ")} " +
          s"all fill column '${column.value}'; only one may apply", Some(column.value))
    }
    if conflicts.nonEmpty then (Map.empty, conflicts)
    else
      val filled = applicable.map(_.target).toSet
      val resolved = applicable.map { backfill =>
        val operation = required(backfill.target)
        val table = target.tables.find(_.id == operation.tableId).get
        val earlier = previous.tables.find(_.id == operation.tableId).toVector.flatMap(_.columns)
          .filterNot(column => table.columns.exists(_.id == column.id))
        val column = table.columns.find(_.id == backfill.target).get
        BackfillValidation.resolve(backfill, table.name, column, table.columns ++ earlier, filled - backfill.target)
          .flatMap(backend.renderFill)
          .map(statement => backfill.target -> new PlanStep.Fill(backfill, backfill.target, statement))
          .left.map(_.map(PlanProblem(PlanProblem.BackfillInvalid, _, Some(backfill.id))))
      }
      (resolved.flatMap(_.toOption).toMap, resolved.flatMap(_.left.toOption).flatten)

  /** Every recorded backfill must belong to a revision of the schema history. */
  private def verifyRecords(history: Vector[AppliedRevision], records: Vector[BackfillRecord]): Vector[PlanProblem] =
    records.flatMap { record =>
      def corrupt(reason: String) = Some(PlanProblem(PlanProblem.BackfillHistoryInconsistent,
        s"Backfill history is inconsistent at backfill '${record.id}': $reason; refusing to migrate", Some(record.id)))
      val applied = if record.revision < 1 || record.revision > history.size then None else Some(history(record.revision.toInt - 1))
      if record.format != BackfillChecksum.Format then corrupt(s"its definition format ${record.format} is unknown to this version")
      else if !applied.exists(a => a.entry.previousFingerprint == record.previousFingerprint &&
          a.entry.targetFingerprint == record.targetFingerprint) then
        corrupt(s"revision ${record.revision} with its fingerprints is not in the schema history")
      else if record.updatedRows.nonEmpty != (record.result == BackfillResult.Executed) then
        corrupt("only an executed backfill has a count of updated rows")
      else None
    }.take(1)

  /** A recorded ID must keep its definition. */
  private def changedBackfills(records: Vector[BackfillRecord], backfills: Vector[Backfill]): Vector[PlanProblem] =
    backfills.flatMap { backfill =>
      records.find(_.id == backfill.id).filter(_.checksum != BackfillChecksum.of(backfill)).map { record =>
        PlanProblem(PlanProblem.BackfillChanged, s"Backfill '${backfill.id}' was recorded in revision ${record.revision} " +
          "with another definition; a changed rule needs a new ID", Some(backfill.id))
      }
    }

  /** A table, column or sequence that an earlier revision had and the latest does not was
    * dropped. Its ID is retired: reusing it, for example copied from the version history of the
    * code, would attach the old identity to a new object, so no approval permits it.
    */
  private def retired(history: Vector[AppliedRevision], target: SchemaModel): Vector[PlanProblem] =
    def ids(model: SchemaModel): Set[SchemaId] =
      (model.tables.flatMap(table => table.id +: table.columns.map(_.id)) ++ model.sequences.map(_.id)).toSet
    val latest = history.lastOption.fold(Set.empty[SchemaId])(applied => ids(applied.model))
    (ids(target) -- latest).toVector.sortBy(_.value).flatMap { id =>
      history.findLast(applied => ids(applied.model).contains(id)).map { applied =>
        PlanProblem(PlanProblem.RetiredId, s"Stable ID '${id.value}' was dropped after revision ${applied.entry.revision} " +
          "and is retired; a retired ID must never be reused, generate a new one", Some(id.value))
      }
    }

  /** Each type change of a plan with the table and column names the database has before the
    * migration, where objects that depend on the column are looked up.
    */
  def typeChanges(plan: MigrationPlan): Vector[(SchemaOperation.ChangeColumnType, QualifiedName, SqlIdentifier)] =
    plan.operations.collect { case change: SchemaOperation.ChangeColumnType => change }.flatMap { change =>
      plan.previous.tables.find(_.id == change.tableId).flatMap { table =>
        table.columns.find(_.id == change.columnId).map(column => (change, table.name, column.name))
      }
    }

  /** Why objects outside the model keep a column from changing its type. */
  def blockedTypeChange(column: SchemaId, table: QualifiedName, name: SqlIdentifier, blockers: Vector[String]): String =
    s"Column '${column.value}' (${table.display}.${name.value}) cannot change its type while ${blockers.mkString(", ")} " +
      s"${if blockers.size == 1 then "depends" else "depend"} on it; change or drop the dependent object first, " +
      "the migration never uses CASCADE"

  /** Required columns of the target that were missing or nullable before. */
  def newlyRequired(previous: SchemaModel, target: SchemaModel): Set[SchemaId] =
    val before = previous.tables.flatMap(_.columns).map(column => column.id -> column.nullable).toMap
    target.tables.flatMap(_.columns).filter(column => !column.nullable && before.getOrElse(column.id, true)).map(_.id).toSet
