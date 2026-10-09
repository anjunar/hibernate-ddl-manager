package com.anjunar.hibernateddl.executor

import com.anjunar.hibernateddl.core.*

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
