package com.anjunar.hibernateddl.hibernate

import com.anjunar.hibernateddl.hibernate.annotation.{SchemaId, SecondaryTableId}
import jakarta.persistence.*
import org.hibernate.annotations.{JdbcTypeCode, NaturalId, OnDelete, OnDeleteAction}
import org.hibernate.`type`.SqlTypes

import scala.compiletime.uninitialized
import java.lang.{Long as JavaLong}

@Entity
@SchemaId("bbbbbbbb")
@Table(indexes = Array(new Index(columnList = "code", options = "WHERE code IS NOT NULL")))
class Unsupported:
  @Id @SchemaId("0a1b2c3d") var id: JavaLong = uninitialized
  @SchemaId("1a1b2c3d") @ManyToOne @OnDelete(action = OnDeleteAction.CASCADE) var customer: LegacyCustomer =
    uninitialized
  @SchemaId("2a1b2c3d") @Embedded var folder: Folder = uninitialized
  @SchemaId("3a1b2c3d") var code: String = uninitialized
  @SchemaId("5a1b2c3d") var tags: Array[String] = uninitialized
  @SchemaId("6a1b2c3d") @Embedded @JdbcTypeCode(SqlTypes.JSON) var guarded: Guarded = uninitialized
