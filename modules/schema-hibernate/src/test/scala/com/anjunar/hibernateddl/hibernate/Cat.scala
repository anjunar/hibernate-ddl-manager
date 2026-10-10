package com.anjunar.hibernateddl.hibernate

import com.anjunar.hibernateddl.hibernate.annotation.{SchemaId, SecondaryTableId}
import jakarta.persistence.*
import org.hibernate.annotations.{JdbcTypeCode, NaturalId, OnDelete, OnDeleteAction}
import org.hibernate.`type`.SqlTypes

import scala.compiletime.uninitialized
import java.lang.{Integer as JavaInteger}

@Entity
@SchemaId("2b3c4d5f")
class Cat extends Animal:
  @SchemaId("2c3d4e5f") var lives: JavaInteger = uninitialized
