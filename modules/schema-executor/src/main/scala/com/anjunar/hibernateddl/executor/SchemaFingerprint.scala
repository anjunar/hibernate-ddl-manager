package com.anjunar.hibernateddl.executor

import com.anjunar.hibernateddl.core.*
import java.io.{ByteArrayOutputStream, DataOutputStream}
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/** Canonical SHA-256 identity of a complete schema model.
  * Table, column and foreign key ordering is irrelevant; stable IDs and all physical metadata
  * matter. Foreign keys are appended after all tables and only when there are any, so models
  * without them keep the fingerprints that existing histories store.
  */
object SchemaFingerprint:
  def of(model: SchemaModel): String =
    val bytes = new ByteArrayOutputStream()
    val out = new DataOutputStream(bytes)
    string(out, "hibernate-ddl-model-v1")
    val tables = model.tables.sortBy(_.id.value)
    out.writeInt(tables.size)
    tables.foreach { table =>
      string(out, table.id.value)
      string(out, table.name.name.value)
      optionalIdentifier(out, table.name.schema)
      optionalIdentifier(out, table.name.catalog)
      val columns = table.columns.sortBy(_.id.value)
      out.writeInt(columns.size)
      columns.foreach { column =>
        string(out, column.id.value)
        string(out, column.name.value)
        column.dataType match
          case SqlType.Varchar(length) =>
            string(out, "varchar")
            out.writeInt(length)
          case SqlType.Timestamp(precision) =>
            string(out, "timestamp")
            out.writeInt(precision)
          case SqlType.TimestampWithTimeZone(precision) =>
            string(out, "timestamptz")
            out.writeInt(precision)
          case SqlType.Integer => string(out, "integer")
          case SqlType.BigInt => string(out, "bigint")
          case SqlType.Boolean => string(out, "boolean")
          case SqlType.Text => string(out, "text")
          case SqlType.Uuid => string(out, "uuid")
        out.writeBoolean(column.nullable)
      }
      ids(out, table.primaryKey)
    }
    given Ordering[Vector[String]] = Ordering.Implicits.seqOrdering[Vector, String]
    val foreignKeys = tables.flatMap(table => table.foreignKeys.sortBy(_.columns.map(_.value)).map(table.id -> _))
    if foreignKeys.nonEmpty then
      string(out, "foreign-keys")
      out.writeInt(foreignKeys.size)
      foreignKeys.foreach { (tableId, key) =>
        string(out, tableId.value)
        ids(out, key.columns)
        string(out, key.referencedTable.value)
        ids(out, key.referencedColumns)
      }
    out.flush()
    MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray)
      .map(byte => f"${byte & 0xff}%02x").mkString

  private def ids(out: DataOutputStream, values: Vector[SchemaId]): Unit =
    out.writeInt(values.size)
    values.foreach(id => string(out, id.value))

  private def optionalIdentifier(out: DataOutputStream, identifier: Option[SqlIdentifier]): Unit =
    out.writeBoolean(identifier.nonEmpty)
    identifier.foreach(value => string(out, value.value))

  private def string(out: DataOutputStream, value: String): Unit =
    val bytes = value.getBytes(StandardCharsets.UTF_8)
    out.writeInt(bytes.length)
    out.write(bytes)
