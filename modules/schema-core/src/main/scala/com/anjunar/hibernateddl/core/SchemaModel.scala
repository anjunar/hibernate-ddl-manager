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
):
  def display: String = (catalog.toVector ++ schema.toVector :+ name).map(_.value).mkString(".")

/** Time and timestamp precisions count fractional-second digits. Numeric precision counts
  * all digits and scale those after the decimal point. A large object is a reference to data
  * stored outside the row, as Hibernate maps `@Lob`. Json is a JSON document in the database's
  * binary JSON type, as Hibernate maps `@JdbcTypeCode(SqlTypes.JSON)`.
  */
enum SqlType:
  case Varchar(length: Int)
  case Char(length: Int)
  case Numeric(precision: Int, scale: Int)
  case Timestamp(precision: Int)
  case TimestampWithTimeZone(precision: Int)
  case Time(precision: Int)
  case Integer, BigInt, Boolean, Text, Uuid, SmallInt, Real, DoublePrecision, Date, Binary, LargeObject, Json

/** A CHECK constraint on one column, as Hibernate generates for enums: the allowed values of
  * a string column, or an inclusive range of an integer column. NULL always passes.
  */
enum ColumnCheck:
  case AllowedValues(values: Vector[String])
  case Range(min: Long, max: Long)

