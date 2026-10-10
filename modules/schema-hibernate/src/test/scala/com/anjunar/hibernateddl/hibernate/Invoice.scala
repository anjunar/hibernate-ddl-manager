package com.anjunar.hibernateddl.hibernate

import com.anjunar.hibernateddl.hibernate.annotation.{SchemaId, SecondaryTableId}
import jakarta.persistence.*
import org.hibernate.annotations.{JdbcTypeCode, NaturalId, OnDelete, OnDeleteAction}
import org.hibernate.`type`.SqlTypes

import scala.compiletime.uninitialized
import java.lang.{Long as JavaLong}

/** Associations: a required reference, one without a constraint and a self-reference. */
@Entity
@SchemaId("5c6d7e8f")
class Invoice:
  @Id @SchemaId("0a1b2c3d") var id: JavaLong = uninitialized
  @SchemaId("1d2e3f40") @ManyToOne(optional = false) var customer: LegacyCustomer = uninitialized
  @SchemaId("2e3f4051") @ManyToOne @JoinColumn(foreignKey = new ForeignKey(value = ConstraintMode.NO_CONSTRAINT))
  var reviewer: LegacyCustomer = uninitialized
  @SchemaId("3f405162") @ManyToOne var correction: Invoice = uninitialized
