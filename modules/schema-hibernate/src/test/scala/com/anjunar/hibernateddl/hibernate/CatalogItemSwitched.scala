package com.anjunar.hibernateddl.hibernate

import com.anjunar.hibernateddl.hibernate.annotation.{SchemaId, SecondaryTableId}
import jakarta.persistence.*
import org.hibernate.annotations.{JdbcTypeCode, NaturalId, OnDelete, OnDeleteAction}
import org.hibernate.`type`.SqlTypes

import scala.compiletime.uninitialized
import java.lang.{Long as JavaLong}

/** Code becomes unique through a unique index; sku is no longer unique. */
@Entity
@Table(name = "catalog_item", indexes = Array(new Index(name = "idx_code", columnList = "code", unique = true)))
@SchemaId("b8c9d0e1")
class CatalogItemSwitched:
  @Id @SchemaId("0a1b2c3d") var id: JavaLong = uninitialized
  @SchemaId("1b2c3d4e") var code: String = uninitialized
  @SchemaId("2c3d4e5f") var sku: String = uninitialized
