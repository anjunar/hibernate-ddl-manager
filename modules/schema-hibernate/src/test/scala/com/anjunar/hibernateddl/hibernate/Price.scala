package com.anjunar.hibernateddl.hibernate

import com.anjunar.hibernateddl.hibernate.annotation.{SchemaId, SecondaryTableId}
import jakarta.persistence.*
import org.hibernate.annotations.{JdbcTypeCode, NaturalId, OnDelete, OnDeleteAction}
import org.hibernate.`type`.SqlTypes

import scala.compiletime.uninitialized
import java.lang.{Integer as JavaInteger}

@Embeddable
class Price:
  @SchemaId("8c9d0e1f") var amount: JavaInteger = uninitialized
  @SchemaId("9d0e1f20") var currency: String = uninitialized
