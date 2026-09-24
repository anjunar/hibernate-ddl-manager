package io.github.hibernateddl.executor

import io.github.hibernateddl.core.*
import java.io.{ByteArrayOutputStream, DataOutputStream}
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/** Canonical SHA-256 identity of a complete schema model.
  * Table and column ordering is irrelevant; stable IDs and all physical metadata matter.
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
          case SqlType.Integer => string(out, "integer")
          case SqlType.BigInt => string(out, "bigint")
          case SqlType.Boolean => string(out, "boolean")
          case SqlType.Text => string(out, "text")
        out.writeBoolean(column.nullable)
      }
      out.writeInt(table.primaryKey.size)
      table.primaryKey.foreach(id => string(out, id.value))
    }
    out.flush()
    MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray)
      .map(byte => f"${byte & 0xff}%02x").mkString

  private def optionalIdentifier(out: DataOutputStream, identifier: Option[SqlIdentifier]): Unit =
    out.writeBoolean(identifier.nonEmpty)
    identifier.foreach(value => string(out, value.value))

  private def string(out: DataOutputStream, value: String): Unit =
    val bytes = value.getBytes(StandardCharsets.UTF_8)
    out.writeInt(bytes.length)
    out.write(bytes)
