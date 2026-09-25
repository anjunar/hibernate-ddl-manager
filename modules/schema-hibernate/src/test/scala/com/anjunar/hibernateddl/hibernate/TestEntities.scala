package com.anjunar.hibernateddl.hibernate

import com.anjunar.hibernateddl.hibernate.annotation.{SchemaId, SecondaryTableId}
import jakarta.persistence.*
import org.hibernate.annotations.{NaturalId, OnDelete, OnDeleteAction}

import scala.compiletime.uninitialized

@Embeddable
class Address:
  @SchemaId("5a6b7c8d") var street: String = uninitialized

@Entity
@Table(name = "Customer")
@SchemaId("7f3a9c21")
class Customer:
  @Id @SchemaId("0a1b2c3d") var id: java.lang.Long = uninitialized
  @SchemaId("f34e45b6") @Column(name = "nick_name", length = 80) var nickName: String = uninitialized
  @SchemaId("1c2d3e4f") @Column(name = "\"LoginName\"", nullable = false) var login: String = uninitialized
  @SchemaId("2d3e4f5a") @Column(nullable = false) var active: Boolean = false
  @SchemaId("6b7c8d9e") var visits: java.lang.Integer = uninitialized
  @SchemaId("3e4f5a6b") @Embedded var billing: Address = uninitialized
  @SchemaId("4f5a6b7c") @Embedded var shipping: Address = uninitialized
  @Transient var cache: String = uninitialized

/** The same entity before a refactoring: other class, table and column names, same IDs. */
@Entity
@Table(name = "customer")
@SchemaId("7f3a9c21")
class LegacyCustomer:
  @Id @SchemaId("0a1b2c3d") var id: java.lang.Long = uninitialized
  @SchemaId("f34e45b6") @Column(name = "nick_name", length = 80) var nickName: String = uninitialized

@Entity
@Table(name = "client")
@SchemaId("7f3a9c21")
class Client:
  @Id @SchemaId("0a1b2c3d") var id: java.lang.Long = uninitialized
  @SchemaId("f34e45b6") @Column(name = "alias", length = 80) var alias: String = uninitialized

@Entity
@SchemaId("USER")
class Unidentified:
  @Id var id: java.lang.Long = uninitialized
  @SchemaId("abc") var name: String = uninitialized

@Entity
@SchemaId("aaaaaaaa")
class DuplicateA:
  @Id @SchemaId("0a1b2c3d") var id: java.lang.Long = uninitialized
  @SchemaId("0a1b2c3d") var name: String = uninitialized

@Entity
@SchemaId("aaaaaaaa")
class DuplicateB:
  @Id @SchemaId("0a1b2c3d") var id: java.lang.Long = uninitialized

/** A typical entity: generated UUID key, timestamps with and without time zone. */
@Entity
@SchemaId("9d8e7f60")
class Purchase:
  @Id @GeneratedValue @SchemaId("0a1b2c3d") var id: java.util.UUID = uninitialized
  @SchemaId("1b2c3d4e") @Column(nullable = false) var createdAt: java.time.LocalDateTime = uninitialized
  @SchemaId("2c3d4e5f") var paidAt: java.time.Instant = uninitialized
  @SchemaId("3d4e5f60") var shippedAt: java.time.OffsetDateTime = uninitialized
  @SchemaId("4e5f6071") @Column(secondPrecision = 3) var deliveredAt: java.time.LocalDateTime = uninitialized

/** Associations: a required reference, one without a constraint and a self-reference. */
@Entity
@SchemaId("5c6d7e8f")
class Invoice:
  @Id @SchemaId("0a1b2c3d") var id: java.lang.Long = uninitialized
  @SchemaId("1d2e3f40") @ManyToOne(optional = false) var customer: LegacyCustomer = uninitialized
  @SchemaId("2e3f4051") @ManyToOne @JoinColumn(foreignKey = new ForeignKey(value = ConstraintMode.NO_CONSTRAINT))
  var reviewer: LegacyCustomer = uninitialized
  @SchemaId("3f405162") @ManyToOne var correction: Invoice = uninitialized

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

