package com.anjunar.hibernateddl.postgresql

import com.anjunar.hibernateddl.core.*

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

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
      case SchemaOperation.CreateSequence(sequence) => validateName(sequence.name, "new sequence")
      case SchemaOperation.RenameSequence(_, from, to) =>
        validateName(from, "source sequence") ++
          validateName(to, "target sequence") ++
          Option.when(from.schema != to.schema)("Renaming a sequence across schemas is not supported.").toVector
      case SchemaOperation.CreateTable(table) =>
        validateTable(table, "new table") ++
          Option.when(table.foreignKeys.nonEmpty)(
            "The new table's foreign keys must be separate operations after all tables exist."
          ).toVector ++
          Option.when(table.indexes.nonEmpty)("The new table's indexes must be separate operations.").toVector
      case SchemaOperation.ChangeCheck(_, table, _, column, from, to) =>
        validateName(table, "table") ++
          validateIdentifier(column, "checked column") ++
          to.toVector.flatMap(validateCheck(_, "new")) ++
          Option.when(from.isEmpty && to.isEmpty)("A check change needs a previous or a new check.").toVector
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
      case SchemaOperation.SetNotNull(_, table, _, column) =>
        validateName(table, "table") ++ validateIdentifier(column, "required column")
      case SchemaOperation.DropNotNull(_, table, _, column) =>
        validateName(table, "table") ++ validateIdentifier(column, "optional column")
      case SchemaOperation.DropColumn(_, table, _, column) =>
        validateName(table, "table") ++ validateIdentifier(column, "dropped column")
      case SchemaOperation.DropTables(tables) =>
        tables.flatMap(table => validateName(table.table, "dropped table")) ++
          Option.when(tables.isEmpty)("Dropping tables needs at least one table.").toVector
      case SchemaOperation.DropSequence(_, sequence) => validateName(sequence, "dropped sequence")

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
    validateIdentifier(column.name, label) ++ column.check.toVector.flatMap(validateCheck(_, label)) ++ (column.dataType match
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

  private def validateCheck(check: ColumnCheck, label: String): Vector[String] = check match
    case ColumnCheck.AllowedValues(values) =>
      Option.when(values.exists(_.contains('\u0000')))(s"The $label check allows a value containing NUL.").toVector
    case ColumnCheck.Range(_, _) => Vector.empty

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
      case SchemaOperation.CreateSequence(sequence) =>
        s"CREATE SEQUENCE ${qualified(sequence.name)} AS bigint START WITH ${sequence.start} INCREMENT BY ${sequence.increment};"
      case SchemaOperation.RenameSequence(_, from, to) =>
        s"ALTER SEQUENCE ${qualified(from)} RENAME TO ${quoted(to.name)};"
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
      case SchemaOperation.ChangeCheck(_, table, columnId, column, from, to) =>
        val changes = from.map(check => s"DROP CONSTRAINT ${quoted(checkName(columnId, check))}").toVector ++
          to.map(check => "ADD " + renderCheck(columnId, column, check))
        s"ALTER TABLE ${qualified(table)} ${changes.mkString(", ")};"
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
      case SchemaOperation.SetNotNull(_, table, _, column) =>
        s"ALTER TABLE ${qualified(table)} ALTER COLUMN ${quoted(column)} SET NOT NULL;"
      case SchemaOperation.DropNotNull(_, table, _, column) =>
        s"ALTER TABLE ${qualified(table)} ALTER COLUMN ${quoted(column)} DROP NOT NULL;"
      // Without CASCADE: anything outside the model that depends on the dropped object, such
      // as a view, makes the statement fail instead of disappearing with it.
      case SchemaOperation.DropColumn(_, table, _, column) =>
        s"ALTER TABLE ${qualified(table)} DROP COLUMN ${quoted(column)};"
      case SchemaOperation.DropTables(tables) =>
        s"DROP TABLE ${tables.map(table => qualified(table.table)).mkString(", ")};"
      case SchemaOperation.DropSequence(_, sequence) =>
        s"DROP SEQUENCE ${qualified(sequence)};"

  private def renderColumn(column: ColumnModel): String =
    val nullable = if column.nullable then "" else " NOT NULL"
    val identity = if column.identity then " GENERATED BY DEFAULT AS IDENTITY" else ""
    val check = column.check.fold("")(value => " " + renderCheck(column.id, column.name, value))
    s"${quoted(column.name)} ${renderType(column.dataType)}$nullable$identity$check"

  /** An UPDATE that fills a column's NULLs. Every constant is a JDBC parameter, cast to the type
    * it fills, which it fits without rounding. Concatenation uses `||`, which is NULL when any part
    * is; the assignment to the column fails, rather than truncates, when a value is too long.
    */
  private[postgresql] def renderFill(fill: NullFill): Either[Vector[String], (String, Vector[AnyRef])] =
    def names(value: FillValue): Vector[String] = value match
      case FillValue.Literal(BackfillLiteral.Text(text), _) =>
        Option.when(text.contains('\u0000'))("A text constant must not contain NUL.").toVector
      case FillValue.Literal(_, _) => Vector.empty
      case FillValue.Column(name) => validateIdentifier(name, "source column")
      case FillValue.Coalesce(values) => values.flatMap(names)
      case FillValue.Concat(values) => values.flatMap(names)
    val errors = validateName(fill.table, "filled table") ++ validateIdentifier(fill.column, "filled column") ++ names(fill.value)
    if errors.nonEmpty then Left(errors.map(message => s"Backfill '${fill.backfillId}': $message"))
    else
      val parameters = Vector.newBuilder[AnyRef]
      def expression(value: FillValue): String = value match
        case FillValue.Literal(literal, as) =>
          parameters += parameter(literal)
          s"CAST(? AS ${renderType(as)})"
        case FillValue.Column(name) => quoted(name)
        case FillValue.Coalesce(values) => values.map(expression).mkString("COALESCE(", ", ", ")")
        case FillValue.Concat(values) => values.map(expression).mkString("(", " || ", ")")
      val sql = s"UPDATE ${qualified(fill.table)} SET ${quoted(fill.column)} = ${expression(fill.value)} " +
        s"WHERE ${quoted(fill.column)} IS NULL"
      Right(sql -> parameters.result())

  private def parameter(literal: BackfillLiteral): AnyRef = literal match
    case BackfillLiteral.Text(value) => value
    case BackfillLiteral.WholeNumber(value) => java.lang.Long.valueOf(value)
    case BackfillLiteral.Decimal(value) => value
    case BackfillLiteral.FloatingPoint(value) => java.lang.Double.valueOf(value)
    case BackfillLiteral.Bool(value) => java.lang.Boolean.valueOf(value)
    case BackfillLiteral.Uuid(value) => value
    case BackfillLiteral.Date(value) => value
    case BackfillLiteral.Time(value) => value
    case BackfillLiteral.Timestamp(value) => value
    case BackfillLiteral.TimestampWithTimeZone(value) => value

  /** Counts the rows in which a column is NULL. */
  private[postgresql] def nullCount(table: QualifiedName, column: SqlIdentifier): String =
    s"SELECT count(*) FROM ${qualified(table)} WHERE ${quoted(column)} IS NULL"

  /** A temporary table whose columns carry exactly the given columns' checks, as this dialect
    * creates them, so that PostgreSQL can show their canonical definitions for comparison.
    */
  private[postgresql] def checkProbe(table: SqlIdentifier, columns: Vector[ColumnModel]): String =
    val definitions = columns.map(column => renderColumn(column.copy(nullable = true, identity = false)))
    s"CREATE TEMPORARY TABLE ${quoted(table)} (${definitions.mkString(", ")}) ON COMMIT DROP"

  /** Check constraints are named after the column ID and the check, so a plan can replace a
    * check without looking up its name, and column renames keep the name valid.
    */
  private[postgresql] def checkName(columnId: SchemaId, check: ColumnCheck): SqlIdentifier =
    val canonical = check match
      case ColumnCheck.AllowedValues(values) => "values" +: values
      case ColumnCheck.Range(min, max) => Vector("range", min.toString, max.toString)
    val digest = MessageDigest.getInstance("SHA-256")
    (columnId.value +: canonical).foreach { part =>
      val bytes = part.getBytes(StandardCharsets.UTF_8)
      digest.update(ByteBuffer.allocate(4).putInt(bytes.length).array())
      digest.update(bytes)
    }
    SqlIdentifier("ck_" + digest.digest().take(12).map(byte => f"${byte & 0xff}%02x").mkString)

  private def renderCheck(columnId: SchemaId, column: SqlIdentifier, check: ColumnCheck): String =
    val condition = check match
      case ColumnCheck.AllowedValues(values) =>
        s"${quoted(column)} IN (${values.map(value => "'" + value.replace("'", "''") + "'").mkString(", ")})"
      case ColumnCheck.Range(min, max) => s"${quoted(column)} BETWEEN $min AND $max"
    s"CONSTRAINT ${quoted(checkName(columnId, check))} CHECK ($condition)"

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
    case SqlType.LargeObject => "oid"
    case SqlType.Json => "jsonb"

  private def qualified(name: QualifiedName): String =
    (name.schema.toVector :+ name.name).map(quoted).mkString(".")

  private def quoted(identifier: SqlIdentifier): String =
    "\"" + identifier.value.replace("\"", "\"\"") + "\""
