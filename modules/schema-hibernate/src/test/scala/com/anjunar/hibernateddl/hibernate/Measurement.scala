package com.anjunar.hibernateddl.hibernate

import com.anjunar.hibernateddl.hibernate.annotation.{SchemaId, SecondaryTableId}
import jakarta.persistence.*
import org.hibernate.annotations.{JdbcTypeCode, NaturalId, OnDelete, OnDeleteAction}
import org.hibernate.`type`.SqlTypes

import scala.compiletime.uninitialized

/** Further basic types as Hibernate maps them on PostgreSQL. */
@Entity
@SchemaId("8f90a1b2")
class Measurement:
  @Id @SchemaId("0a1b2c3d") var id: java.lang.Short = uninitialized
  @SchemaId("1b2c3d4e") var day: java.time.LocalDate = uninitialized
  @SchemaId("2c3d4e5f") var takenAt: java.time.LocalTime = uninitialized
  @SchemaId("3d4e5f60") @Column(precision = 10, scale = 2) var price: java.math.BigDecimal = uninitialized
  @SchemaId("4e5f6071") var total: java.math.BigInteger = uninitialized
  @SchemaId("5f607182") var ratio: java.lang.Double = uninitialized
  @SchemaId("60718293") var weight: java.lang.Float = uninitialized
  @SchemaId("718293a4") var unit: java.lang.Character = uninitialized
  @SchemaId("8293a4b5") var raw: Array[Byte] = uninitialized
  @SchemaId("93a4b5c6") var level: java.lang.Byte = uninitialized
