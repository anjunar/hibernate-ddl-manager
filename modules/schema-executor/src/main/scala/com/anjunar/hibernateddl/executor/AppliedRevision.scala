package com.anjunar.hibernateddl.executor

import com.anjunar.hibernateddl.core.*

/** A history entry whose model was decoded and verified as part of an unbroken chain. */
final case class AppliedRevision(entry: HistoryEntry, model: SchemaModel)
