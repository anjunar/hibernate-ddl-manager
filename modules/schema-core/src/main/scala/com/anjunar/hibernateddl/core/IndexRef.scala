package com.anjunar.hibernateddl.core

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/** A plain index by what defines it: its table and its columns in order, each with its
  * direction. Table and column renames keep it; another order or direction is another index.
  */
final case class IndexRef(table: SchemaId, columns: Vector[IndexColumn]):
  def display: String = IndexModel(columns).display
