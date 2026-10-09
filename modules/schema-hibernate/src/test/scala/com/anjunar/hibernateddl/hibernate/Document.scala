package com.anjunar.hibernateddl.hibernate

import com.anjunar.hibernateddl.hibernate.annotation.{SchemaId, SecondaryTableId}
import jakarta.persistence.*
import org.hibernate.annotations.{JdbcTypeCode, NaturalId, OnDelete, OnDeleteAction}
import org.hibernate.`type`.SqlTypes

import scala.compiletime.uninitialized

/** Large objects: @Lob text, bytes and JDBC LOBs, all stored as PostgreSQL large objects. */
@Entity
@SchemaId("a2b3c4d5")
class Document:
  @Id @SchemaId("0a1b2c3d") var id: java.lang.Long = uninitialized
  @SchemaId("1b2c3d4e") @Lob var body: String = uninitialized
  @SchemaId("2c3d4e5f") @Lob var scan: Array[Byte] = uninitialized
  @SchemaId("3d4e5f60") var attachment: java.sql.Blob = uninitialized
  @SchemaId("4e5f6071") var notes: java.sql.Clob = uninitialized
