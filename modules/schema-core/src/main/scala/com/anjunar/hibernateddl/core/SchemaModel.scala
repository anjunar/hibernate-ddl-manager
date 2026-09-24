package com.anjunar.hibernateddl.core

/** A logical identity retained across physical database renames. */
final case class SchemaId(value: String):
  require(value != null && value.trim.nonEmpty, "Schema ID must not be blank")

/** An unquoted physical identifier. Dialects are responsible for quoting it. */
final case class SqlIdentifier(value: String):
  require(value != null && value.trim.nonEmpty, "SQL identifier must not be blank")

final case class QualifiedName(
    name: SqlIdentifier,
    schema: Option[SqlIdentifier] = None,
    catalog: Option[SqlIdentifier] = None
)

/** Timestamp precisions count fractional-second digits. */
enum SqlType:
  case Varchar(length: Int)
  case Timestamp(precision: Int)
  case TimestampWithTimeZone(precision: Int)
  case Integer, BigInt, Boolean, Text, Uuid

final case class ColumnModel(
    id: SchemaId,
    name: SqlIdentifier,
    dataType: SqlType,
    nullable: Boolean = true
)

/** A foreign key references the primary key of a table in the same model. Columns and
  * referenced columns pair up in key order; both are IDs, so renames keep the key intact.
  * A table has at most one foreign key per column list, which is its identity.
  */
final case class ForeignKeyModel(
    columns: Vector[SchemaId],
    referencedTable: SchemaId,
    referencedColumns: Vector[SchemaId]
):
  def display: String = columns.map(_.value).mkString("(", ", ", ")")

/** A unique constraint over column IDs in key order; the column list is its identity. */
final case class UniqueKeyModel(columns: Vector[SchemaId]):
  def display: String = columns.map(_.value).mkString("(", ", ", ")")

/** The primary key lists column IDs in key order, so column renames keep it intact. */
final case class TableModel(
    id: SchemaId,
    name: QualifiedName,
    columns: Vector[ColumnModel],
    primaryKey: Vector[SchemaId] = Vector.empty,
    foreignKeys: Vector[ForeignKeyModel] = Vector.empty,
    uniqueKeys: Vector[UniqueKeyModel] = Vector.empty
)

final case class SchemaModel(tables: Vector[TableModel])

/** Validation is independent of any particular database dialect. */
object SchemaValidation:
  def validate(model: SchemaModel): Vector[String] =
    val errors = Vector.newBuilder[String]
    val ids = model.tables.flatMap(table => table.id +: table.columns.map(_.id))
    ids.groupBy(identity).foreach { (id, occurrences) =>
      if occurrences.size > 1 then errors += s"Duplicate stable ID '${id.value}'"
    }
    model.tables.groupBy(_.name).foreach { (name, tables) =>
      if tables.size > 1 then
        errors += s"Duplicate physical table name '${displayName(name)}'"
    }
    model.tables.foreach { table =>
      table.columns.groupBy(_.name).foreach { (name, columns) =>
        if columns.size > 1 then
          errors += s"Duplicate physical column name '${name.value}' in table '${table.id.value}'"
      }
      table.columns.foreach { column =>
        column.dataType match
          case SqlType.Varchar(length) if length <= 0 =>
            errors += s"Column '${column.id.value}' has invalid VARCHAR length $length; expected a positive length"
          case SqlType.Timestamp(precision) if precision < 0 =>
            errors += s"Column '${column.id.value}' has invalid TIMESTAMP precision $precision; expected zero or more"
          case SqlType.TimestampWithTimeZone(precision) if precision < 0 =>
            errors += s"Column '${column.id.value}' has invalid TIMESTAMP precision $precision; expected zero or more"
          case _ => ()
      }
      val columns = table.columns.map(c => c.id -> c).toMap
      if table.primaryKey.distinct.size != table.primaryKey.size then
        errors += s"Primary key of table '${table.id.value}' lists a column more than once"
      table.primaryKey.foreach { id =>
        columns.get(id) match
          case None =>
            errors += s"Primary key of table '${table.id.value}' references unknown column '${id.value}'"
          case Some(column) if column.nullable =>
            errors += s"Primary key column '${id.value}' of table '${table.id.value}' must not be nullable"
          case Some(_) => ()
      }
      table.foreignKeys.groupBy(_.columns).foreach { (_, keys) =>
        if keys.size > 1 then
          errors += s"Table '${table.id.value}' has more than one foreign key on ${keys.head.display}"
      }
      table.foreignKeys.foreach(key => errors ++= validateForeignKey(model, table, key))
      table.uniqueKeys.groupBy(_.columns).foreach { (_, keys) =>
        if keys.size > 1 then
          errors += s"Table '${table.id.value}' has more than one unique key on ${keys.head.display}"
      }
      table.uniqueKeys.foreach { key =>
        val label = s"Unique key ${key.display} of table '${table.id.value}'"
        if key.columns.isEmpty then errors += s"$label has no columns"
        if key.columns.distinct.size != key.columns.size then errors += s"$label lists a column more than once"
        key.columns.filterNot(columns.contains).foreach { id =>
          errors += s"$label references unknown column '${id.value}'"
        }
      }
    }
    errors.result().distinct.sorted

  private def validateForeignKey(model: SchemaModel, table: TableModel, key: ForeignKeyModel): Vector[String] =
    val label = s"Foreign key ${key.display} of table '${table.id.value}'"
    val referenced = model.tables.find(_.id == key.referencedTable)
    val structure = Vector(
      Option.when(key.columns.isEmpty)(s"$label has no columns"),
      Option.when(key.columns.distinct.size != key.columns.size)(s"$label lists a column more than once"),
      Option.when(referenced.isEmpty)(s"$label references unknown table '${key.referencedTable.value}'"),
      referenced.flatMap { target =>
        Option.when(target.primaryKey.isEmpty || key.referencedColumns != target.primaryKey)(
          s"$label must reference the primary key of table '${target.id.value}'")
      }
    ).flatten ++ key.columns.filterNot(id => table.columns.exists(_.id == id)).map { id =>
      s"$label references unknown column '${id.value}'"
    }
    if structure.nonEmpty then structure
    else
      val target = referenced.get
      key.columns.zip(key.referencedColumns).flatMap { (columnId, referencedId) =>
        val column = table.columns.find(_.id == columnId).get
        val referencedColumn = target.columns.find(_.id == referencedId).get
        Option.when(column.dataType != referencedColumn.dataType)(
          s"$label column '${columnId.value}' has type ${column.dataType} but references type ${referencedColumn.dataType}")
      }

  private[core] def displayName(name: QualifiedName): String =
    (name.catalog.toVector ++ name.schema.toVector :+ name.name).map(_.value).mkString(".")
