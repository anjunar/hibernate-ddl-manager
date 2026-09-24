package com.anjunar.hibernateddl.postgresql

import com.anjunar.hibernateddl.core.*

import java.nio.charset.StandardCharsets

/** Pure PostgreSQL DDL rendering.
  *
  * The limit is PostgreSQL's default identifier limit of 63 UTF-8 bytes. Servers
  * compiled with a different NAMEDATALEN are deliberately outside this dialect's
  * initial scope. Validation is atomic: any invalid operation rejects the plan.
  */
object PostgreSqlDialect extends SchemaDialect:
  private val MaxIdentifierBytes = 63
  private val MaxTimestampPrecision = 6
  private val MaxNumericPrecision = 1000
  private val MaxCharacterLength = 10485760

  override def render(
      operations: Vector[SchemaOperation]
  ): Either[Vector[String], Vector[String]] =
    val diagnostics = operations.zipWithIndex.flatMap { (operation, index) =>
      validate(operation).map(message => s"Operation ${index + 1}: $message")
    }
    if diagnostics.nonEmpty then Left(diagnostics)
    else Right(operations.map(renderValidated))

  private def validate(operation: SchemaOperation): Vector[String] =
    operation match
      case SchemaOperation.CreateTable(table) =>
        validateTable(table, "new table") ++
          Option.when(table.foreignKeys.nonEmpty)(
            "The new table's foreign keys must be separate operations after all tables exist."
          ).toVector ++
          Option.when(table.indexes.nonEmpty)("The new table's indexes must be separate operations.").toVector
      case SchemaOperation.CreateIndex(_, table, columns) =>
        validateName(table, "table") ++
          columns.flatMap(column => validateIdentifier(column.name, "index column")) ++
          Option.when(columns.isEmpty)("An index needs at least one column.").toVector
      case SchemaOperation.AddUniqueKey(_, table, columns) =>
        validateName(table, "table") ++
          columns.flatMap(validateIdentifier(_, "unique key column")) ++
          Option.when(columns.isEmpty)("A unique key needs at least one column.").toVector
      case SchemaOperation.AddForeignKey(_, table, columns, referencedTable, referencedColumns) =>
        validateName(table, "table") ++
          validateName(referencedTable, "referenced table") ++
          columns.flatMap(validateIdentifier(_, "foreign key column")) ++
          referencedColumns.flatMap(validateIdentifier(_, "referenced column")) ++
          Option.when(columns.isEmpty || columns.size != referencedColumns.size)(
            "A foreign key needs at least one column and as many referenced columns."
          ).toVector
      case SchemaOperation.AddColumn(_, table, column) =>
        validateName(table, "table") ++
          validateColumn(column, "new column") ++
          Option.when(!column.nullable)("Adding a non-null column to an existing table requires a data migration.").toVector
      case SchemaOperation.RenameTable(_, from, to) =>
        validateName(from, "source table") ++
          validateName(to, "target table") ++
          Option.when(from.schema != to.schema)(
            "Renaming a table across schemas is not supported."
          ).toVector
      case SchemaOperation.RenameColumn(_, table, _, from, to) =>
        validateName(table, "table") ++
          validateIdentifier(from, "source column") ++
          validateIdentifier(to, "target column")

  /** Checks names, column types and primary key of a table as this dialect would create it. */
  private[postgresql] def validateTable(table: TableModel, label: String = "table"): Vector[String] =
    validateName(table.name, label) ++
      table.columns.flatMap(column => validateColumn(column, s"$label column")) ++
      table.primaryKey.filterNot(id => table.columns.exists(_.id == id)).map { id =>
        s"The $label primary key references unknown column '${id.value}'."
      } ++
      table.uniqueKeys.flatMap(_.columns).filterNot(id => table.columns.exists(_.id == id)).map { id =>
        s"A $label unique key references unknown column '${id.value}'."
      } ++
      Option.when(table.uniqueKeys.exists(_.columns.isEmpty))(s"A $label unique key has no columns.").toVector ++
      table.indexes.flatMap(_.columns.map(_.column)).filterNot(id => table.columns.exists(_.id == id)).map { id =>
        s"A $label index references unknown column '${id.value}'."
      } ++
      Option.when(table.indexes.exists(_.columns.isEmpty))(s"A $label index has no columns.").toVector

  private def validateColumn(column: ColumnModel, label: String): Vector[String] =
    validateIdentifier(column.name, label) ++ (column.dataType match
      case SqlType.Varchar(length) if length <= 0 || length > MaxCharacterLength =>
        Vector(s"$label has VARCHAR length $length; PostgreSQL supports 1 to $MaxCharacterLength.")
      case SqlType.Char(length) if length <= 0 || length > MaxCharacterLength =>
        Vector(s"$label has CHAR length $length; PostgreSQL supports 1 to $MaxCharacterLength.")
      case SqlType.Numeric(precision, scale) if precision <= 0 || precision > MaxNumericPrecision || scale < 0 || scale > precision =>
        Vector(s"$label has NUMERIC($precision, $scale); PostgreSQL supports precision 1 to $MaxNumericPrecision and scale 0 to precision here.")
      case SqlType.Time(precision) if precision < 0 || precision > MaxTimestampPrecision =>
        Vector(s"$label has TIME precision $precision; PostgreSQL supports 0 to $MaxTimestampPrecision.")
      case SqlType.Timestamp(precision) if precision < 0 || precision > MaxTimestampPrecision =>
        Vector(s"$label has TIMESTAMP precision $precision; PostgreSQL supports 0 to $MaxTimestampPrecision.")
      case SqlType.TimestampWithTimeZone(precision) if precision < 0 || precision > MaxTimestampPrecision =>
        Vector(s"$label has TIMESTAMP precision $precision; PostgreSQL supports 0 to $MaxTimestampPrecision.")
      case _ => Vector.empty
    )

  private def validateName(name: QualifiedName, label: String): Vector[String] =
    validateIdentifier(name.name, label) ++
      name.schema.toVector.flatMap(validateIdentifier(_, s"$label schema")) ++
      name.catalog.toVector.flatMap { catalog =>
        Vector(s"Catalog qualification is not supported for $label.") ++
          validateIdentifier(catalog, s"$label catalog")
      }

  private def validateIdentifier(
      identifier: SqlIdentifier,
      label: String
  ): Vector[String] =
    val value = identifier.value
    Vector(
      Option.when(value.isEmpty)(s"The $label identifier must not be empty."),
      Option.when(value.contains('\u0000'))(
        s"The $label identifier must not contain NUL."
      ),
      Option.when(value.getBytes(StandardCharsets.UTF_8).length > MaxIdentifierBytes)(
        s"The $label identifier exceeds the PostgreSQL limit of $MaxIdentifierBytes UTF-8 bytes."
      )
    ).flatten

  private def renderValidated(operation: SchemaOperation): String =
    operation match
      case SchemaOperation.CreateTable(table) =>
        val primaryKey = Option.when(table.primaryKey.nonEmpty) {
          val names = table.primaryKey.map(id => quoted(table.columns.find(_.id == id).get.name))
          s"PRIMARY KEY (${names.mkString(", ")})"
        }
        val uniqueKeys = table.uniqueKeys.map { key =>
          val names = key.columns.map(id => quoted(table.columns.find(_.id == id).get.name))
          s"UNIQUE (${names.mkString(", ")})"
        }
        val definitions = table.columns.map(renderColumn) ++ primaryKey ++ uniqueKeys
        s"CREATE TABLE ${qualified(table.name)} (${definitions.mkString(", ")});"
      case SchemaOperation.AddColumn(_, table, column) =>
        s"ALTER TABLE ${qualified(table)} ADD COLUMN ${renderColumn(column)};"
      case SchemaOperation.CreateIndex(_, table, columns) =>
        val keys = columns.map(column => quoted(column.name) + (if column.descending then " DESC" else ""))
        s"CREATE INDEX ON ${qualified(table)} (${keys.mkString(", ")});"
      case SchemaOperation.AddUniqueKey(_, table, columns) =>
        s"ALTER TABLE ${qualified(table)} ADD UNIQUE (${columns.map(quoted).mkString(", ")});"
      case SchemaOperation.AddForeignKey(_, table, columns, referencedTable, referencedColumns) =>
        s"ALTER TABLE ${qualified(table)} ADD FOREIGN KEY (${columns.map(quoted).mkString(", ")}) " +
          s"REFERENCES ${qualified(referencedTable)} (${referencedColumns.map(quoted).mkString(", ")});"
      case SchemaOperation.RenameTable(_, from, to) =>
        s"ALTER TABLE ${qualified(from)} RENAME TO ${quoted(to.name)};"
      case SchemaOperation.RenameColumn(_, table, _, from, to) =>
        s"ALTER TABLE ${qualified(table)} RENAME COLUMN ${quoted(from)} TO ${quoted(to)};"

  private def renderColumn(column: ColumnModel): String =
    val nullable = if column.nullable then "" else " NOT NULL"
    s"${quoted(column.name)} ${renderType(column.dataType)}$nullable"

  private def renderType(dataType: SqlType): String = dataType match
    case SqlType.Varchar(length) => s"varchar($length)"
    case SqlType.Timestamp(precision) => s"timestamp($precision)"
    case SqlType.TimestampWithTimeZone(precision) => s"timestamp($precision) with time zone"
    case SqlType.Integer => "integer"
    case SqlType.BigInt => "bigint"
    case SqlType.Boolean => "boolean"
    case SqlType.Text => "text"
    case SqlType.Uuid => "uuid"
    case SqlType.Char(length) => s"char($length)"
    case SqlType.Numeric(precision, scale) => s"numeric($precision,$scale)"
    case SqlType.Time(precision) => s"time($precision)"
    case SqlType.SmallInt => "smallint"
    case SqlType.Real => "real"
    case SqlType.DoublePrecision => "double precision"
    case SqlType.Date => "date"
    case SqlType.Binary => "bytea"

  private def qualified(name: QualifiedName): String =
    (name.schema.toVector :+ name.name).map(quoted).mkString(".")

  private def quoted(identifier: SqlIdentifier): String =
    "\"" + identifier.value.replace("\"", "\"\"") + "\""
