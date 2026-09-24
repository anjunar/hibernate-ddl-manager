package com.anjunar.hibernateddl.postgresql

import com.anjunar.hibernateddl.core.*
import com.anjunar.hibernateddl.executor.*

import java.sql.{Connection, PreparedStatement, ResultSet, SQLException}
import scala.util.Using

/** Transactional migration for PostgreSQL 14 and newer.
  *
  * The initial execution envelope is deliberately narrow: explicitly qualified,
  * permanent ordinary tables containing only the modeled native types,
  * nullability, a non-deferrable primary key and plain foreign keys (no actions,
  * MATCH SIMPLE, not deferrable). Defaults, identities, generated columns, custom
  * collations, inheritance, partitions, other indexes and constraints, triggers,
  * rules and RLS require a richer schema model before execution is supported.
  * Unmanaged tables outside the supplied model are allowed, but must not reference
  * a modeled table. Every modeled table is exclusively
  * locked before catalog inspection; callers must retain this transaction until
  * the migration and its history entry have both committed.
  *
  * The history table stores every applied model as jsonb, together with the executed
  * statements. A history table with another column layout was created by another version
  * and is refused, never altered.
  */
object PostgreSqlMigrationBackend extends TransactionalMigrationBackend:
  private val HistorySchema = "__hibernate_ddl"
  private val HistoryTable = "\"__hibernate_ddl\".\"schema_history\""
  // One transaction lock per database, independent of the migrated schemas.
  private val AdvisoryLockKey = 0x4844444c4d475231L
  private val HistoryColumns = Vector("revision", "previous_fingerprint", "target_fingerprint", "model", "statements")

  override def render(
      operations: Vector[SchemaOperation]
  ): Either[Vector[String], Vector[String]] = PostgreSqlDialect.render(operations)

  override def validate(model: SchemaModel): Vector[String] = validateModel(model)

  override def acquireLock(connection: Connection, options: ExecutionOptions): Unit =
    if connection.getAutoCommit then
      throw new SQLException("PostgreSQL migration execution requires an active transaction.")
    val metadata = connection.getMetaData
    if metadata.getDatabaseProductName != "PostgreSQL" || metadata.getDatabaseMajorVersion < 14 then
      throw new SQLException("This migration backend requires PostgreSQL 14 or newer.")
    if !metadata.supportsTransactions() ||
        !metadata.supportsDataDefinitionAndDataManipulationTransactions() ||
        metadata.dataDefinitionCausesTransactionCommit() ||
        metadata.dataDefinitionIgnoredInTransactions()
    then throw new SQLException("The connection does not support transactional PostgreSQL DDL.")
    if options.lockTimeoutMillis <= 0 || options.statementTimeoutMillis <= 0 then
      throw new SQLException("Migration lock and statement timeouts must be positive.")

    query(connection,
      "SELECT pg_catalog.set_config('lock_timeout', ?, true), " +
        "pg_catalog.set_config('statement_timeout', ?, true), " +
        "pg_catalog.set_config('search_path', 'pg_catalog', true)"
    ) { statement =>
      statement.setString(1, s"${options.lockTimeoutMillis}ms")
      statement.setString(2, s"${options.statementTimeoutMillis}ms")
    }(_ => ())
    val identifierLimit = query(connection,
      "SELECT pg_catalog.current_setting('max_identifier_length')"
    )(_ => ())(_.getInt(1)).head
    if identifierLimit != 63 then
      throw new SQLException("This backend requires PostgreSQL's standard 63-byte identifier limit.")
    query(connection, "SELECT pg_catalog.pg_advisory_xact_lock(?)")(
      _.setLong(1, AdvisoryLockKey)
    )(_ => ())
    ()

  override def initializeHistory(connection: Connection): Unit =
    execute(connection, "CREATE SCHEMA IF NOT EXISTS \"__hibernate_ddl\"")
    execute(connection,
      s"""CREATE TABLE IF NOT EXISTS $HistoryTable (
         |  revision BIGINT PRIMARY KEY CHECK (revision > 0),
         |  previous_fingerprint CHAR(64) NOT NULL,
         |  target_fingerprint CHAR(64) NOT NULL,
         |  model JSONB NOT NULL,
         |  statements TEXT[] NOT NULL,
         |  applied_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT pg_catalog.clock_timestamp()
         |)""".stripMargin
    )
    val columns = query(connection,
      """SELECT a.attname
        |FROM pg_catalog.pg_attribute a
        |WHERE a.attrelid = pg_catalog.to_regclass(?) AND a.attnum > 0 AND NOT a.attisdropped
        |ORDER BY a.attnum""".stripMargin
    )(_.setString(1, HistoryTable))(_.getString("attname"))
    val expected = HistoryColumns :+ "applied_at"
    if columns != expected then
      throw new SQLException(s"History table $HistoryTable has the columns ${columns.mkString(", ")}; " +
        s"expected ${expected.mkString(", ")}. It was created by another version of Hibernate DDL Manager.")

  override def readHistory(connection: Connection): Vector[HistoryEntry] =
    query(connection,
      s"SELECT revision, previous_fingerprint, target_fingerprint, CAST(model AS pg_catalog.text) AS model, statements " +
        s"FROM $HistoryTable ORDER BY revision"
    )(_ => ()) { row =>
      HistoryEntry(row.getLong("revision"), row.getString("previous_fingerprint"),
        row.getString("target_fingerprint"), row.getString("model"), strings(row, "statements"))
    }

  override def recordHistory(connection: Connection, entry: HistoryEntry): Unit =
    Using.resource(connection.prepareStatement(
      s"INSERT INTO $HistoryTable (${HistoryColumns.mkString(", ")}) VALUES (?, ?, ?, CAST(? AS pg_catalog.jsonb), ?)"
    )) { statement =>
      val statements = connection.createArrayOf("text", entry.statements.toArray[AnyRef])
      try
        statement.setLong(1, entry.revision)
        statement.setString(2, entry.previousFingerprint)
        statement.setString(3, entry.targetFingerprint)
        statement.setString(4, entry.model)
        statement.setArray(5, statements)
        if statement.executeUpdate() != 1 then
          throw new SQLException("Recording the migration did not insert exactly one history entry.")
      finally statements.free()
    }

  override def lockAndValidate(connection: Connection, expected: SchemaModel): Vector[String] =
    val validation = validateModel(expected)
    if validation.nonEmpty then validation
    else
      val tables = expected.tables.sortBy(table => qualified(table.name))
      // Lock every table before inspecting any of them. ONLY avoids recursively
      // locking an unmodeled inheritance tree, which inspection will reject.
      tables.foreach { table =>
        execute(connection, s"LOCK TABLE ONLY ${qualified(table.name)} IN ACCESS EXCLUSIVE MODE")
      }
      tables.flatMap(table => inspectTable(connection, table, expected)).distinct.sorted

  private def validateModel(model: SchemaModel): Vector[String] =
    val dialectErrors = model.tables.flatMap { table =>
      PostgreSqlDialect.validateTable(table).map(message => s"Table '${table.id.value}': $message")
    }
    // The history stores models as jsonb, which cannot hold NUL.
    val idErrors = model.tables.flatMap(table => table.id +: table.columns.map(_.id))
      .filter(_.value.contains('\u0000')).map(id => s"Stable ID '${id.value.replace('\u0000', '?')}' must not contain NUL.")
    val namespaceErrors = model.tables.flatMap { table =>
      Vector(
        Option.when(table.name.schema.isEmpty)(
          s"Table '${table.id.value}' must have an explicit schema for execution."
        ),
        Option.when(table.name.schema.exists(_.value == HistorySchema))(
          s"Table '${table.id.value}' uses the reserved history schema '$HistorySchema'."
        )
      ).flatten
    }
    (SchemaValidation.validate(model) ++ dialectErrors ++ idErrors ++ namespaceErrors).distinct.sorted

  private def inspectTable(connection: Connection, expected: TableModel, model: SchemaModel): Vector[String] =
    val display = qualified(expected.name)
    val relations = query(connection,
      """SELECT c.oid, c.relkind, c.relispartition, c.relpersistence, c.reloftype,
        |       c.relrowsecurity, c.relforcerowsecurity,
        |       EXISTS (SELECT 1 FROM pg_catalog.pg_inherits i
        |               WHERE i.inhrelid = c.oid OR i.inhparent = c.oid) AS has_inheritance,
        |       EXISTS (SELECT 1 FROM pg_catalog.pg_constraint k
        |               WHERE k.conrelid = c.oid AND k.contype <> 'f'
        |                 AND NOT (k.contype = 'n' AND k.convalidated)
        |                 AND NOT (k.contype = 'p' AND NOT k.condeferrable)
        |              ) AS has_constraints,
        |       EXISTS (SELECT 1 FROM pg_catalog.pg_index i
        |               WHERE i.indrelid = c.oid AND NOT i.indisprimary) AS has_indexes,
        |       EXISTS (SELECT 1 FROM pg_catalog.pg_trigger t
        |               WHERE t.tgrelid = c.oid AND NOT t.tgisinternal) AS has_triggers,
        |       EXISTS (SELECT 1 FROM pg_catalog.pg_rewrite r
        |               WHERE r.ev_class = c.oid) AS has_rules,
        |       EXISTS (SELECT 1 FROM pg_catalog.pg_policy p
        |               WHERE p.polrelid = c.oid) AS has_policies
        |FROM pg_catalog.pg_class c
        |JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace
        |WHERE n.nspname = ? AND c.relname = ?""".stripMargin
    ) { statement =>
      statement.setString(1, expected.name.schema.get.value)
      statement.setString(2, expected.name.name.value)
    } { row =>
      val features = Vector(
        Option.when(row.getString("relkind") != "r")("not an ordinary table"),
        Option.when(row.getBoolean("relispartition") || row.getBoolean("has_inheritance"))(
          "partitioning or inheritance"
        ),
        Option.when(row.getString("relpersistence") != "p")("non-permanent storage"),
        Option.when(row.getLong("reloftype") != 0)("typed table"),
        Option.when(row.getBoolean("has_constraints"))("unmodeled constraints"),
        Option.when(row.getBoolean("has_indexes"))("indexes"),
        Option.when(row.getBoolean("has_triggers"))("triggers"),
        Option.when(row.getBoolean("has_rules"))("rules"),
        Option.when(row.getBoolean("relrowsecurity") || row.getBoolean("relforcerowsecurity") ||
          row.getBoolean("has_policies"))("row-level security")
      ).flatten
      (row.getLong("oid"), features)
    }
    relations.headOption match
      case None => Vector(s"Database drift: table $display does not exist.")
      case Some((oid, features)) =>
        val errors = Vector.newBuilder[String]
        features.foreach(feature => errors += s"Table $display has unsupported $feature.")
        val actual = inspectColumns(connection, oid)
        val actualByName = actual.map(column => column.name -> column).toMap
        val expectedNames = expected.columns.map(_.name.value).toSet
        val actualNames = actualByName.keySet
        (expectedNames -- actualNames).toVector.sorted.foreach { column =>
          errors += s"Database drift: column ${quoted(column)} is missing from $display."
        }
        (actualNames -- expectedNames).toVector.sorted.foreach { column =>
          errors += s"Database drift: unexpected column ${quoted(column)} in $display."
        }
        expected.columns.foreach { column =>
          actualByName.get(column.name.value).foreach { databaseColumn =>
            val columnDisplay = s"$display.${quoted(column.name.value)}"
            if !databaseColumn.dataType.contains(column.dataType) then
              errors += s"Database drift: column $columnDisplay type is ${databaseColumn.typeDescription}; expected ${column.dataType}."
            if databaseColumn.nullable != column.nullable then
              errors += s"Database drift: column $columnDisplay nullable=${databaseColumn.nullable}; expected ${column.nullable}."
            databaseColumn.features.foreach { feature =>
              errors += s"Column $columnDisplay has unsupported $feature."
            }
          }
        }
        val expectedKey = expected.primaryKey.flatMap(id => expected.columns.find(_.id == id)).map(_.name.value)
        val actualKey = inspectPrimaryKey(connection, oid)
        if actualKey != expectedKey then
          def show(key: Vector[String]) = if key.isEmpty then "none" else key.map(quoted).mkString("(", ", ", ")")
          errors += s"Database drift: primary key of $display is ${show(actualKey)}; expected ${show(expectedKey)}."
        errors ++= compareForeignKeys(connection, oid, display, expected, model)
        errors.result()

  /** Foreign keys match by columns and referenced columns, never by constraint name. Keys
    * referencing this table must come from modeled tables, whose own check covers them.
    */
  private def compareForeignKeys(
      connection: Connection,
      oid: Long,
      display: String,
      expected: TableModel,
      model: SchemaModel
  ): Vector[String] =
    def names(table: TableModel, ids: Vector[SchemaId]) = ids.map(id => table.columns.find(_.id == id).get.name.value)
    val expectedKeys = expected.foreignKeys.map { key =>
      val referenced = model.tables.find(_.id == key.referencedTable).get
      ForeignKeyShape(names(expected, key.columns), qualified(referenced.name), names(referenced, key.referencedColumns))
    }
    val actualKeys = inspectForeignKeys(connection, oid)
    val modeledTables = model.tables.map(table => qualified(table.name)).toSet
    actualKeys.flatMap { key =>
      key.features.map(feature => s"Foreign key ${quoted(key.name)} of $display has unsupported $feature.")
    } ++ (expectedKeys diff actualKeys.map(_.shape)).map { key =>
      s"Database drift: foreign key ${key.show} is missing from $display."
    } ++ (actualKeys.map(_.shape) diff expectedKeys).map { key =>
      s"Database drift: unexpected foreign key ${key.show} in $display."
    } ++ inspectReferencingTables(connection, oid).filterNot(modeledTables.contains).map { referencing =>
      s"Table $display is referenced by a foreign key of unmodeled table $referencing."
    }

  private final case class ForeignKeyShape(columns: Vector[String], referencedTable: String, referencedColumns: Vector[String]):
    def show: String =
      s"${columns.map(quoted).mkString("(", ", ", ")")} REFERENCES $referencedTable ${referencedColumns.map(quoted).mkString("(", ", ", ")")}"

  private final case class DatabaseForeignKey(name: String, shape: ForeignKeyShape, features: Vector[String])

  private def inspectForeignKeys(connection: Connection, oid: Long): Vector[DatabaseForeignKey] =
    query(connection,
      """SELECT k.conname, k.confupdtype, k.confdeltype, k.confmatchtype, k.condeferrable, k.convalidated,
        |       n.nspname AS referenced_schema, r.relname AS referenced_table,
        |       ARRAY(SELECT CAST(a.attname AS pg_catalog.text)
        |             FROM pg_catalog.unnest(k.conkey) WITH ORDINALITY AS u(attnum, position)
        |             JOIN pg_catalog.pg_attribute a ON a.attrelid = k.conrelid AND a.attnum = u.attnum
        |             ORDER BY u.position) AS columns,
        |       ARRAY(SELECT CAST(a.attname AS pg_catalog.text)
        |             FROM pg_catalog.unnest(k.confkey) WITH ORDINALITY AS u(attnum, position)
        |             JOIN pg_catalog.pg_attribute a ON a.attrelid = k.confrelid AND a.attnum = u.attnum
        |             ORDER BY u.position) AS referenced_columns
        |FROM pg_catalog.pg_constraint k
        |JOIN pg_catalog.pg_class r ON r.oid = k.confrelid
        |JOIN pg_catalog.pg_namespace n ON n.oid = r.relnamespace
        |WHERE k.conrelid = CAST(? AS pg_catalog.oid) AND k.contype = 'f'
        |ORDER BY k.conname""".stripMargin
    )(_.setLong(1, oid)) { row =>
      val features = Vector(
        Option.when(row.getString("confupdtype") != "a")("ON UPDATE action"),
        Option.when(row.getString("confdeltype") != "a")("ON DELETE action"),
        Option.when(row.getString("confmatchtype") != "s")("MATCH FULL or PARTIAL"),
        Option.when(row.getBoolean("condeferrable"))("deferrable checking"),
        Option.when(!row.getBoolean("convalidated"))("NOT VALID state")
      ).flatten
      val referenced = quoted(row.getString("referenced_schema")) + "." + quoted(row.getString("referenced_table"))
      DatabaseForeignKey(row.getString("conname"),
        ForeignKeyShape(strings(row, "columns"), referenced, strings(row, "referenced_columns")), features)
    }

  private def inspectReferencingTables(connection: Connection, oid: Long): Vector[String] =
    query(connection,
      """SELECT DISTINCT n.nspname, c.relname
        |FROM pg_catalog.pg_constraint k
        |JOIN pg_catalog.pg_class c ON c.oid = k.conrelid
        |JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace
        |WHERE k.confrelid = CAST(? AS pg_catalog.oid) AND k.contype = 'f'""".stripMargin
    )(_.setLong(1, oid))(row => quoted(row.getString("nspname")) + "." + quoted(row.getString("relname")))

  private def strings(row: ResultSet, column: String): Vector[String] =
    val array = row.getArray(column)
    try array.getArray.asInstanceOf[Array[String]].toVector
    finally array.free()

  private final case class DatabaseColumn(
      name: String,
      dataType: Option[SqlType],
      typeDescription: String,
      nullable: Boolean,
      features: Vector[String]
  )

  private def inspectColumns(connection: Connection, oid: Long): Vector[DatabaseColumn] =
    query(connection,
      """SELECT a.attname, a.attnotnull, a.atttypmod, a.attndims, a.atthasdef,
        |       a.attidentity, a.attgenerated, a.attcollation, t.typcollation,
        |       t.typname, t.typtype, n.nspname AS type_schema
        |FROM pg_catalog.pg_attribute a
        |JOIN pg_catalog.pg_type t ON t.oid = a.atttypid
        |JOIN pg_catalog.pg_namespace n ON n.oid = t.typnamespace
        |WHERE a.attrelid = CAST(? AS pg_catalog.oid) AND a.attnum > 0 AND NOT a.attisdropped
        |ORDER BY a.attnum""".stripMargin
    )(_.setLong(1, oid)) { row =>
      val typeName = row.getString("typname")
      val typeSchema = row.getString("type_schema")
      val typmod = row.getInt("atttypmod")
      val nativeType = typeSchema == "pg_catalog" && row.getString("typtype") == "b" &&
        row.getInt("attndims") == 0
      val dataType = if !nativeType then None else typeName match
        case "int4" if typmod == -1 => Some(SqlType.Integer)
        case "int8" if typmod == -1 => Some(SqlType.BigInt)
        case "bool" if typmod == -1 => Some(SqlType.Boolean)
        case "text" if typmod == -1 => Some(SqlType.Text)
        case "uuid" if typmod == -1 => Some(SqlType.Uuid)
        case "varchar" if typmod > 4 => Some(SqlType.Varchar(typmod - 4))
        // Without an explicit precision typmod is -1, which the model never creates.
        case "timestamp" if typmod >= 0 => Some(SqlType.Timestamp(typmod))
        case "timestamptz" if typmod >= 0 => Some(SqlType.TimestampWithTimeZone(typmod))
        case _ => None
      val features = Vector(
        Option.when(row.getBoolean("atthasdef"))("default or generation expression"),
        Option.when(row.getString("attidentity").nonEmpty)("identity generation"),
        Option.when(row.getString("attgenerated").nonEmpty)("generated expression"),
        Option.when(row.getLong("attcollation") != row.getLong("typcollation"))("custom collation")
      ).flatten
      DatabaseColumn(row.getString("attname"), dataType,
        s"$typeSchema.$typeName (typmod=$typmod)", !row.getBoolean("attnotnull"), features)
    }

  /** Primary key columns in key order; INCLUDE columns are listed too and therefore never match. */
  private def inspectPrimaryKey(connection: Connection, oid: Long): Vector[String] =
    query(connection,
      """SELECT a.attname
        |FROM pg_catalog.pg_index i
        |CROSS JOIN LATERAL pg_catalog.unnest(CAST(i.indkey AS pg_catalog.int2[])) WITH ORDINALITY AS k(attnum, position)
        |JOIN pg_catalog.pg_attribute a ON a.attrelid = i.indrelid AND a.attnum = k.attnum
        |WHERE i.indrelid = CAST(? AS pg_catalog.oid) AND i.indisprimary
        |ORDER BY k.position""".stripMargin
    )(_.setLong(1, oid))(_.getString("attname"))

  private def query[A](connection: Connection, sql: String)(bind: PreparedStatement => Unit)(
      read: ResultSet => A
  ): Vector[A] =
    Using.resource(connection.prepareStatement(sql)) { statement =>
      bind(statement)
      Using.resource(statement.executeQuery()) { rows =>
        val values = Vector.newBuilder[A]
        while rows.next() do values += read(rows)
        values.result()
      }
    }

  private def execute(connection: Connection, sql: String): Unit =
    Using.resource(connection.createStatement()) { statement =>
      statement.execute(sql)
      ()
    }

  private def qualified(name: QualifiedName): String =
    (name.schema.toVector :+ name.name).map(identifier => quoted(identifier.value)).mkString(".")

  private def quoted(identifier: String): String = "\"" + identifier.replace("\"", "\"\"") + "\""
