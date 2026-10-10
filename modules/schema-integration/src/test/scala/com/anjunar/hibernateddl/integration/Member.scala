package com.anjunar.hibernateddl.integration

import com.anjunar.hibernateddl.core.*
import com.anjunar.hibernateddl.hibernate.annotation.SchemaId as Id
import jakarta.persistence.*

import scala.compiletime.uninitialized
import jakarta.persistence.{Id as PersistenceId}
import java.lang.{Long as JavaLong}

/** A member whose nickname is optional, as an earlier release maps it. */
@Entity
@Table(name = "member")
@Id("c1d2e3f4")
class Member:
  @PersistenceId @Id("0a1b2c3d") var id: JavaLong = uninitialized
  @Id("1b2c3d4e") var nick: String = uninitialized
