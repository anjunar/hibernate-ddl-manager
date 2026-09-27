package com.anjunar.hibernateddl.core

import munit.FunSuite

class TableCheckSuite extends FunSuite:
  private val id = ColumnModel(SchemaId("id"), SqlIdentifier("id"), SqlType.Uuid, nullable = false)
  private val state = ColumnModel(SchemaId("status"), SqlIdentifier("status"), SqlType.Varchar(24))
  private val check = TableCheck(SqlIdentifier("ck_status"), "status IN ('DRAFT', 'PUBLISHED')")
  private val table = TableModel(SchemaId("post"), QualifiedName(SqlIdentifier("post")), Vector(id, state),
    primaryKey = Vector(id.id), checks = Vector(check))
  private def model(value: TableModel) = SchemaModel(Vector(value))

  test("checks survive table creation and permit unrelated additions and widenings") {
    val create = DiffEngine.diff(SchemaModel(Vector.empty), model(table)).toOption.get
    assertEquals(create, Vector(SchemaOperation.CreateTable(table)))
    val extra = ColumnModel(SchemaId("summary"), SqlIdentifier("summary"), SqlType.Text)
    assert(DiffEngine.diff(model(table), model(table.copy(columns = table.columns :+ extra))).isRight)
    val wider = table.copy(columns = Vector(id, state.copy(dataType = SqlType.Varchar(40))))
    assert(DiffEngine.diff(model(table), model(wider)).isRight)
  }

  test("changing named checks and renaming or dropping columns require manual migration") {
    val changes = Vector(
      table.copy(checks = Vector.empty),
      table.copy(checks = Vector(check.copy(expression = "true"))),
      table.copy(checks = Vector(check.copy(name = SqlIdentifier("another_check")))),
      table.copy(columns = Vector(id)),
      table.copy(columns = Vector(id, state.copy(name = SqlIdentifier("state"))))
    )
    changes.foreach { changed =>
      val errors = DiffEngine.diff(model(table), model(changed)).swap.toOption.get
      assert(errors.exists(_.contains("manual migration")), errors.mkString("; "))
    }
  }

  test("duplicate names and unsafe or blank SQL fragments are rejected") {
    assert(SchemaValidation.validate(model(table.copy(checks = Vector(check, check)))).nonEmpty)
    Vector("", " ", "true; DROP TABLE post", "true -- comment", "true /* comment */", "true\u0000").foreach { text =>
      assert(SchemaValidation.validate(model(table.copy(checks = Vector(check.copy(expression = text))))).nonEmpty)
    }
  }
