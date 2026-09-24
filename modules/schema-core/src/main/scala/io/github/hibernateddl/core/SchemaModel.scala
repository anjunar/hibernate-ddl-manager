package io.github.hibernateddl.core

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

enum SqlType:
  case Varchar(length: Int)
  case Integer, BigInt, Boolean, Text

final case class ColumnModel(
    id: SchemaId,
    name: SqlIdentifier,
    dataType: SqlType,
    nullable: Boolean = true
)

/** The primary key lists column IDs in key order, so column renames keep it intact. */
final case class TableModel(
    id: SchemaId,
    name: QualifiedName,
    columns: Vector[ColumnModel],
    primaryKey: Vector[SchemaId] = Vector.empty
)

final case class SchemaModel(tables: Vector[TableModel])

final case class SchemaSnapshot(
    formatVersion: Int,
    revision: Long,
    model: SchemaModel
)

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
    }
    errors.result().distinct.sorted

  private[core] def displayName(name: QualifiedName): String =
    (name.catalog.toVector ++ name.schema.toVector :+ name.name).map(_.value).mkString(".")