final case class ColumnModel(
    id: SchemaId,
    name: SqlIdentifier,
    dataType: SqlType,
    nullable: Boolean = true,
    check: Option[ColumnCheck] = None,
    identity: Boolean = false
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

final case class IndexColumn(column: SchemaId, descending: Boolean = false)

/** A plain, non-unique B-tree index over columns in key order; the column list, including
  * each column's direction, is its identity. Unique indexes are unique keys.
  */
final case class IndexModel(columns: Vector[IndexColumn]):
  def display: String =
    columns.map(c => if c.descending then s"${c.column.value} DESC" else c.column.value).mkString("(", ", ", ")")

/** The primary key lists column IDs in key order, so column renames keep it intact. */
final case class TableModel(
    id: SchemaId,
    name: QualifiedName,
    columns: Vector[ColumnModel],
    primaryKey: Vector[SchemaId] = Vector.empty,
    foreignKeys: Vector[ForeignKeyModel] = Vector.empty,
    uniqueKeys: Vector[UniqueKeyModel] = Vector.empty,
    indexes: Vector[IndexModel] = Vector.empty
)

/** An ascending bigint sequence, as Hibernate uses for generated keys. Only the start and the
  * increment are modeled; everything else keeps the database's defaults.
  */
final case class SequenceModel(id: SchemaId, name: QualifiedName, start: Long = 1, increment: Long = 1)

/** Tables and sequences share one name space, as relations do in PostgreSQL. */
final case class SchemaModel(tables: Vector[TableModel], sequences: Vector[SequenceModel] = Vector.empty)

/** Validation is independent of any particular database dialect. */
object SchemaValidation:
  def validate(model: SchemaModel): Vector[String] =
    val errors = Vector.newBuilder[String]
    val ids = model.tables.flatMap(table => table.id +: table.columns.map(_.id)) ++ model.sequences.map(_.id)
    ids.groupBy(identity).foreach { (id, occurrences) =>
      if occurrences.size > 1 then errors += s"Duplicate stable ID '${id.value}'"
    }
    (model.tables.map(_.name) ++ model.sequences.map(_.name)).groupBy(identity).foreach { (name, relations) =>
      if relations.size > 1 then
        errors += s"Duplicate physical table or sequence name '${displayName(name)}'"
    }
    model.sequences.foreach { sequence =>
      if sequence.increment <= 0 || sequence.start < 1 then
        errors += s"Sequence '${sequence.id.value}' starts at ${sequence.start} with increment ${sequence.increment}; " +
          "expected an ascending sequence starting at 1 or later"
    }
    model.tables.foreach { table =>
      table.columns.groupBy(_.name).foreach { (name, columns) =>
        if columns.size > 1 then
          errors += s"Duplicate physical column name '${name.value}' in table '${table.id.value}'"
      }
      table.columns.foreach { column =>
        column.check.foreach(check => errors ++= validateCheck(column, check))
        if column.identity then
          if !Set[SqlType](SqlType.SmallInt, SqlType.Integer, SqlType.BigInt).contains(column.dataType) then
            errors += s"Identity column '${column.id.value}' has type ${column.dataType}; expected an integer type"
          if column.nullable then errors += s"Identity column '${column.id.value}' must not be nullable"
        column.dataType match
          case SqlType.Varchar(length) if length <= 0 =>
            errors += s"Column '${column.id.value}' has invalid VARCHAR length $length; expected a positive length"
          case SqlType.Timestamp(precision) if precision < 0 =>
            errors += s"Column '${column.id.value}' has invalid TIMESTAMP precision $precision; expected zero or more"
          case SqlType.TimestampWithTimeZone(precision) if precision < 0 =>
            errors += s"Column '${column.id.value}' has invalid TIMESTAMP precision $precision; expected zero or more"
          case SqlType.Time(precision) if precision < 0 =>
            errors += s"Column '${column.id.value}' has invalid TIME precision $precision; expected zero or more"
          case SqlType.Char(length) if length <= 0 =>
            errors += s"Column '${column.id.value}' has invalid CHAR length $length; expected a positive length"
          case SqlType.Numeric(precision, scale) if precision <= 0 || scale < 0 || scale > precision =>
            errors += s"Column '${column.id.value}' has invalid NUMERIC($precision, $scale); expected 0 <= scale <= precision and precision > 0"
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
        errors ++= validateColumnList(s"Unique key ${key.display} of table '${table.id.value}'", key.columns, columns.keySet)
      }
      table.indexes.groupBy(_.columns).foreach { (_, indexes) =>
        if indexes.size > 1 then
          errors += s"Table '${table.id.value}' has more than one index on ${indexes.head.display}"
      }
      table.indexes.foreach { index =>
        errors ++= validateColumnList(s"Index ${index.display} of table '${table.id.value}'", index.columns.map(_.column),
          columns.keySet)
      }
    }
    errors.result().distinct.sorted

  private def validateCheck(column: ColumnModel, check: ColumnCheck): Vector[String] =
    val label = s"Check of column '${column.id.value}'"
    check match
      case ColumnCheck.AllowedValues(values) =>
        val maximum = column.dataType match
          case SqlType.Varchar(length) => Some(Some(length))
          case SqlType.Char(length) => Some(Some(length))
          case SqlType.Text => Some(None)
          case _ => None
        Option.when(maximum.isEmpty)(s"$label allows values, but the column type ${column.dataType} is not a string type").toVector ++
          Option.when(values.isEmpty)(s"$label allows no value") ++
          Option.when(values.distinct.size != values.size)(s"$label lists a value more than once") ++
          maximum.flatten.toVector.flatMap { length =>
            values.filter(_.length > length).map(value => s"$label allows '$value', which is longer than $length characters")
          }
      case ColumnCheck.Range(min, max) =>
        val bounds = column.dataType match
          case SqlType.SmallInt => Some((Short.MinValue.toLong, Short.MaxValue.toLong))
          case SqlType.Integer => Some((Int.MinValue.toLong, Int.MaxValue.toLong))
          case SqlType.BigInt => Some((Long.MinValue, Long.MaxValue))
          case _ => None
        Option.when(bounds.isEmpty)(s"$label is a range, but the column type ${column.dataType} is not an integer type").toVector ++
          Option.when(min > max)(s"$label has the empty range $min to $max") ++
          bounds.filter((low, high) => min < low || max > high).map((low, high) =>
            s"$label range $min to $max exceeds the column type's range $low to $high")

  private def validateColumnList(label: String, ids: Vector[SchemaId], known: Set[SchemaId]): Vector[String] =
    Option.when(ids.isEmpty)(s"$label has no columns").toVector ++
      Option.when(ids.distinct.size != ids.size)(s"$label lists a column more than once") ++
      ids.filterNot(known.contains).map(id => s"$label references unknown column '${id.value}'")

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
