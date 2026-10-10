package com.anjunar.hibernateddl.hibernate

import com.anjunar.hibernateddl.core.{SqlIdentifier, TableCheck}
import com.anjunar.hibernateddl.hibernate.annotation.SchemaId
import jakarta.persistence.{CheckConstraint, Column, Entity, Id, Table}
import munit.FunSuite

import java.util
class TableCheckMappingSuite extends FunSuite:
  test("a named Hibernate table check reaches the schema model unchanged") {
    val model = TestMetadata.read(classOf[CheckedPost]).toOption.get
    assertEquals(
      model.tables.head.checks,
      Vector(TableCheck(
        SqlIdentifier("ck_publication"),
        "(status = 'DRAFT' AND published_at IS NULL) OR (status = 'PUBLISHED' AND published_at IS NOT NULL)"
      ))
    )
  }

  test("unnamed checks cannot acquire an accidental unstable identity") {
    val errors = TestMetadata.read(classOf[UnnamedCheckedPost]).swap.toOption.get
    assert(errors.exists(_.contains("explicit name")), errors.mkString("; "))
  }
