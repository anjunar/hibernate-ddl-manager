package com.anjunar.hibernateddl.hibernate

import com.anjunar.hibernateddl.hibernate.annotation.{SchemaId, SecondaryTableId}
import jakarta.persistence.*
import org.hibernate.annotations.{JdbcTypeCode, NaturalId, OnDelete, OnDeleteAction}
import org.hibernate.`type`.SqlTypes

import scala.compiletime.uninitialized

@Entity
@SchemaId("aaaaaaaa")
class DuplicateB:
  @Id @SchemaId("0a1b2c3d") var id: java.lang.Long = uninitialized
