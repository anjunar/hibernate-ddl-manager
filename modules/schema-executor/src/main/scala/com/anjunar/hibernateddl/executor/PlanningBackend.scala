package com.anjunar.hibernateddl.executor

import com.anjunar.hibernateddl.core.*
import java.sql.Connection

/** What planning needs from a backend: checks and SQL rendering without a connection. */
trait PlanningBackend extends SchemaDialect:
  def validate(model: SchemaModel): Vector[String]
  /** A query whose single row and column counts the rows in which the column is NULL. */
  def nullCount(table: QualifiedName, column: SqlIdentifier): String
  /** An UPDATE that fills the column's NULLs and binds every constant as a parameter. */
  def renderFill(fill: NullFill): Either[Vector[String], BoundStatement]
