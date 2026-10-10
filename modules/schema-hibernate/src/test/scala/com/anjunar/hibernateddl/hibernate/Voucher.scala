package com.anjunar.hibernateddl.hibernate

import com.anjunar.hibernateddl.hibernate.annotation.{SchemaId, SecondaryTableId}
import jakarta.persistence.*
import org.hibernate.annotations.{JdbcTypeCode, NaturalId, OnDelete, OnDeleteAction}
import org.hibernate.`type`.SqlTypes

import scala.compiletime.uninitialized
import java.lang.{Long as JavaLong}

@Entity
@SchemaId("f6071829")
class Voucher:
  @Id @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "voucher_gen")
  @SequenceGenerator(name = "voucher_gen", sequenceName = "voucher_numbers", allocationSize = 10, initialValue = 100)
  @SchemaId("0a1b2c3d") var id: JavaLong = uninitialized
