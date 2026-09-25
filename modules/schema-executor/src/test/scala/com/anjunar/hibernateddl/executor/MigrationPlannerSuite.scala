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

  test("no approval turns a narrowing into a supported type change, not even returning to an earlier revision") {
    def named(dataType: SqlType) = SchemaModel(Vector(account.copy(columns = Vector(name.copy(dataType = dataType), bio))))
    val narrow = named(SqlType.Varchar(100))
    val all = Set[Approval](Approval.Revert(1), Approval.Drop(name.id), Approval.RenameBack(name.id))
    val result = plan(narrow, history(narrow, named(SqlType.Varchar(255))), ExecutionOptions(approvals = all))
    assert(!result.executable)
    assertEquals(result.steps, Vector.empty)
    assert(result.problems.exists(p => p.code == PlanProblem.UnsupportedChange && p.message.contains("shortens VARCHAR")),
      result.problems)
  }

  test("a type change runs after the renames, and its column is found under the names it has before the migration") {
    val wide = name.copy(name = SqlIdentifier("display_name"), dataType = SqlType.Varchar(255))
    val before = SchemaModel(Vector(account.copy(columns = Vector(name.copy(dataType = SqlType.Varchar(100)), bio))))
    val renamed = account.copy(name = QualifiedName(SqlIdentifier("accounts")), columns = Vector(wide, bio))
    val result = plan(SchemaModel(Vector(renamed)), history(before))
    assert(result.executable)
    assertEquals(result.operations.map(_.getClass.getSimpleName), Vector("RenameTable", "RenameColumn", "ChangeColumnType"))
    assertEquals(MigrationPlanner.typeChanges(result).map((change, table, column) => (change.columnId, table, column)),
      Vector((name.id, account.name, name.name)))
  }

  test("planner and preview resolve a backfill from a retyped source with the source's target type") {
    val source = ColumnModel(SchemaId("account-visits"), SqlIdentifier("visits"), SqlType.Integer)
    val copy = ColumnModel(SchemaId("account-total"), SqlIdentifier("total"), SqlType.Integer)
    val before = SchemaModel(Vector(account.copy(columns = Vector(name, source, copy))))
    val after = SchemaModel(Vector(account.copy(columns = Vector(name, source.copy(dataType = SqlType.BigInt),
      copy.copy(dataType = SqlType.BigInt, nullable = false)))))
    val backfill = Backfill.fillNulls("total-v1", copy.id, BackfillTrigger.BecomesRequired, BackfillValue.column(source.id))
    val result = plan(after, history(before), backfills = Vector(backfill))
    assert(result.executable, result.problems)
    val fill = result.steps.indexWhere(_.isInstanceOf[PlanStep.Fill])
    assert(fill > result.steps.lastIndexWhere {
      case PlanStep.Statement(_: SchemaOperation.ChangeColumnType, _, _, _) => true
      case _ => false
    }, result.steps)
    // Before, the preview read the source as INTEGER, which cannot fill BIGINT, and projected NULL.
    val nulls = PreviewDataChecks.planned(result).find(_.code == PreviewCheck.NoNulls).get
    assertEquals(nulls.query, DataQuery.Nulls(account.name,
      Projection.Filled(Some(copy.name), FillValue.Column(source.name), SqlType.BigInt)))
  }

  private val keyed = account.copy(uniqueKeys = Vector(UniqueKeyModel(Vector(name.id))))
  private val unkeyed = SchemaModel(Vector(account.copy(uniqueKeys = Vector.empty)))
  private val nameKey = UniqueKeyRef(account.id, Vector(name.id))

  test("dropping a unique key needs its own approval, which names it by signature and as a setting entry") {
    val blocked = plan(unkeyed, history(SchemaModel(Vector(keyed))))
    assertEquals((blocked.complete, blocked.executable), (true, false))
    val problem = blocked.problems.head
    assertEquals((problem.code, problem.subject), (PlanProblem.ApprovalMissing, Some(nameKey.signature)))
    assert(problem.message.contains(s"Approval.DropUniqueKey(\"${nameKey.signature}\")"), problem.message)
    assert(problem.message.contains(s"drop-unique:${nameKey.signature}"), problem.message)
    assertEquals(blocked.steps.collect { case s: PlanStep.Statement => s.approval }, Vector(Some(Approval.dropUniqueKey(nameKey))))
    assert(plan(unkeyed, history(SchemaModel(Vector(keyed))), ExecutionOptions(approvals = Set(Approval.dropUniqueKey(nameKey))))
      .executable)
  }

  test("neither another key's approval nor drop or revert approvals permit dropping a unique key") {
    val other = UniqueKeyRef(account.id, Vector(bio.id))
    val all = Set[Approval](Approval.dropUniqueKey(other), Approval.Drop(name.id), Approval.Drop(account.id), Approval.Revert(1))
    val result = plan(unkeyed, history(unkeyed, SchemaModel(Vector(keyed))), ExecutionOptions(approvals = all))
    assertEquals(result.problems.map(_.code), Vector(PlanProblem.ApprovalMissing))
  }

  test("a dropped plain index needs no approval; bound steps are found under the names before the migration") {
    val indexed = account.copy(indexes = Vector(IndexModel(Vector(IndexColumn(name.id, descending = true)))))
    val renamed = account.copy(name = QualifiedName(SqlIdentifier("accounts")),
      columns = Vector(name.copy(name = SqlIdentifier("display_name")), bio), uniqueKeys = Vector.empty)
    val result = plan(SchemaModel(Vector(renamed)), history(SchemaModel(Vector(indexed.copy(uniqueKeys = keyed.uniqueKeys)))),
      ExecutionOptions(approvals = Set(Approval.dropUniqueKey(nameKey))))
    assert(result.executable, result.problems)
    assertEquals(MigrationPlanner.bindings(result).map((index, operation, table, columns) =>
      (index, operation.getClass.getSimpleName, table, columns)), Vector(
      (2, "DropUniqueKey", account.name, Vector(name.name)),
      (3, "DropIndex", account.name, Vector(name.name))))
    assertEquals(result.operations.take(2).map(_.getClass.getSimpleName), Vector("RenameTable", "RenameColumn"))
  }

  test("approval entries read back as the approvals that wrote them; malformed entries are refused") {
    Vector(Approval.Drop(SchemaId("a/b")), Approval.RenameBack(SchemaId("c")), Approval.Revert(3),
      Approval.dropUniqueKey(nameKey)).foreach { approval =>
      assertEquals(Approval.parse(Approval.entry(approval)), Right(approval))
    }
    Vector("drop-unique:", "drop-unique:abc", "drop-unique:u1-XYZ", "unique:u1-00", "revert:0").foreach { entry =>
      assert(Approval.parse(entry).isLeft, entry)
    }
  }
