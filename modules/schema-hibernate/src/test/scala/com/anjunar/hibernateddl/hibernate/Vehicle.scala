package com.anjunar.hibernateddl.hibernate

import com.anjunar.hibernateddl.hibernate.annotation.{SchemaId, SecondaryTableId}
import jakarta.persistence.*
import org.hibernate.annotations.{JdbcTypeCode, NaturalId, OnDelete, OnDeleteAction}
import org.hibernate.`type`.SqlTypes

import scala.compiletime.uninitialized

/** JOINED: the subclass table's key is a foreign key to the parent table. */
@Entity
@Inheritance(strategy = InheritanceType.JOINED)
@SchemaId("4d5e6f71")
class Vehicle:
  @Id @SchemaId("0a1b2c3d") var id: java.lang.Long = uninitialized
  @SchemaId("1b2c3d4e") var wheels: java.lang.Integer = uninitialized
