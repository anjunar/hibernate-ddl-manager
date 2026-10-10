package com.anjunar.hibernateddl.hibernate

import com.anjunar.hibernateddl.hibernate.annotation.{SchemaId, SecondaryTableId}
import jakarta.persistence.*
import org.hibernate.annotations.{JdbcTypeCode, NaturalId, OnDelete, OnDeleteAction}
import org.hibernate.`type`.SqlTypes

import scala.compiletime.uninitialized
import java.lang.{Long as JavaLong}
import java.util

/** Maps with basic, embeddable and entity values or keys, and ordered lists. */
@Entity
@SchemaId("18293a4b")
class Shelf:
  @Id @SchemaId("0a1b2c3d") var id: JavaLong = uninitialized
  @SchemaId("1b2c3d4e") @ElementCollection @MapKeyColumn(name = "lang")
  var titles: util.Map[String, String] = new util.HashMap[String, String]()
  @SchemaId("2c3d4e5f") @ElementCollection var prices: util.Map[String, Price] = new util.HashMap[String, Price]()
  @SchemaId("3d4e5f60") @ElementCollection @MapKeyJoinColumn(name = "label_id")
  var weights: util.Map[Label, Integer] = new util.HashMap[Label, Integer]()
  @SchemaId("4e5f6071") @ElementCollection @OrderColumn(name = "position")
  var steps: util.List[String] = new util.ArrayList[String]()
  @SchemaId("5f607182") @ManyToMany @OrderColumn var ranked: util.List[Label] = new util.ArrayList[Label]()