/** Plain indexes, one of them descending and composite; the unique one is a unique key. */
@Entity
@SchemaId("7e8f90a1")
@Table(indexes = Array(
  new Index(columnList = "placedat"),
  new Index(columnList = "status, placedat desc"),
  new Index(columnList = "number", unique = true)
))
class Shipment:
  @Id @SchemaId("0a1b2c3d") var id: java.lang.Long = uninitialized
  @SchemaId("1b2c3d4e") var placedAt: java.time.Instant = uninitialized
  @SchemaId("2c3d4e5f") var status: String = uninitialized
  @SchemaId("3d4e5f60") var number: String = uninitialized

enum Status extends java.lang.Enum[Status]:
  case Draft, Sent, Paid

enum Quote extends java.lang.Enum[Quote]:
  case Plain, `it's`

/** Enums by name and by ordinal; Hibernate guards both with a CHECK constraint. */
@Entity
@SchemaId("90a1b2c3")
class Letter:
  @Id @SchemaId("0a1b2c3d") var id: java.lang.Long = uninitialized
  @SchemaId("1b2c3d4e") @Enumerated(EnumType.STRING) var status: Status = uninitialized
  @SchemaId("2c3d4e5f") @Enumerated(EnumType.ORDINAL) var priority: Status = uninitialized
  @SchemaId("3d4e5f60") @Enumerated(EnumType.STRING) @Column(name = "\"Stage\"", length = 10) var stage: Status = uninitialized

/** Checks the adapter cannot read: a quote in an enum value and a hand-written @Check. */
@Entity
@SchemaId("a1b2c3d4")
class OddChecks:
  @Id @SchemaId("0a1b2c3d") var id: java.lang.Long = uninitialized
  @SchemaId("1b2c3d4e") @Enumerated(EnumType.STRING) var quote: Quote = uninitialized
  @SchemaId("2c3d4e5f") @Column(check = Array(new CheckConstraint(constraint = "amount <> 5"))) var amount: Integer = uninitialized

@Embeddable
class Line:
  @SchemaId("6a7b8c9d") var text: String = uninitialized
  @SchemaId("7b8c9d0e") var amount: java.lang.Integer = uninitialized

/** The inverse side of a many-to-many owns no table and needs no @SchemaId. */
@Entity
@SchemaId("b2c3d4e5")
class Label:
  @Id @SchemaId("0a1b2c3d") var id: java.lang.Long = uninitialized
  @ManyToMany(mappedBy = "labels") var articles: java.util.Set[Article] = new java.util.HashSet[Article]()

/** Collections in their own tables: basic values, embeddables, enums and a many-to-many. */
@Entity
@SchemaId("c3d4e5f6")
class Article:
  @Id @SchemaId("0a1b2c3d") var id: java.lang.Long = uninitialized
  @SchemaId("1b2c3d4e") @ElementCollection @CollectionTable(name = "article_keyword")
  var keywords: java.util.Set[String] = new java.util.HashSet[String]()
  @SchemaId("2c3d4e5f") @ElementCollection var lines: java.util.List[Line] = new java.util.ArrayList[Line]()
  @SchemaId("3d4e5f60") @ManyToMany var labels: java.util.Set[Label] = new java.util.HashSet[Label]()
  @SchemaId("4e5f6071") @ElementCollection @Enumerated(EnumType.STRING)
  var statuses: java.util.Set[Status] = new java.util.HashSet[Status]()

/** Two collections that Hibernate's default naming puts into the same join table. */
@Entity
@SchemaId("d4e5f607")
class Crowded:
  @Id @SchemaId("0a1b2c3d") var id: java.lang.Long = uninitialized
  @SchemaId("1b2c3d4e") @ManyToMany var favorites: java.util.Set[Label] = new java.util.HashSet[Label]()
  @SchemaId("2c3d4e5f") @OneToMany var pinned: java.util.Set[Label] = new java.util.HashSet[Label]()

