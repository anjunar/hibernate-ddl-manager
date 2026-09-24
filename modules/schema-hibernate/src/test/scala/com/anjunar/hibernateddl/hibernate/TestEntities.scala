package com.anjunar.hibernateddl.hibernate

import com.anjunar.hibernateddl.hibernate.annotation.SchemaId
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
  @SchemaId("4a1b2c3d") @Lob var document: String = uninitialized
  @SchemaId("1a1b2c3d") @ManyToOne @OnDelete(action = OnDeleteAction.CASCADE) var customer: LegacyCustomer = uninitialized
  @SchemaId("2a1b2c3d") @ElementCollection var tags: java.util.Set[String] = new java.util.HashSet[String]()
  @SchemaId("3a1b2c3d") var code: String = uninitialized

@Entity
@SchemaId("cccccccc")
class Generated:
  @Id @GeneratedValue @SchemaId("0a1b2c3d") var id: java.lang.Long = uninitialized
