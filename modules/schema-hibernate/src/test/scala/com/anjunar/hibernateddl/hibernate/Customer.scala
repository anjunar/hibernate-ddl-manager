package com.anjunar.hibernateddl.hibernate

import com.anjunar.hibernateddl.hibernate.annotation.{SchemaId, SecondaryTableId}
import jakarta.persistence.*
import org.hibernate.annotations.{JdbcTypeCode, NaturalId, OnDelete, OnDeleteAction}
import org.hibernate.`type`.SqlTypes

import scala.compiletime.uninitialized

@Entity
@Table(name = "Customer")
@SchemaId("7f3a9c21")
class Customer:
  @Id @SchemaId("0a1b2c3d") var id: java.lang.Long = uninitialized
  @SchemaId("f34e45b6") @Column(name = "nick_name", length = 80) var nickName: String = uninitialized
  @SchemaId("1c2d3e4f") @Column(name = "\"LoginName\"", nullable = false) var login: String = uninitialized
  @SchemaId("2d3e4f5a") @Column(nullable = false) var active: Boolean = false
  @SchemaId("6b7c8d9e") var visits: java.lang.Integer = uninitialized
  @SchemaId("3e4f5a6b") @Embedded var billing: Address = uninitialized
  @SchemaId("4f5a6b7c") @Embedded var shipping: Address = uninitialized
  @Transient var cache: String = uninitialized