@Embeddable
class Price:
  @SchemaId("8c9d0e1f") var amount: java.lang.Integer = uninitialized
  @SchemaId("9d0e1f20") var currency: String = uninitialized

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

@Embeddable
class Folder:
  @SchemaId("a0b1c2d3") @ElementCollection var files: java.util.Set[String] = new java.util.HashSet[String]()

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

/** Large objects: @Lob text, bytes and JDBC LOBs, all stored as PostgreSQL large objects. */
@Entity
@SchemaId("a2b3c4d5")
class Document:
  @Id @SchemaId("0a1b2c3d") var id: java.lang.Long = uninitialized
  @SchemaId("1b2c3d4e") @Lob var body: String = uninitialized
  @SchemaId("2c3d4e5f") @Lob var scan: Array[Byte] = uninitialized
  @SchemaId("3d4e5f60") var attachment: java.sql.Blob = uninitialized
  @SchemaId("4e5f6071") var notes: java.sql.Clob = uninitialized

/** A secondary table without @SecondaryTableId, and one that names no secondary table. */
@Entity
@SchemaId("4b5c6d7e")
@SecondaryTable(name = "unlabelled_details")
@SecondaryTableId(table = "elsewhere", value = "5c6d7e8f")
class Unlabelled:
  @Id @SchemaId("0a1b2c3d") var id: java.lang.Long = uninitialized
  @SchemaId("1b2c3d4e") @Column(table = "unlabelled_details") var extra: String = uninitialized

/** SINGLE_TABLE, the default: one table with a discriminator; subclasses add their columns. */
@Entity
@SchemaId("1a2b3c4e")
class Animal:
  @Id @SchemaId("0a1b2c3d") var id: java.lang.Long = uninitialized
  @SchemaId("1b2c3d4e") var name: String = uninitialized

@Entity
@SchemaId("2b3c4d5f")
class Cat extends Animal:
  @SchemaId("2c3d4e5f") var lives: java.lang.Integer = uninitialized

@Entity
@SchemaId("3c4d5e60")
class Dog extends Animal:
  @SchemaId("3d4e5f60") var breed: String = uninitialized

/** JOINED: the subclass table's key is a foreign key to the parent table. */
@Entity
@Inheritance(strategy = InheritanceType.JOINED)
@SchemaId("4d5e6f71")
class Vehicle:
  @Id @SchemaId("0a1b2c3d") var id: java.lang.Long = uninitialized
  @SchemaId("1b2c3d4e") var wheels: java.lang.Integer = uninitialized

@Entity
@SchemaId("5e6f7082")
class Car extends Vehicle:
  @SchemaId("2c3d4e5f") var seats: java.lang.Integer = uninitialized

/** TABLE_PER_CLASS with an abstract root: no root table, one sequence for the whole hierarchy. */
@Entity
@Inheritance(strategy = InheritanceType.TABLE_PER_CLASS)
@SchemaId("6f708193")
abstract class Payment:
  @Id @GeneratedValue @SchemaId("0a1b2c3d") var id: java.lang.Long = uninitialized
  @SchemaId("1b2c3d4e") var amount: java.lang.Integer = uninitialized

@Entity
@SchemaId("708192a4")
class CardPayment extends Payment:
  @SchemaId("2c3d4e5f") var card: String = uninitialized

@Entity
@SchemaId("8192a3b5")
class TransferPayment extends Payment:
  @SchemaId("3d4e5f60") var iban: String = uninitialized

