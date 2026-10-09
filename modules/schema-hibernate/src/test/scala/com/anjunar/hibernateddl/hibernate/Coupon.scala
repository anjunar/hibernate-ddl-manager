package com.anjunar.hibernateddl.hibernate

import com.anjunar.hibernateddl.hibernate.annotation.{SchemaId, SecondaryTableId}
import jakarta.persistence.*
import org.hibernate.annotations.{JdbcTypeCode, NaturalId, OnDelete, OnDeleteAction}
import org.hibernate.`type`.SqlTypes

import scala.compiletime.uninitialized

/** Draws its keys from Voucher's sequence, which one sequence per entity key does not allow. */
@Entity
@SchemaId("0718293a")
class Coupon:
  @Id @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "coupon_gen")
  @SequenceGenerator(name = "coupon_gen", sequenceName = "voucher_numbers", allocationSize = 10, initialValue = 100)
  @SchemaId("0a1b2c3d") var id: java.lang.Long = uninitialized
