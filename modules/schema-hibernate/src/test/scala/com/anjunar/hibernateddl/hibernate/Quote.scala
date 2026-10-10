package com.anjunar.hibernateddl.hibernate

import com.anjunar.hibernateddl.hibernate.annotation.{SchemaId, SecondaryTableId}
import jakarta.persistence.*
import org.hibernate.annotations.{JdbcTypeCode, NaturalId, OnDelete, OnDeleteAction}
import org.hibernate.`type`.SqlTypes

import scala.compiletime.uninitialized
import java.lang.Enum
import java.lang.{Integer as JavaInteger}
import java.lang.{Long as JavaLong}
import java.util
enum Quote extends Enum[Quote]:
  case Plain, `it's`

/** Enums by name and by ordinal; Hibernate guards both with a CHECK constraint. */
@Entity
@SchemaId("90a1b2c3")
class Letter:
  @Id @SchemaId("0a1b2c3d") var id: JavaLong = uninitialized
  @SchemaId("1b2c3d4e") @Enumerated(EnumType.STRING) var status: Status = uninitialized
  @SchemaId("2c3d4e5f") @Enumerated(EnumType.ORDINAL) var priority: Status = uninitialized
  @SchemaId("3d4e5f60") @Enumerated(EnumType.STRING) @Column(name = "\"Stage\"", length = 10) var stage: Status =
    uninitialized

/** Checks the adapter cannot read: a quote in an enum value and a hand-written @Check. */
@Entity
@SchemaId("a1b2c3d4")
class OddChecks:
  @Id @SchemaId("0a1b2c3d") var id: JavaLong = uninitialized
  @SchemaId("1b2c3d4e") @Enumerated(EnumType.STRING) var quote: Quote = uninitialized
  @SchemaId("2c3d4e5f") @Column(check = Array(new CheckConstraint(constraint = "amount <> 5"))) var amount: Integer =
    uninitialized

@Embeddable
class Line:
  @SchemaId("6a7b8c9d") var text: String = uninitialized
  @SchemaId("7b8c9d0e") var amount: JavaInteger = uninitialized

/** The inverse side of a many-to-many owns no table and needs no @SchemaId. */
@Entity
@SchemaId("b2c3d4e5")
class Label:
  @Id @SchemaId("0a1b2c3d") var id: JavaLong = uninitialized
  @ManyToMany(mappedBy = "labels") var articles: util.Set[Article] = new util.HashSet[Article]()

/** Collections in their own tables: basic values, embeddables, enums and a many-to-many. */
@Entity
@SchemaId("c3d4e5f6")
class Article:
  @Id @SchemaId("0a1b2c3d") var id: JavaLong = uninitialized
  @SchemaId("1b2c3d4e") @ElementCollection @CollectionTable(name = "article_keyword")
  var keywords: util.Set[String] = new util.HashSet[String]()
  @SchemaId("2c3d4e5f") @ElementCollection var lines: util.List[Line] = new util.ArrayList[Line]()
  @SchemaId("3d4e5f60") @ManyToMany var labels: util.Set[Label] = new util.HashSet[Label]()
  @SchemaId("4e5f6071") @ElementCollection @Enumerated(EnumType.STRING)
  var statuses: util.Set[Status] = new util.HashSet[Status]()
