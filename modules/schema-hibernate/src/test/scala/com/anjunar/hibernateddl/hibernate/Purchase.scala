package com.anjunar.hibernateddl.hibernate

import com.anjunar.hibernateddl.hibernate.annotation.{SchemaId, SecondaryTableId}
import jakarta.persistence.*
import org.hibernate.annotations.{JdbcTypeCode, NaturalId, OnDelete, OnDeleteAction}
import org.hibernate.`type`.SqlTypes

import scala.compiletime.uninitialized

/** A typical entity: generated UUID key, timestamps with and without time zone. */
@Entity
@SchemaId("9d8e7f60")
class Purchase:
  @Id @GeneratedValue @SchemaId("0a1b2c3d") var id: java.util.UUID = uninitialized
  @SchemaId("1b2c3d4e") @Column(nullable = false) var createdAt: java.time.LocalDateTime = uninitialized
  @SchemaId("2c3d4e5f") var paidAt: java.time.Instant = uninitialized
  @SchemaId("3d4e5f60") var shippedAt: java.time.OffsetDateTime = uninitialized
  @SchemaId("4e5f6071") @Column(secondPrecision = 3) var deliveredAt: java.time.LocalDateTime = uninitialized
