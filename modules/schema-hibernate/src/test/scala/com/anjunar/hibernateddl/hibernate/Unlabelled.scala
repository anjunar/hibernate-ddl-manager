package com.anjunar.hibernateddl.hibernate

import com.anjunar.hibernateddl.hibernate.annotation.{SchemaId, SecondaryTableId}
import jakarta.persistence.*
import org.hibernate.annotations.{JdbcTypeCode, NaturalId, OnDelete, OnDeleteAction}
import org.hibernate.`type`.SqlTypes

import scala.compiletime.uninitialized

/** A secondary table without @SecondaryTableId, and one that names no secondary table. */
@Entity
@SchemaId("4b5c6d7e")
@SecondaryTable(name = "unlabelled_details")
@SecondaryTableId(table = "elsewhere", value = "5c6d7e8f")
class Unlabelled:
  @Id @SchemaId("0a1b2c3d") var id: java.lang.Long = uninitialized
  @SchemaId("1b2c3d4e") @Column(table = "unlabelled_details") var extra: String = uninitialized
