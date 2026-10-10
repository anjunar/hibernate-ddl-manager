package com.anjunar.hibernateddl.hibernate

import com.anjunar.hibernateddl.hibernate.annotation.{SchemaId, SecondaryTableId}
import jakarta.persistence.*
import org.hibernate.annotations.{JdbcTypeCode, NaturalId, OnDelete, OnDeleteAction}
import org.hibernate.`type`.SqlTypes

import scala.compiletime.uninitialized
import java.lang.{Long as JavaLong}
import java.util

/** JSON documents: a map, a list, raw text and an embeddable stored as one JSON column. */
@Entity
@SchemaId("b3c4d5e6")
class Settings:
  @Id @SchemaId("0a1b2c3d") var id: JavaLong = uninitialized
  @SchemaId("1b2c3d4e") @JdbcTypeCode(SqlTypes.JSON) var values: util.Map[String, Object] = uninitialized
  @SchemaId("2c3d4e5f") @JdbcTypeCode(SqlTypes.JSON) var tags: util.List[String] = uninitialized
  @SchemaId("3d4e5f60") @JdbcTypeCode(SqlTypes.JSON) var raw: String = uninitialized
  @SchemaId("4e5f6071") @Embedded @JdbcTypeCode(SqlTypes.JSON) var preferences: Preferences = uninitialized
