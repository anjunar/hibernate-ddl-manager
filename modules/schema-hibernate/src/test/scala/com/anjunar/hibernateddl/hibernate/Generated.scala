package com.anjunar.hibernateddl.hibernate

import com.anjunar.hibernateddl.hibernate.annotation.{SchemaId, SecondaryTableId}
import jakarta.persistence.*
import org.hibernate.annotations.{JdbcTypeCode, NaturalId, OnDelete, OnDeleteAction}
import org.hibernate.`type`.SqlTypes

import scala.compiletime.uninitialized

/** Hibernate's default key generation: the sequence Generated_SEQ with increment 50. */
@Entity
@SchemaId("cccccccc")
class Generated:
  @Id @GeneratedValue @SchemaId("0a1b2c3d") var id: java.lang.Long = uninitialized