/** Further basic types as Hibernate maps them on PostgreSQL. */
@Entity
@SchemaId("8f90a1b2")
class Measurement:
  @Id @SchemaId("0a1b2c3d") var id: java.lang.Short = uninitialized
  @SchemaId("1b2c3d4e") var day: java.time.LocalDate = uninitialized
  @SchemaId("2c3d4e5f") var takenAt: java.time.LocalTime = uninitialized
  @SchemaId("3d4e5f60") @Column(precision = 10, scale = 2) var price: java.math.BigDecimal = uninitialized
  @SchemaId("4e5f6071") var total: java.math.BigInteger = uninitialized
  @SchemaId("5f607182") var ratio: java.lang.Double = uninitialized
  @SchemaId("60718293") var weight: java.lang.Float = uninitialized
  @SchemaId("718293a4") var unit: java.lang.Character = uninitialized
  @SchemaId("8293a4b5") var raw: Array[Byte] = uninitialized
  @SchemaId("93a4b5c6") var level: java.lang.Byte = uninitialized

@Entity
@SchemaId("bbbbbbbb")
@Table(indexes = Array(new Index(columnList = "code", options = "WHERE code IS NOT NULL")))
class Unsupported:
  @Id @SchemaId("0a1b2c3d") var id: java.lang.Long = uninitialized
  @SchemaId("4a1b2c3d") @org.hibernate.annotations.JdbcTypeCode(org.hibernate.`type`.SqlTypes.JSON) var document: String = uninitialized
  @SchemaId("1a1b2c3d") @ManyToOne @OnDelete(action = OnDeleteAction.CASCADE) var customer: LegacyCustomer = uninitialized
  @SchemaId("2a1b2c3d") @Embedded var folder: Folder = uninitialized
  @SchemaId("3a1b2c3d") var code: String = uninitialized
  @SchemaId("5a1b2c3d") var tags: Array[String] = uninitialized

/** Hibernate's default key generation: the sequence Generated_SEQ with increment 50. */
@Entity
@SchemaId("cccccccc")
class Generated:
  @Id @GeneratedValue @SchemaId("0a1b2c3d") var id: java.lang.Long = uninitialized

@Entity
@SchemaId("e5f60718")
class Ticket:
  @Id @GeneratedValue(strategy = GenerationType.IDENTITY) @SchemaId("0a1b2c3d") var id: java.lang.Long = uninitialized

@Entity
@SchemaId("f6071829")
class Voucher:
  @Id @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "voucher_gen")
  @SequenceGenerator(name = "voucher_gen", sequenceName = "voucher_numbers", allocationSize = 10, initialValue = 100)
  @SchemaId("0a1b2c3d") var id: java.lang.Long = uninitialized

/** Draws its keys from Voucher's sequence, which one sequence per entity key does not allow. */
@Entity
@SchemaId("0718293a")
class Coupon:
  @Id @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "coupon_gen")
  @SequenceGenerator(name = "coupon_gen", sequenceName = "voucher_numbers", allocationSize = 10, initialValue = 100)
  @SchemaId("0a1b2c3d") var id: java.lang.Long = uninitialized

/** DDL options, which Hibernate appends verbatim, on a column, the table, a unique key, a
  * foreign key and a sequence.
  */
@Entity
@SchemaId("9a0b1c2d")
@Table(options = "WITH (fillfactor = 70)",
  uniqueConstraints = Array(new UniqueConstraint(columnNames = Array("code"), options = "DEFERRABLE")))
class Tuned:
  @Id @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "tuned_gen")
  @SequenceGenerator(name = "tuned_gen", sequenceName = "tuned_seq", options = "CACHE 20")
  @SchemaId("0a1b2c3d") var id: java.lang.Long = uninitialized
  @SchemaId("1b2c3d4e") @Column(options = "CHECK (score > 0)") var score: Integer = uninitialized
  @SchemaId("2c3d4e5f") var code: String = uninitialized
  @SchemaId("3d4e5f60") @ManyToOne @JoinColumn(foreignKey = new ForeignKey(options = "DEFERRABLE"))
  var parent: Tuned = uninitialized

