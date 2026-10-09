package com.anjunar.hibernateddl.core

trait SchemaDialect:
  def render(operations: Vector[SchemaOperation]): Either[Vector[String], Vector[String]]
