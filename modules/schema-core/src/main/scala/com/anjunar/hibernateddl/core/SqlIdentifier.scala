package com.anjunar.hibernateddl.core

/** An unquoted physical identifier. Dialects are responsible for quoting it. */
final case class SqlIdentifier(value: String):
  require(value != null && value.trim.nonEmpty, "SQL identifier must not be blank")
