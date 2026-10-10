package com.anjunar.hibernateddl.hibernate

import com.anjunar.hibernateddl.hibernate.annotation.{SchemaId, SecondaryTableId}
import jakarta.persistence.*
import org.hibernate.annotations.{JdbcTypeCode, NaturalId, OnDelete, OnDeleteAction}
import org.hibernate.`type`.SqlTypes

import scala.compiletime.uninitialized
import java.lang.{Integer as JavaInteger}
import java.lang.{Long as JavaLong}
import java.math.{BigDecimal as JavaBigDecimal}

/** A membership before its columns grow: a longer name, a counter that outgrows INTEGER and an
  * amount with more digits before the decimal point. The status keeps its enum check, the tag its
  * unique key and the score its descending index.
  */
@Entity
@Table(name = "membership", indexes = Array(new Index(columnList = "score desc")))
@SchemaId("a7b8c9d0")
class MembershipBefore:
  @Id @SchemaId("0a1b2c3d") var id: JavaLong = uninitialized
  @SchemaId("1b2c3d4e") @Column(name = "display_name", length = 100) var displayName: String = uninitialized
  @SchemaId("2c3d4e5f") var visits: JavaInteger = uninitialized
  @SchemaId("3d4e5f60") @Column(precision = 10, scale = 2, nullable = false) var balance: JavaBigDecimal = uninitialized
  @SchemaId("4e5f6071") @Column(unique = true, length = 20) var tag: String = uninitialized
  @SchemaId("5f607182") @Enumerated(EnumType.STRING) @Column(length = 10) var status: Status = uninitialized
  @SchemaId("60718293") var score: JavaInteger = uninitialized
