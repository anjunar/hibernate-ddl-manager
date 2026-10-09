package com.anjunar.hibernateddl.executor

import com.anjunar.hibernateddl.core.*

/** `includeExactCounts` counts affected rows as well, within the same timeouts. */
final case class PreviewOptions(dataChecks: DataChecks = DataChecks.Existence, includeExactCounts: Boolean = false)

