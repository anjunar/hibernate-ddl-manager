package com.anjunar.hibernateddl.core

/** A logical identity retained across physical database renames. */
final case class SchemaId(value: String):
  require(value != null && value.trim.nonEmpty, "Schema ID must not be blank")
