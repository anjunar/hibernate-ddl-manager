package com.anjunar.hibernateddl.executor

import com.anjunar.hibernateddl.core.*

/** Which data checks a preview runs: none, or whether at least one problematic row exists. */
enum DataChecks:
  case Skip, Existence

/** `includeExactCounts` counts affected rows as well, within the same timeouts. */
final case class PreviewOptions(dataChecks: DataChecks = DataChecks.Existence, includeExactCounts: Boolean = false)

enum CheckStatus:
  /** The checked condition holds in the state read. */
  case Passed
  /** A blocker was found. */
  case Failed
  /** The plan does not need the check. */
  case NotApplicable
  /** Not selected, or not run after an earlier failure. */
  case NotRun
  /** Could not be decided, for example after a timeout, without privileges or for an unsupported form. */
  case Inconclusive

enum PreviewOutcome:
  /** A complete plan without blockers, and every required check passed. */
  case Ready
  /** A blocker was found. */
  case Blocked
  /** No blocker was found, but a required check is open. */
  case Incomplete

/** A number of rows. Unknown is never zero. */
enum RowCount:
  case Exact(rows: Long)
  case Estimate(rows: Long)
  case Unknown

final case class PreviewCheck(
    code: String,
    description: String,
    status: CheckStatus,
    required: Boolean,
    step: Option[Int] = None,
    subject: Option[String] = None,
    details: Vector[String] = Vector.empty,
    rows: RowCount = RowCount.Unknown
)

object PreviewCheck:
  val HistoryReadable = "HISTORY_READABLE"
  val HistoryConsistent = "HISTORY_CONSISTENT"
  val SchemaMatches = "SCHEMA_MATCHES"
  val NamesFree = "NAMES_FREE"
  val RelationsAbsent = "RELATIONS_ABSENT"
  val NoNulls = "NO_NULLS"
  val NoDuplicates = "NO_DUPLICATES"
  val ReferencesResolve = "REFERENCES_RESOLVE"
  val CheckHolds = "CHECK_HOLDS"
  val RowEstimate = "ROW_ESTIMATE"

/** A plan problem, tied to the step and stable ID it concerns. */
final case class PreviewFinding(code: String, message: String, step: Option[Int] = None, subject: Option[String] = None)

/** One step in execution order. SQL shows placeholders and their types, never the values. */
final case class PreviewStep(
    number: Int,
    kind: String,
    description: String,
    subjects: Vector[String],
    sql: String,
    parameterTypes: Vector[String],
    risk: Option[String],
    approval: Option[String],
    approved: Boolean
)

final case class PreviewLock(table: String, mode: String)

