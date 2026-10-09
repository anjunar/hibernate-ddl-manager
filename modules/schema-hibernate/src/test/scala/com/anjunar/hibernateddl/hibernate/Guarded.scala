package com.anjunar.hibernateddl.hibernate

import com.anjunar.hibernateddl.hibernate.annotation.{SchemaId, SecondaryTableId}
import jakarta.persistence.*
import org.hibernate.annotations.{JdbcTypeCode, NaturalId, OnDelete, OnDeleteAction}
import org.hibernate.`type`.SqlTypes

import scala.compiletime.uninitialized

/** Hibernate guards an enum or a NOT NULL property inside a JSON document with a table check. */
@Embeddable
class Guarded:
  @Enumerated(EnumType.STRING) var status: Status = uninitialized
  @Column(nullable = false) var name: String = uninitialized
