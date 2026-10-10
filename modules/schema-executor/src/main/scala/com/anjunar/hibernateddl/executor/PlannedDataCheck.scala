package com.anjunar.hibernateddl.executor

import com.anjunar.hibernateddl.core.*
final case class PlannedDataCheck(
  code: String,
  description: String,
  step: Option[Int],
  subject: Option[String],
  query: DataQuery,
  failure: String,
  required: Boolean = true
)
