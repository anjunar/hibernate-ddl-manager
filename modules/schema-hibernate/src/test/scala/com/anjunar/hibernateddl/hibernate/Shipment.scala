package com.anjunar.hibernateddl.hibernate

import com.anjunar.hibernateddl.hibernate.annotation.{SchemaId, SecondaryTableId}
import jakarta.persistence.*
import org.hibernate.annotations.{JdbcTypeCode, NaturalId, OnDelete, OnDeleteAction}
import org.hibernate.`type`.SqlTypes

import scala.compiletime.uninitialized
import java.lang.{Long as JavaLong}
import java.time.Instant

/** Plain indexes, one of them descending and composite; the unique one is a unique key. */
@Entity
@SchemaId("7e8f90a1")
@Table(indexes =
  Array(
    new Index(columnList = "placedat"),
    new Index(columnList = "status, placedat desc"),
    new Index(columnList = "number", unique = true)
  )
)
class Shipment:
  @Id @SchemaId("0a1b2c3d") var id: JavaLong = uninitialized
  @SchemaId("1b2c3d4e") var placedAt: Instant = uninitialized
  @SchemaId("2c3d4e5f") var status: String = uninitialized
  @SchemaId("3d4e5f60") var number: String = uninitialized
