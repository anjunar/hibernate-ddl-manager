package com.anjunar.hibernateddl.core

/** A CHECK constraint on one column, as Hibernate generates for enums: the allowed values of
  * a string column, or an inclusive range of an integer column. NULL always passes.
  */
enum ColumnCheck:
  case AllowedValues(values: Vector[String])
  case Range(min: Long, max: Long)
