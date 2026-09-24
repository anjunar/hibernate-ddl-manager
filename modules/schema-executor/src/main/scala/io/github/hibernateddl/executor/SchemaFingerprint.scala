package io.github.hibernateddl.executor

import io.github.hibernateddl.core.*
import java.io.{ByteArrayOutputStream, DataOutputStream}
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/** Canonical SHA-256 identity of the complete snapshot, including its revision.
  * Table and column ordering is irrelevant; stable IDs and all physical metadata matter.
  */
object SchemaFingerprint:
  def of(snapshot: SchemaSnapshot): String = BinaryFingerprint.digest { out =>
    BinaryFingerprint.string(out, "hibernate-ddl-snapshot-v2")
    out.writeInt(snapshot.formatVersion)
    out.writeLong(snapshot.revision)
    val tables = snapshot.model.tables.sortBy(_.id.value)
    out.writeInt(tables.size)
    tables.foreach { table =>
      BinaryFingerprint.string(out, table.id.value)
      BinaryFingerprint.string(out, table.name.name.value)
      optionalIdentifier(out, table.name.schema)
      optionalIdentifier(out, table.name.catalog)
      val columns = table.columns.sortBy(_.id.value)
      out.writeInt(columns.size)
      columns.foreach { column =>
        BinaryFingerprint.string(out, column.id.value)
        BinaryFingerprint.string(out, column.name.value)
        column.dataType match
          case SqlType.Varchar(length) =>
            BinaryFingerprint.string(out, "varchar")
            out.writeInt(length)
          case SqlType.Integer => BinaryFingerprint.string(out, "integer")
          case SqlType.BigInt => BinaryFingerprint.string(out, "bigint")
          case SqlType.Boolean => BinaryFingerprint.string(out, "boolean")
          case SqlType.Text => BinaryFingerprint.string(out, "text")
        out.writeBoolean(column.nullable)
      }
      out.writeInt(table.primaryKey.size)
      table.primaryKey.foreach(id => BinaryFingerprint.string(out, id.value))
    }
  }

  private def optionalIdentifier(out: DataOutputStream, identifier: Option[SqlIdentifier]): Unit =
    out.writeBoolean(identifier.nonEmpty)
    identifier.foreach(value => BinaryFingerprint.string(out, value.value))

private[executor] object BinaryFingerprint:
  def string(out: DataOutputStream, value: String): Unit =
    val bytes = value.getBytes(StandardCharsets.UTF_8)
    out.writeInt(bytes.length)
    out.write(bytes)

  def digest(write: DataOutputStream => Unit): String =
    val bytes = new ByteArrayOutputStream()
    val out = new DataOutputStream(bytes)
    write(out)
    out.flush()
    MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray)
      .map(byte => f"${byte & 0xff}%02x").mkString
