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
  val NoDependents = "NO_DEPENDENTS"
  val DropTargetFound = "DROP_TARGET_FOUND"
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
    approved: Boolean,
    typeChange: Option[PreviewTypeChange] = None
)

/** What a type change does, apart from its SQL. Keeping the values follows from the rule; a
  * rewrite of the table and its indexes is `possible` or `expected` and holds the locks longer.
  * `affected` lists the unique keys, indexes and check the database rebuilds with the column.
  */
final case class PreviewTypeChange(
    column: String,
    from: String,
    to: String,
    rule: String,
    valuesPreserved: Boolean,
    rewrite: String,
    affected: Vector[String]
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
          PreviewStep(index + 1, "ddl", describe(operation) + alsoRemoved(plan, operation), subjects(operation),
            bound.getOrElse(index, sql), Vector.empty, Some(operation.risk.toString), approval.map(this.approval), approved,
            typeChange(plan, operation))
        case PlanStep.Fill(backfill, column, statement) =>
          PreviewStep(index + 1, "fill", s"Fill the NULLs of column '${column.value}' with backfill '${backfill.id}'",
            Vector(column.value), statement.sql, statement.parameters.map(parameterType), None, None, true)
        case PlanStep.NoNulls(columnId, column, query, _) =>
          PreviewStep(index + 1, "null check", s"Check that no row of $column holds NULL", Vector(columnId.value), query,
            Vector.empty, None, None, true)
    }

  /** What a dropped column or table takes with it: its unique keys, indexes and foreign keys,
    * which get no steps and no approvals of their own.
    */
  private def alsoRemoved(plan: MigrationPlan, operation: SchemaOperation): String =
    def structures(table: TableModel, touches: Vector[SchemaId] => Boolean): Vector[String] =
      def names(ids: Vector[SchemaId]) = ids.map(id => table.columns.find(_.id == id).get.name.value).mkString("(", ", ", ")")
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
        tables.flatMap(dropped => plan.previous.tables.find(_.id == dropped.tableId).toVector.flatMap { table =>
          structures(table, _ => true).map(structure => s"$structure of ${table.name.display}")
        })
      case _ => Vector.empty
    if removed.isEmpty then "" else s", which also removes ${removed.mkString(", ")}"

  private def typeChange(plan: MigrationPlan, operation: SchemaOperation): Option[PreviewTypeChange] = operation match
    case SchemaOperation.ChangeColumnType(tableId, _, columnId, _, from, to) =>
      val (rule, rewrite) = TypeChangeRules.classify(from, to) match
        case TypeChange.Widening(rule, rewrite) => (rule, rewrite)
        // Unreachable: a plan with any other change is refused before it has steps.
        case _ => ("not a supported widening", TableRewrite.Expected)
      val affected = plan.target.tables.find(_.id == tableId).toVector.flatMap { table =>
        def names(ids: Vector[SchemaId]) = ids.map(id => table.columns.find(_.id == id).get.name.value).mkString("(", ", ", ")")
        table.uniqueKeys.filter(_.columns.contains(columnId)).map(key => s"unique key ${names(key.columns)}") ++
          table.indexes.filter(_.columns.exists(_.column == columnId)).map { index =>
            "index " + index.columns.map { column =>
              table.columns.find(_.id == column.column).get.name.value + (if column.descending then " DESC" else "")
            }.mkString("(", ", ", ")")
          } ++
          table.columns.find(_.id == columnId).flatMap(_.check).map(check => s"check $check")
      }
      Some(PreviewTypeChange(columnId.value, from.toString, to.toString, rule, valuesPreserved = true,
        rewrite.toString.toLowerCase, affected))
    case _ => None

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
    case SchemaOperation.ChangeColumnType(_, _, id, _, _, _) => Vector(id.value)
    case SchemaOperation.AddUniqueKey(id, _, _) => Vector(id.value)
    case SchemaOperation.DropIndex(ref, _, _) => Vector(ref.table.value)
    case SchemaOperation.DropUniqueKey(ref, _, _) => Vector(ref.table.value, ref.signature)
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
    case SchemaOperation.ChangeColumnType(_, table, _, column, from, to) =>
      s"Change the type of column ${table.display}.${column.value} from $from to $to"
    case SchemaOperation.AddUniqueKey(_, table, columns) => s"Add unique key (${columns.map(_.value).mkString(", ")}) to ${table.display}"
    case SchemaOperation.DropIndex(_, table, columns) =>
      val keys = columns.map(column => column.name.value + (if column.descending then " DESC" else ""))
      s"Drop index (${keys.mkString(", ")}) of ${table.display}; queries that used it may become slower"
    case SchemaOperation.DropUniqueKey(_, table, columns) =>
      s"Drop unique key (${columns.map(_.value).mkString(", ")}) of ${table.display}; its columns may then hold duplicates"
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
        step.typeChange.foreach { change =>
          line(s"     ${change.rule}; ${if change.valuesPreserved then "every value is kept" else "values change"}; " +
            s"rewrite of the table and its indexes ${change.rewrite}")
          if change.affected.nonEmpty then line(s"     rebuilt with the column: ${change.affected.mkString(", ")}")
        }
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
          "risk" -> optional(step.risk), "approval" -> optional(step.approval), "approved" -> step.approved.toString,
          "typeChange" -> step.typeChange.fold("null") { change =>
            obj("column" -> string(change.column), "from" -> string(change.from), "to" -> string(change.to),
              "rule" -> string(change.rule), "valuesPreserved" -> change.valuesPreserved.toString,
              "rewrite" -> string(change.rewrite), "affected" -> strings(change.affected))
          })
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
