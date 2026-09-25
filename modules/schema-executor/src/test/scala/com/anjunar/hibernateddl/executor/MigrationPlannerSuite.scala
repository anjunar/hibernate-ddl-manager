package com.anjunar.hibernateddl.executor

import com.anjunar.hibernateddl.core.*

class MigrationPlannerSuite extends munit.FunSuite:
  private val backend = new PlanningBackend:
    def validate(model: SchemaModel): Vector[String] = Vector.empty
    def render(operations: Vector[SchemaOperation]): Either[Vector[String], Vector[String]] =
      Right(operations.map(_.getClass.getSimpleName))
    def nullCount(table: QualifiedName, column: SqlIdentifier): String = s"count ${column.value}"
    def renderFill(fill: NullFill): Either[Vector[String], BoundStatement] = Right(BoundStatement(s"fill ${fill.column.value}", Vector.empty))

  private val name = ColumnModel(SchemaId("account-name"), SqlIdentifier("name"), SqlType.Text)
  private val bio = ColumnModel(SchemaId("account-bio"), SqlIdentifier("bio"), SqlType.Text)
  private val account = TableModel(SchemaId("account"), QualifiedName(SqlIdentifier("account")), Vector(name, bio))
  private val initial = SchemaModel(Vector(account))

  private def history(models: SchemaModel*): Vector[AppliedRevision] =
    models.zipWithIndex.foldLeft(Vector.empty[AppliedRevision]) { case (chain, (model, index)) =>
      val previous = chain.lastOption.fold(MigrationPlanner.EmptyFingerprint)(_.entry.targetFingerprint)
      chain :+ AppliedRevision(HistoryEntry(index + 1L, previous, SchemaFingerprint.of(model), SchemaModelJson.encode(model),
        Vector.empty), model)
    }

  private def plan(target: SchemaModel, applied: Vector[AppliedRevision] = history(initial),
      options: ExecutionOptions = ExecutionOptions(), backfills: Vector[Backfill] = Vector.empty,
      existing: Vector[QualifiedName] = Vector.empty, records: Vector[BackfillRecord] = Vector.empty) =
    MigrationPlanner.plan(backend, options, applied, records, target, SchemaFingerprint.of(target), backfills, existing)

  test("a missing approval keeps the step in a complete plan, marked unapproved, and blocks it") {
    val dropped = SchemaModel(Vector(account.copy(columns = Vector(name))))
    val blocked = plan(dropped)
    assertEquals((blocked.mode, blocked.complete, blocked.executable), (PlanMode.Migration, true, false))
    assertEquals(blocked.steps, Vector(PlanStep.Statement(SchemaOperation.DropColumn(account.id, account.name, bio.id, bio.name),
      "DropColumn", Some(Approval.Drop(bio.id)), false)))
    assertEquals(blocked.problems.map(p => (p.code, p.subject)), Vector(PlanProblem.ApprovalMissing -> Some("account-bio")))
    val approved = plan(dropped, options = ExecutionOptions(approvals = Set(Approval.Drop(bio.id))))
    assert(approved.executable)
    assertEquals(approved.steps.collect { case step: PlanStep.Statement => step.approved }, Vector(true))
  }

  test("an unsupported change leaves an incomplete plan without steps, with the way out as a note") {
    val retyped = SchemaModel(Vector(account.copy(columns = Vector(name.copy(dataType = SqlType.Integer), bio))))
    val result = plan(retyped)
    assertEquals((result.complete, result.steps, result.problems.map(_.code)), (false, Vector.empty,
      Vector(PlanProblem.UnsupportedChange)))
    assert(result.notes.head.contains(SchemaFingerprint.of(retyped)))
  }

  test("modes: no change, creation, adoption and manual migration follow the executor's rules") {
    assertEquals(plan(initial).mode, PlanMode.NoChange)
    val created = plan(initial, applied = Vector.empty)
    assertEquals((created.mode, created.steps.size, created.executable), (PlanMode.Migration, 1, true))
    val adoption = plan(initial, applied = Vector.empty, existing = Vector(account.name))
    assertEquals((adoption.mode, adoption.problems.map(_.code)), (PlanMode.Adoption, Vector(PlanProblem.AdoptionNotEnabled)))
    assert(plan(initial, Vector.empty, ExecutionOptions(adoptExistingSchema = true), existing = Vector(account.name)).executable)
    val renamed = SchemaModel(Vector(account.copy(name = QualifiedName(SqlIdentifier("accounts")))))
    val manual = plan(renamed, options = ExecutionOptions(acceptManualMigration = Some(SchemaFingerprint.of(renamed))))
    assertEquals((manual.mode, manual.absent, manual.executable), (PlanMode.ManualMigration, Vector(account.name), true))
  }

  test("a changed backfill, a revert and a retired ID are problems even where the plan is otherwise complete") {
    val rule = Backfill.fillNulls("name-v1", name.id, BackfillTrigger.BecomesRequired, BackfillValue.literal("x"))
    val record = BackfillRecord("name-v1", BackfillChecksum.Format, "0" * 64, name.id, 1, MigrationPlanner.EmptyFingerprint,
      SchemaFingerprint.of(initial), BackfillResult.Adopted, None)
    assertEquals(plan(initial, backfills = Vector(rule), records = Vector(record)).problems.map(_.code),
      Vector(PlanProblem.BackfillChanged))
    val withoutBio = SchemaModel(Vector(account.copy(columns = Vector(name))))
    val codes = plan(initial, applied = history(initial, withoutBio)).problems.map(_.code)
    assertEquals(codes, Vector(PlanProblem.RevertNotApproved, PlanProblem.RetiredId))
  }

  test("a column that becomes required gets the fill, the NULL check and the statement in that order") {
    val required = SchemaModel(Vector(account.copy(columns = Vector(name.copy(nullable = false), bio))))
    val rule = Backfill.fillNulls("name-v1", name.id, BackfillTrigger.BecomesRequired, BackfillValue.literal("x"))
    val result = plan(required, backfills = Vector(rule))
    assertEquals(result.steps.map {
      case step: PlanStep.Fill => s"fill ${step.backfill.id}"
      case step: PlanStep.NoNulls => s"check ${step.columnId.value}"
      case step: PlanStep.Statement => step.sql
    }, Vector("fill name-v1", "check account-name", "SetNotNull"))
    assertEquals(result.pending, Vector.empty)
  }
