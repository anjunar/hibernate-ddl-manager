package com.anjunar.hibernateddl.executor

import com.anjunar.hibernateddl.core.*

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
