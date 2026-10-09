package com.anjunar.hibernateddl.core

/** An ascending bigint sequence, as Hibernate uses for generated keys. Only the start and the
  * increment are modeled; everything else keeps the database's defaults.
  */
final case class SequenceModel(id: SchemaId, name: QualifiedName, start: Long = 1, increment: Long = 1)
