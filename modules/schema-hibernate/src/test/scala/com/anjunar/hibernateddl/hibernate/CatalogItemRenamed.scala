package com.anjunar.hibernateddl.hibernate

import com.anjunar.hibernateddl.hibernate.annotation.{SchemaId, SecondaryTableId}
import jakarta.persistence.*
import org.hibernate.annotations.{JdbcTypeCode, NaturalId, OnDelete, OnDeleteAction}
import org.hibernate.`type`.SqlTypes

import scala.compiletime.uninitialized

/** Other names, and one of the two unique markings on sku removed: the same target. */
@Entity
@Table(name = "catalog_item", indexes = Array(new Index(name = "idx_code_renamed", columnList = "code")),
  uniqueConstraints = Array(new UniqueConstraint(name = "uk_sku_renamed", columnNames = Array("sku"))))
@SchemaId("b8c9d0e1")
class CatalogItemRenamed:
  @Id @SchemaId("0a1b2c3d") var id: java.lang.Long = uninitialized
  @SchemaId("1b2c3d4e") var code: String = uninitialized
  @SchemaId("2c3d4e5f") var sku: String = uninitialized
