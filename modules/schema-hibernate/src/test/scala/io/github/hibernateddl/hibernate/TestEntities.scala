package io.github.hibernateddl.hibernate

import io.github.hibernateddl.hibernate.annotation.SchemaId
import jakarta.persistence.*

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

@Entity
@SchemaId("bbbbbbbb")
class Unsupported:
  @Id @SchemaId("0a1b2c3d") var id: java.util.UUID = uninitialized
  @SchemaId("1a1b2c3d") @ManyToOne var customer: LegacyCustomer = uninitialized
  @SchemaId("2a1b2c3d") @ElementCollection var tags: java.util.Set[String] = new java.util.HashSet[String]()
  @SchemaId("3a1b2c3d") @Column(unique = true) var code: String = uninitialized

@Entity
@SchemaId("cccccccc")
class Generated:
  @Id @GeneratedValue @SchemaId("0a1b2c3d") var id: java.lang.Long = uninitialized
