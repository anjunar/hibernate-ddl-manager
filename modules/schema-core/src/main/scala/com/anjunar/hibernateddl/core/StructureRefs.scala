package com.anjunar.hibernateddl.core

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/** A plain index by what defines it: its table and its columns in order, each with its
  * direction. Table and column renames keep it; another order or direction is another index.
  */
final case class IndexRef(table: SchemaId, columns: Vector[IndexColumn]):
  def display: String = IndexModel(columns).display

/** A unique key by what defines it: its table and its columns in key order. Table and column
  * renames keep it; another order is another unique key.
  */
final case class UniqueKeyRef(table: SchemaId, columns: Vector[SchemaId]):
  def display: String = UniqueKeyModel(columns).display

  /** Names this definition in an approval: a versioned checksum of its kind, table and
    * columns, never of a physical name. It has neither commas nor colons, so it fits into a
    * comma-separated list of approval entries.
    */
  def signature: String =
    val digest = MessageDigest.getInstance("SHA-256")
    (Vector("unique key", table.value, columns.size.toString) ++ columns.map(_.value)).foreach { part =>
      val bytes = part.getBytes(StandardCharsets.UTF_8)
      digest.update(ByteBuffer.allocate(4).putInt(bytes.length).array())
      digest.update(bytes)
    }
    s"u${UniqueKeyRef.SignatureFormat}-" + digest.digest().take(16).map(byte => f"${byte & 0xff}%02x").mkString

object UniqueKeyRef:
  val SignatureFormat: Int = 1
