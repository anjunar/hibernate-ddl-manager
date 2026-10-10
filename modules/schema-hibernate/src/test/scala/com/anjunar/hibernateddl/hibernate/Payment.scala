package com.anjunar.hibernateddl.hibernate

import com.anjunar.hibernateddl.hibernate.annotation.{SchemaId, SecondaryTableId}
import jakarta.persistence.*
import org.hibernate.annotations.{JdbcTypeCode, NaturalId, OnDelete, OnDeleteAction}
import org.hibernate.`type`.SqlTypes

import scala.compiletime.uninitialized
import java.lang.{Integer as JavaInteger}
import java.lang.{Long as JavaLong}

/** TABLE_PER_CLASS with an abstract root: no root table, one sequence for the whole hierarchy. */
@Entity
@Inheritance(strategy = InheritanceType.TABLE_PER_CLASS)
@SchemaId("6f708193")
abstract class Payment:
  @Id @GeneratedValue @SchemaId("0a1b2c3d") var id: JavaLong = uninitialized
  @SchemaId("1b2c3d4e") var amount: JavaInteger = uninitialized
