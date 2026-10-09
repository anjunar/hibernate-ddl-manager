package com.anjunar.hibernateddl.hibernate

import com.anjunar.hibernateddl.hibernate.annotation.{SchemaId, SecondaryTableId}
import jakarta.persistence.*
import org.hibernate.annotations.{JdbcTypeCode, NaturalId, OnDelete, OnDeleteAction}
import org.hibernate.`type`.SqlTypes

import scala.compiletime.uninitialized

@Entity
@SchemaId("2b3c4d5f")
class Cat extends Animal:
  @SchemaId("2c3d4e5f") var lives: java.lang.Integer = uninitialized
