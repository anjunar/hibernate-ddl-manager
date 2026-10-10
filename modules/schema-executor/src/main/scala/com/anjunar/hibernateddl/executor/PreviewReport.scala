package com.anjunar.hibernateddl.executor

import com.anjunar.hibernateddl.core.*
import java.lang.{Boolean as JavaBoolean}
import java.lang.{Double as JavaDouble}
import java.lang.{Long as JavaLong}
import java.math.{BigDecimal as JavaBigDecimal}
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.OffsetDateTime
import java.util

/** The result of a preview. Text and JSON are rendered from it and show the same findings. */
final case class PreviewReport(
  format: Int,
  createdAt: Instant,
  mode: Option[PlanMode],
  revision: Long,
  previousFingerprint: Option[String],
  targetFingerprint: String,
  complete: Boolean,
  steps: Vector[PreviewStep],
  allowedRisks: Vector[String],
  requiredApprovals: Vector[String],
  presentApprovals: Vector[String],
  missingApprovals: Vector[String],
  locks: Vector[PreviewLock],
  findings: Vector[PreviewFinding],
  checks: Vector[PreviewCheck],
  notes: Vector[String]
):
  def outcome: PreviewOutcome =
    if !complete || findings.nonEmpty || checks.exists(_.status == CheckStatus.Failed) then PreviewOutcome.Blocked
    else if checks.exists(check =>
        check.required && Set(CheckStatus.Inconclusive, CheckStatus.NotRun).contains(check.status)
      )
    then PreviewOutcome.Incomplete
    else PreviewOutcome.Ready

