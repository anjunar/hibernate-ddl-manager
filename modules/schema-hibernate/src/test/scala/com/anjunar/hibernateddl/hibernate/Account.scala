package com.anjunar.hibernateddl.hibernate

import com.anjunar.hibernateddl.hibernate.annotation.{SchemaId, SecondaryTableId}
import jakarta.persistence.*
import org.hibernate.annotations.{JdbcTypeCode, NaturalId, OnDelete, OnDeleteAction}
import org.hibernate.`type`.SqlTypes

import scala.compiletime.uninitialized

/** Every kind of uniqueness: a column, a composite constraint, a natural ID and a one-to-one. */
@Entity
@SchemaId("6d7e8f90")
@Table(uniqueConstraints = Array(new UniqueConstraint(columnNames = Array("tenant", "code"))))
class Account:
  @Id @SchemaId("0a1b2c3d") var id: java.lang.Long = uninitialized
  @SchemaId("1a2b3c4d") @Column(unique = true) var email: String = uninitialized
  @SchemaId("2b3c4d5e") var tenant: String = uninitialized
  @SchemaId("3c4d5e6f") var code: String = uninitialized
  @SchemaId("4d5e6f70") @NaturalId var handle: String = uninitialized
  @SchemaId("5e6f7081") @OneToOne var owner: LegacyCustomer = uninitialized
