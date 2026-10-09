package com.anjunar.hibernateddl.executor

import com.anjunar.hibernateddl.core.*
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
