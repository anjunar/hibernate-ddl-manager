package com.anjunar.hibernateddl.executor

import com.anjunar.hibernateddl.core.*

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
