package com.anjunar.hibernateddl.executor

import com.anjunar.hibernateddl.core.*

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
