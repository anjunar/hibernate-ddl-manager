package com.anjunar.hibernateddl.hibernate

import com.anjunar.hibernateddl.hibernate.annotation.{SchemaId, SecondaryTableId}
import jakarta.persistence.*
import org.hibernate.annotations.{JdbcTypeCode, NaturalId, OnDelete, OnDeleteAction}
import org.hibernate.`type`.SqlTypes

import scala.compiletime.uninitialized
import java.lang.{Long as JavaLong}

@Entity
@SchemaId("e5f60718")
class Ticket:
  @Id @GeneratedValue(strategy = GenerationType.IDENTITY) @SchemaId("0a1b2c3d") var id: JavaLong = uninitialized
