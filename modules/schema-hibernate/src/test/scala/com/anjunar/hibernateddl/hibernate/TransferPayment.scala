package com.anjunar.hibernateddl.hibernate

import com.anjunar.hibernateddl.hibernate.annotation.{SchemaId, SecondaryTableId}
import jakarta.persistence.*
import org.hibernate.annotations.{JdbcTypeCode, NaturalId, OnDelete, OnDeleteAction}
import org.hibernate.`type`.SqlTypes

import scala.compiletime.uninitialized

@Entity
@SchemaId("8192a3b5")
class TransferPayment extends Payment:
  @SchemaId("3d4e5f60") var iban: String = uninitialized
