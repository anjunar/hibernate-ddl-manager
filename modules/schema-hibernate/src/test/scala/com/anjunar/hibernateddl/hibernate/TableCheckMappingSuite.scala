package com.anjunar.hibernateddl.hibernate

import com.anjunar.hibernateddl.core.{SqlIdentifier, TableCheck}
import com.anjunar.hibernateddl.hibernate.annotation.SchemaId
import jakarta.persistence.{CheckConstraint, Column, Entity, Id, Table}
import munit.FunSuite

import java.util.UUID

@Entity
@SchemaId("682d9ace")
@Table(name = "checked_post", schema = "public",
  check = Array(new CheckConstraint(name = "ck_publication",
    constraint = "(status = 'DRAFT' AND published_at IS NULL) OR (status = 'PUBLISHED' AND published_at IS NOT NULL)")))
class CheckedPost:
  @Id
  @SchemaId("a2473e8b")
  var id: UUID = null

  @SchemaId("cf271a06")
  @Column(nullable = false, length = 24)
  var status: String = "DRAFT"

  @SchemaId("398bfd50")
  @Column(name = "published_at")
  var publishedAt: String = null

@Entity
@SchemaId("f4971acd")
@Table(name = "unnamed_check", schema = "public", check = Array(new CheckConstraint(constraint = "id > 0")))
class UnnamedCheckedPost:
  @Id
  @SchemaId("4ac9e501")
  var id: Int = 0

class TableCheckMappingSuite extends FunSuite:
  test("a named Hibernate table check reaches the schema model unchanged") {
    val model = TestMetadata.read(classOf[CheckedPost]).toOption.get
    assertEquals(model.tables.head.checks, Vector(TableCheck(SqlIdentifier("ck_publication"),
      "(status = 'DRAFT' AND published_at IS NULL) OR (status = 'PUBLISHED' AND published_at IS NOT NULL)")))
  }

  test("unnamed checks cannot acquire an accidental unstable identity") {
    val errors = TestMetadata.read(classOf[UnnamedCheckedPost]).swap.toOption.get
    assert(errors.exists(_.contains("explicit name")), errors.mkString("; "))
  }
