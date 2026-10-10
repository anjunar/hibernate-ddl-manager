package com.anjunar.hibernateddl.hibernate

import com.anjunar.hibernateddl.hibernate.annotation.{SchemaId, SecondaryTableId}
import jakarta.persistence.*
import org.hibernate.annotations.{JdbcTypeCode, NaturalId, OnDelete, OnDeleteAction}
import org.hibernate.`type`.SqlTypes

import scala.compiletime.uninitialized
import java.lang.{Integer as JavaInteger}
import java.lang.{Long as JavaLong}

/** JOINED: the subclass table's key is a foreign key to the parent table. */
@Entity
@Inheritance(strategy = InheritanceType.JOINED)
@SchemaId("4d5e6f71")
class Vehicle:
  @Id @SchemaId("0a1b2c3d") var id: JavaLong = uninitialized
  @SchemaId("1b2c3d4e") var wheels: JavaInteger = uninitialized
