package com.anjunar.hibernateddl.hibernate

import com.anjunar.hibernateddl.hibernate.annotation.{SchemaId, SecondaryTableId}
import jakarta.persistence.*
import org.hibernate.annotations.{JdbcTypeCode, NaturalId, OnDelete, OnDeleteAction}
import org.hibernate.`type`.SqlTypes

import scala.compiletime.uninitialized

/** Maps with basic, embeddable and entity values or keys, and ordered lists. */
@Entity
@SchemaId("18293a4b")
class Shelf:
  @Id @SchemaId("0a1b2c3d") var id: java.lang.Long = uninitialized
  @SchemaId("1b2c3d4e") @ElementCollection @MapKeyColumn(name = "lang")
  var titles: java.util.Map[String, String] = new java.util.HashMap[String, String]()
  @SchemaId("2c3d4e5f") @ElementCollection var prices: java.util.Map[String, Price] = new java.util.HashMap[String, Price]()
  @SchemaId("3d4e5f60") @ElementCollection @MapKeyJoinColumn(name = "label_id")
  var weights: java.util.Map[Label, Integer] = new java.util.HashMap[Label, Integer]()
  @SchemaId("4e5f6071") @ElementCollection @OrderColumn(name = "position")
  var steps: java.util.List[String] = new java.util.ArrayList[String]()
  @SchemaId("5f607182") @ManyToMany @OrderColumn var ranked: java.util.List[Label] = new java.util.ArrayList[Label]()
