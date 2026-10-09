package com.anjunar.hibernateddl.hibernate

import com.anjunar.hibernateddl.hibernate.annotation.{SchemaId, SecondaryTableId}
import jakarta.persistence.*
import org.hibernate.annotations.{JdbcTypeCode, NaturalId, OnDelete, OnDeleteAction}
import org.hibernate.`type`.SqlTypes

import scala.compiletime.uninitialized

/** A secondary table with its own stable ID. */
@Entity
@SchemaId("29384a5b")
@SecondaryTable(name = "profile_details")
@SecondaryTableId(table = "profile_details", value = "3a4b5c6d")
class Profile:
  @Id @SchemaId("0a1b2c3d") var id: java.lang.Long = uninitialized
  @SchemaId("1b2c3d4e") var name: String = uninitialized
  @SchemaId("2c3d4e5f") @Column(table = "profile_details") var bio: String = uninitialized
  @SchemaId("3d4e5f60") @Column(table = "profile_details") var essay: String = uninitialized
