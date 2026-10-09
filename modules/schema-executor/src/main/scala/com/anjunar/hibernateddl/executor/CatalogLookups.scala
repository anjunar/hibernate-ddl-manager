package com.anjunar.hibernateddl.executor

import com.anjunar.hibernateddl.core.*
import java.sql.Connection

/** What only the database's catalog knows about modeled objects. Every method only reads, so a
  * preview may call it too; table and column names are those the database has before the
  * migration.
  */
trait CatalogLookups:
  /** The objects outside the model, such as views, that depend on the column and keep its type
    * from changing; each described for a message. Neither a migration nor a preview removes them.
    */
  def typeChangeBlockers(connection: Connection, table: QualifiedName, column: SqlIdentifier): Vector[String]
  /** The SQL of an operation that [[com.anjunar.hibernateddl.core.SchemaOperation.boundAtExecution]], naming the one database
    * object that matches its definition, or why there is none: no match, several, or an object
    * that depends on it, such as a foreign key.
    */
  def bindDrop(
      connection: Connection,
      operation: SchemaOperation,
      table: QualifiedName,
      columns: Vector[SqlIdentifier]
  ): Either[Vector[String], String]