/** The result of a preview. Text and JSON are rendered from it and show the same findings. */
final case class PreviewReport(
    format: Int,
    createdAt: java.time.Instant,
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
    else if checks.exists(check => check.required && Set(CheckStatus.Inconclusive, CheckStatus.NotRun).contains(check.status))
    then PreviewOutcome.Incomplete
    else PreviewOutcome.Ready

object PreviewReport:
  val Format: Int = 1

  def approval(value: Approval): String = value match
    case Approval.Drop(id) => s"drop:${id.value}"
    case Approval.RenameBack(id) => s"rename-back:${id.value}"
    case Approval.Revert(revision) => s"revert:$revision"

  /** The steps of a plan, numbered from 1. */
  def steps(plan: MigrationPlan): Vector[PreviewStep] =
    plan.steps.zipWithIndex.map { (step, index) =>
      step match
        case PlanStep.Statement(operation, sql, approval, approved) =>
          PreviewStep(index + 1, "ddl", describe(operation), subjects(operation), sql, Vector.empty,
            Some(operation.risk.toString), approval.map(this.approval), approved)
        case PlanStep.Fill(backfill, column, statement) =>
          PreviewStep(index + 1, "fill", s"Fill the NULLs of column '${column.value}' with backfill '${backfill.id}'",
            Vector(column.value), statement.sql, statement.parameters.map(parameterType), None, None, true)
        case PlanStep.NoNulls(columnId, column, query, _) =>
          PreviewStep(index + 1, "null check", s"Check that no row of $column holds NULL", Vector(columnId.value), query,
            Vector.empty, None, None, true)
    }

  private def parameterType(value: AnyRef): String = value match
    case _: String => "text"
    case _: java.lang.Long => "bigint"
    case _: java.math.BigDecimal => "numeric"
    case _: java.lang.Double => "double precision"
    case _: java.lang.Boolean => "boolean"
    case _: java.util.UUID => "uuid"
    case _: java.time.LocalDate => "date"
    case _: java.time.LocalTime => "time"
    case _: java.time.LocalDateTime => "timestamp"
    case _: java.time.OffsetDateTime => "timestamp with time zone"
    case other => other.getClass.getSimpleName

  private def subjects(operation: SchemaOperation): Vector[String] = operation match
    case SchemaOperation.CreateSequence(sequence) => Vector(sequence.id.value)
    case SchemaOperation.RenameSequence(id, _, _) => Vector(id.value)
    case SchemaOperation.CreateTable(table) => Vector(table.id.value)
    case SchemaOperation.AddColumn(_, _, column) => Vector(column.id.value)
    case SchemaOperation.RenameTable(id, _, _) => Vector(id.value)
    case SchemaOperation.RenameColumn(_, _, id, _, _) => Vector(id.value)
    case SchemaOperation.AddUniqueKey(id, _, _) => Vector(id.value)
    case SchemaOperation.ChangeCheck(_, _, id, _, _, _) => Vector(id.value)
    case SchemaOperation.CreateIndex(id, _, _) => Vector(id.value)
    case SchemaOperation.AddForeignKey(id, _, _, _, _) => Vector(id.value)
    case SchemaOperation.SetNotNull(_, _, id, _) => Vector(id.value)
    case SchemaOperation.DropNotNull(_, _, id, _) => Vector(id.value)
    case SchemaOperation.DropColumn(_, _, id, _) => Vector(id.value)
    case SchemaOperation.DropTables(tables) => tables.map(_.tableId.value)
    case SchemaOperation.DropSequence(id, _) => Vector(id.value)

  def describe(operation: SchemaOperation): String = operation match
    case SchemaOperation.CreateSequence(sequence) => s"Create sequence ${sequence.name.display}"
    case SchemaOperation.RenameSequence(_, from, to) => s"Rename sequence ${from.display} to ${to.name.value}"
    case SchemaOperation.CreateTable(table) => s"Create table ${table.name.display}"
    case SchemaOperation.AddColumn(_, table, column) => s"Add column ${column.name.value} to ${table.display}"
    case SchemaOperation.RenameTable(_, from, to) => s"Rename table ${from.display} to ${to.name.value}"
    case SchemaOperation.RenameColumn(_, table, _, from, to) => s"Rename column ${table.display}.${from.value} to ${to.value}"
    case SchemaOperation.AddUniqueKey(_, table, columns) => s"Add unique key (${columns.map(_.value).mkString(", ")}) to ${table.display}"
    case SchemaOperation.ChangeCheck(_, table, _, column, _, to) =>
      s"${if to.isEmpty then "Remove" else "Set"} the check of column ${table.display}.${column.value}"
    case SchemaOperation.CreateIndex(_, table, columns) => s"Create index (${columns.map(_.name.value).mkString(", ")}) on ${table.display}"
    case SchemaOperation.AddForeignKey(_, table, columns, referenced, _) =>
      s"Add foreign key (${columns.map(_.value).mkString(", ")}) from ${table.display} to ${referenced.display}"
    case SchemaOperation.SetNotNull(_, table, _, column) => s"Make column ${table.display}.${column.value} required"
    case SchemaOperation.DropNotNull(_, table, _, column) => s"Make column ${table.display}.${column.value} optional"
    case SchemaOperation.DropColumn(_, table, _, column) => s"Drop column ${table.display}.${column.value} and its data"
    case SchemaOperation.DropTables(tables) => s"Drop tables ${tables.map(_.table.display).mkString(", ")} and their data"
    case SchemaOperation.DropSequence(_, sequence) => s"Drop sequence ${sequence.display}"

/** Text and versioned JSON renderings of one report; neither adds or drops a finding. */
object PreviewRendering:
  def text(report: PreviewReport): String =
    val out = new StringBuilder
    def line(value: String = ""): Unit = out.append(value).append('\n')
    line(s"Result: ${report.outcome.toString.toUpperCase} for the state read")
    line(s"Mode: ${report.mode.fold("unknown")(_.toString)}")
    line(s"Revision: ${report.revision}")
    line(s"Target fingerprint: ${report.targetFingerprint}")
    if !report.complete then line("The plan is incomplete; it cannot run.")
    if report.steps.nonEmpty then
      line()
      line("Steps:")
      report.steps.foreach { step =>
        val approval = step.approval.fold("")(value => s" [approval $value${if step.approved then "" else ", missing"}]")
        line(s"  ${step.number}. ${step.description}$approval")
        line(s"     ${step.sql}")
        if step.parameterTypes.nonEmpty then line(s"     parameters: ${step.parameterTypes.mkString(", ")}")
      }
    line()
    line(s"Allowed risks: ${report.allowedRisks.mkString(", ")}")
    line(s"Missing approvals: ${if report.missingApprovals.isEmpty then "none" else report.missingApprovals.mkString(", ")}")
    if report.findings.nonEmpty then
      line()
      line("Findings:")
      report.findings.foreach { finding =>
        line(s"  [${finding.code}]${finding.step.fold("")(step => s" step $step")} ${finding.message}")
      }
    line()
    line("Checks:")
    report.checks.foreach { check =>
      val rows = check.rows match
        case RowCount.Exact(count) => s", $count rows (counted)"
        case RowCount.Estimate(count) => s", about $count rows (estimated)"
        case RowCount.Unknown => ""
      line(s"  ${check.status} ${check.code}${if check.required then "" else " (optional)"}: ${check.description}$rows")
      check.details.foreach(detail => line(s"    $detail"))
    }
    if report.locks.nonEmpty then
      line()
      line("Locks the migration will take:")
      report.locks.foreach(lock => line(s"  ${lock.mode} on ${lock.table}"))
    report.notes.foreach { note =>
      line()
      line(note)
    }
    line()
    line("The migration checks the state again under its locks.")
    out.toString

  def json(report: PreviewReport): String =
    def string(value: String): String =
      val escaped = new StringBuilder("\"")
      value.foreach {
        case '"' => escaped.append("\\\"")
        case '\\' => escaped.append("\\\\")
        case '\n' => escaped.append("\\n")
        case '\r' => escaped.append("\\r")
        case '\t' => escaped.append("\\t")
        case c if c < ' ' => escaped.append(f"\\u${c.toInt}%04x")
        case c => escaped.append(c)
      }
      escaped.append('"').toString
    def optional(value: Option[String]): String = value.fold("null")(string)
    def optionalNumber(value: Option[Int]): String = value.fold("null")(_.toString)
    def strings(values: Vector[String]): String = values.map(string).mkString("[", ",", "]")
    def obj(fields: (String, String)*): String = fields.map((key, value) => s"${string(key)}:$value").mkString("{", ",", "}")
    def rows(count: RowCount): String = count match
      case RowCount.Exact(value) => obj("kind" -> string("exact"), "rows" -> value.toString)
      case RowCount.Estimate(value) => obj("kind" -> string("estimate"), "rows" -> value.toString)
      case RowCount.Unknown => obj("kind" -> string("unknown"), "rows" -> "null")
    obj(
      "format" -> report.format.toString,
      "createdAt" -> string(report.createdAt.toString),
      "outcome" -> string(report.outcome.toString),
      "mode" -> optional(report.mode.map(_.toString)),
      "revision" -> report.revision.toString,
      "previousFingerprint" -> optional(report.previousFingerprint),
      "targetFingerprint" -> string(report.targetFingerprint),
      "complete" -> report.complete.toString,
      "steps" -> report.steps.map { step =>
        obj("number" -> step.number.toString, "kind" -> string(step.kind), "description" -> string(step.description),
          "subjects" -> strings(step.subjects), "sql" -> string(step.sql), "parameterTypes" -> strings(step.parameterTypes),
          "risk" -> optional(step.risk), "approval" -> optional(step.approval), "approved" -> step.approved.toString)
      }.mkString("[", ",", "]"),
      "allowedRisks" -> strings(report.allowedRisks),
      "approvals" -> obj("required" -> strings(report.requiredApprovals), "present" -> strings(report.presentApprovals),
        "missing" -> strings(report.missingApprovals)),
      "locks" -> report.locks.map(lock => obj("table" -> string(lock.table), "mode" -> string(lock.mode))).mkString("[", ",", "]"),
      "findings" -> report.findings.map { finding =>
        obj("code" -> string(finding.code), "message" -> string(finding.message), "step" -> optionalNumber(finding.step),
          "subject" -> optional(finding.subject))
      }.mkString("[", ",", "]"),
      "checks" -> report.checks.map { check =>
        obj("code" -> string(check.code), "description" -> string(check.description), "status" -> string(check.status.toString),
          "required" -> check.required.toString, "step" -> optionalNumber(check.step), "subject" -> optional(check.subject),
          "details" -> strings(check.details), "rows" -> rows(check.rows))
      }.mkString("[", ",", "]"),
      "notes" -> strings(report.notes)
    )
