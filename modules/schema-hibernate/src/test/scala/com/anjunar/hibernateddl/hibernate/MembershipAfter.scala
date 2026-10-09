package com.anjunar.hibernateddl.hibernate

import com.anjunar.hibernateddl.hibernate.annotation.{SchemaId, SecondaryTableId}
import jakarta.persistence.*
import org.hibernate.annotations.{JdbcTypeCode, NaturalId, OnDelete, OnDeleteAction}
import org.hibernate.`type`.SqlTypes

import scala.compiletime.uninitialized

/** The same membership after the mapping changed: same IDs and names, wider types. */
@Entity
@Table(name = "membership", indexes = Array(new Index(columnList = "score desc")))
@SchemaId("a7b8c9d0")
class MembershipAfter:
  @Id @SchemaId("0a1b2c3d") var id: java.lang.Long = uninitialized
  @SchemaId("1b2c3d4e") @Column(name = "display_name", length = 255) var displayName: String = uninitialized
  @SchemaId("2c3d4e5f") var visits: java.lang.Long = uninitialized
  @SchemaId("3d4e5f60") @Column(precision = 14, scale = 2, nullable = false) var balance: java.math.BigDecimal = uninitialized
  @SchemaId("4e5f6071") @Column(unique = true, length = 40) var tag: String = uninitialized
  @SchemaId("5f607182") @Enumerated(EnumType.STRING) @Column(length = 20) var status: Status = uninitialized
  @SchemaId("60718293") var score: java.lang.Long = uninitialized