object PreviewReport:
  /** Format 2 added `typeChange` to steps. */
  val Format: Int = 2

  def approval(value: Approval): String = Approval.entry(value)

  /** The steps of a plan, numbered from 1. `bound` holds the SQL of steps bound to database
    * objects, by index; the others show the plan's SQL, a template for a step not bound yet.
    */
  def steps(plan: MigrationPlan, bound: Map[Int, String] = Map.empty): Vector[PreviewStep] =
    plan.steps.zipWithIndex.map { (step, index) =>
      step match
        case PlanStep.Statement(operation, sql, approval, approved) =>
          PreviewStep(
            index + 1,
            "ddl",
            describe(operation) + alsoRemoved(plan, operation),
            subjects(operation),
            bound.getOrElse(index, sql),
            Vector.empty,
            Some(operation.risk.toString),
            approval.map(this.approval),
            approved,
            typeChange(plan, operation)
          )
        case PlanStep.Fill(backfill, column, statement) =>
          PreviewStep(
            index + 1,
            "fill",
            s"Fill the NULLs of column '${column.value}' with backfill '${backfill.id}'",
            Vector(column.value),
            statement.sql,
            statement.parameters.map(parameterType),
            None,
            None,
            true
          )
        case PlanStep.NoNulls(columnId, column, query, _) =>
          PreviewStep(
            index + 1,
            "null check",
            s"Check that no row of $column holds NULL",
            Vector(columnId.value),
            query,
            Vector.empty,
            None,
            None,
            true
          )
    }

  /** What a dropped column or table takes with it: its unique keys, indexes and foreign keys,
    * which get no steps and no approvals of their own.
    */
  private def alsoRemoved(plan: MigrationPlan, operation: SchemaOperation): String =
    def structures(table: TableModel, touches: Vector[SchemaId] => Boolean): Vector[String] =
      def names(ids: Vector[SchemaId]) =
        ids.map(id => table.columns.find(_.id == id).get.name.value).mkString("(", ", ", ")")
      table.uniqueKeys.filter(key => touches(key.columns)).map(key => s"unique key ${names(key.columns)}") ++
        table.indexes.filter(index => touches(index.columns.map(_.column))).map { index =>
          "index " + index.columns.map { column =>
            table.columns.find(_.id == column.column).get.name.value + (if column.descending then " DESC" else "")
          }.mkString("(", ", ", ")")
        } ++
        table.foreignKeys.filter(key => touches(key.columns)).map(key => s"foreign key ${names(key.columns)}")
    val removed = operation match
      case SchemaOperation.DropColumn(tableId, _, columnId, _) =>
        plan.previous.tables.find(_.id == tableId).toVector.flatMap(structures(_, _.contains(columnId)))
      case SchemaOperation.DropTables(tables) =>
        tables.flatMap(dropped =>
          plan.previous.tables.find(_.id == dropped.tableId).toVector.flatMap { table =>
            structures(table, _ => true).map(structure => s"$structure of ${table.name.display}")
          }
        )
      case _ => Vector.empty
    if removed.isEmpty then "" else s", which also removes ${removed.mkString(", ")}"

  private def typeChange(plan: MigrationPlan, operation: SchemaOperation): Option[PreviewTypeChange] = operation match
    case SchemaOperation.ChangeColumnType(tableId, _, columnId, _, from, to) =>
      val (rule, rewrite) = TypeChangeRules.classify(from, to) match
        case TypeChange.Widening(rule, rewrite) => (rule, rewrite)
        // Unreachable: a plan with any other change is refused before it has steps.
        case _ => ("not a supported widening", TableRewrite.Expected)
      val affected = plan.target.tables.find(_.id == tableId).toVector.flatMap { table =>
        def names(ids: Vector[SchemaId]) =
          ids.map(id => table.columns.find(_.id == id).get.name.value).mkString("(", ", ", ")")
        table.uniqueKeys.filter(_.columns.contains(columnId)).map(key => s"unique key ${names(key.columns)}") ++
          table.indexes.filter(_.columns.exists(_.column == columnId)).map { index =>
            "index " + index.columns.map { column =>
              table.columns.find(_.id == column.column).get.name.value + (if column.descending then " DESC" else "")
            }.mkString("(", ", ", ")")
          } ++
          table.columns.find(_.id == columnId).flatMap(_.check).map(check => s"check $check")
      }
      Some(PreviewTypeChange(
        columnId.value,
        from.toString,
        to.toString,
        rule,
        valuesPreserved = true,
        rewrite.toString.toLowerCase,
        affected
      ))
    case _ => None

  private def parameterType(value: AnyRef): String = value match
    case _: String         => "text"
    case _: JavaLong       => "bigint"
    case _: JavaBigDecimal => "numeric"
    case _: JavaDouble     => "double precision"
    case _: JavaBoolean    => "boolean"
    case _: util.UUID      => "uuid"
    case _: LocalDate      => "date"
    case _: LocalTime      => "time"
    case _: LocalDateTime  => "timestamp"
    case _: OffsetDateTime => "timestamp with time zone"
    case other             => other.getClass.getSimpleName

  private def subjects(operation: SchemaOperation): Vector[String] = operation match
    case SchemaOperation.CreateSequence(sequence)            => Vector(sequence.id.value)
    case SchemaOperation.RenameSequence(id, _, _)            => Vector(id.value)
    case SchemaOperation.CreateTable(table)                  => Vector(table.id.value)
    case SchemaOperation.AddColumn(_, _, column)             => Vector(column.id.value)
    case SchemaOperation.RenameTable(id, _, _)               => Vector(id.value)
    case SchemaOperation.RenameColumn(_, _, id, _, _)        => Vector(id.value)
    case SchemaOperation.ChangeColumnType(_, _, id, _, _, _) => Vector(id.value)
    case SchemaOperation.AddUniqueKey(id, _, _)              => Vector(id.value)
    case SchemaOperation.DropIndex(ref, _, _)                => Vector(ref.table.value)
    case SchemaOperation.DropUniqueKey(ref, _, _)            => Vector(ref.table.value, ref.signature)
    case SchemaOperation.ChangeCheck(_, _, id, _, _, _)      => Vector(id.value)
    case SchemaOperation.CreateIndex(id, _, _)               => Vector(id.value)
    case SchemaOperation.AddForeignKey(id, _, _, _, _, _)    => Vector(id.value)
    case SchemaOperation.SetNotNull(_, _, id, _)             => Vector(id.value)
    case SchemaOperation.DropNotNull(_, _, id, _)            => Vector(id.value)
    case SchemaOperation.DropColumn(_, _, id, _)             => Vector(id.value)
    case SchemaOperation.DropTables(tables)                  => tables.map(_.tableId.value)
    case SchemaOperation.DropSequence(id, _)                 => Vector(id.value)

  def describe(operation: SchemaOperation): String = operation match
    case SchemaOperation.CreateSequence(sequence)            => s"Create sequence ${sequence.name.display}"
    case SchemaOperation.RenameSequence(_, from, to)         => s"Rename sequence ${from.display} to ${to.name.value}"
    case SchemaOperation.CreateTable(table)                  => s"Create table ${table.name.display}"
    case SchemaOperation.AddColumn(_, table, column)         => s"Add column ${column.name.value} to ${table.display}"
    case SchemaOperation.RenameTable(_, from, to)            => s"Rename table ${from.display} to ${to.name.value}"
    case SchemaOperation.RenameColumn(_, table, _, from, to) =>
      s"Rename column ${table.display}.${from.value} to ${to.value}"
    case SchemaOperation.ChangeColumnType(_, table, _, column, from, to) =>
      s"Change the type of column ${table.display}.${column.value} from $from to $to"
    case SchemaOperation.AddUniqueKey(_, table, columns) =>
      s"Add unique key (${columns.map(_.value).mkString(", ")}) to ${table.display}"
    case SchemaOperation.DropIndex(_, table, columns) =>
      val keys = columns.map(column => column.name.value + (if column.descending then " DESC" else ""))
      s"Drop index (${keys.mkString(", ")}) of ${table.display}; queries that used it may become slower"
    case SchemaOperation.DropUniqueKey(_, table, columns) =>
      s"Drop unique key (${columns.map(_.value).mkString(", ")}) of ${table.display}; its columns may then hold duplicates"
    case SchemaOperation.ChangeCheck(_, table, _, column, _, to) =>
      s"${if to.isEmpty then "Remove" else "Set"} the check of column ${table.display}.${column.value}"
    case SchemaOperation.CreateIndex(_, table, columns) =>
      s"Create index (${columns.map(_.name.value).mkString(", ")}) on ${table.display}"
    case SchemaOperation.AddForeignKey(_, table, columns, referenced, _, _) =>
      s"Add foreign key (${columns.map(_.value).mkString(", ")}) from ${table.display} to ${referenced.display}"
    case SchemaOperation.SetNotNull(_, table, _, column)  => s"Make column ${table.display}.${column.value} required"
    case SchemaOperation.DropNotNull(_, table, _, column) => s"Make column ${table.display}.${column.value} optional"
    case SchemaOperation.DropColumn(_, table, _, column) => s"Drop column ${table.display}.${column.value} and its data"
    case SchemaOperation.DropTables(tables)              =>
      s"Drop tables ${tables.map(_.table.display).mkString(", ")} and their data"
    case SchemaOperation.DropSequence(_, sequence) => s"Drop sequence ${sequence.display}"
