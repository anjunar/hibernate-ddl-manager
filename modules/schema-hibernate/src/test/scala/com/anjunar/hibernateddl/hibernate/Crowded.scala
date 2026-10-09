package com.anjunar.hibernateddl.hibernate

import com.anjunar.hibernateddl.hibernate.annotation.{SchemaId, SecondaryTableId}
import jakarta.persistence.*
import org.hibernate.annotations.{JdbcTypeCode, NaturalId, OnDelete, OnDeleteAction}
import org.hibernate.`type`.SqlTypes

import scala.compiletime.uninitialized

/** Two collections that Hibernate's default naming puts into the same join table. */
@Entity
@SchemaId("d4e5f607")
class Crowded:
  @Id @SchemaId("0a1b2c3d") var id: java.lang.Long = uninitialized
  @SchemaId("1b2c3d4e") @ManyToMany var favorites: java.util.Set[Label] = new java.util.HashSet[Label]()
  @SchemaId("2c3d4e5f") @OneToMany var pinned: java.util.Set[Label] = new java.util.HashSet[Label]()
