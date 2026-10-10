package com.anjunar.hibernateddl.hibernate

import com.anjunar.hibernateddl.hibernate.annotation.{SchemaId, SecondaryTableId}
import jakarta.persistence.*
import org.hibernate.annotations.{JdbcTypeCode, NaturalId, OnDelete, OnDeleteAction}
import org.hibernate.`type`.SqlTypes

import scala.compiletime.uninitialized
import java.lang.{Byte as JavaByte}
import java.lang.{Character as JavaCharacter}
import java.lang.{Double as JavaDouble}
import java.lang.{Float as JavaFloat}
import java.lang.{Short as JavaShort}
import java.math.{BigDecimal as JavaBigDecimal}
import java.math.BigInteger
import java.time.LocalDate
import java.time.LocalTime

/** Further basic types as Hibernate maps them on PostgreSQL. */
@Entity
@SchemaId("8f90a1b2")
class Measurement:
  @Id @SchemaId("0a1b2c3d") var id: JavaShort = uninitialized
  @SchemaId("1b2c3d4e") var day: LocalDate = uninitialized
  @SchemaId("2c3d4e5f") var takenAt: LocalTime = uninitialized
  @SchemaId("3d4e5f60") @Column(precision = 10, scale = 2) var price: JavaBigDecimal = uninitialized
  @SchemaId("4e5f6071") var total: BigInteger = uninitialized
  @SchemaId("5f607182") var ratio: JavaDouble = uninitialized
  @SchemaId("60718293") var weight: JavaFloat = uninitialized
  @SchemaId("718293a4") var unit: JavaCharacter = uninitialized
  @SchemaId("8293a4b5") var raw: Array[Byte] = uninitialized
  @SchemaId("93a4b5c6") var level: JavaByte = uninitialized
