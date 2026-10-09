package com.anjunar.hibernateddl.hibernate

import com.anjunar.hibernateddl.hibernate.annotation.{SchemaId, SecondaryTableId}
import jakarta.persistence.*
import org.hibernate.annotations.{JdbcTypeCode, NaturalId, OnDelete, OnDeleteAction}
import org.hibernate.`type`.SqlTypes

import scala.compiletime.uninitialized

/** A named index and a named unique constraint whose column is also marked unique. */
@Entity
@Table(name = "catalog_item", indexes = Array(new Index(name = "idx_code", columnList = "code")),
  uniqueConstraints = Array(new UniqueConstraint(name = "uk_sku", columnNames = Array("sku"))))
@SchemaId("b8c9d0e1")
class CatalogItem:
  @Id @SchemaId("0a1b2c3d") var id: java.lang.Long = uninitialized
  @SchemaId("1b2c3d4e") var code: String = uninitialized
  @SchemaId("2c3d4e5f") @Column(unique = true) var sku: String = uninitialized
