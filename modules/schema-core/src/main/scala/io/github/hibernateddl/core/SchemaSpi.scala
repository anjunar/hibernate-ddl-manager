package io.github.hibernateddl.core

trait DesiredSchemaSource[Input]:
  def read(input: Input): Either[Vector[String], SchemaModel]

trait DatabaseIntrospector:
  def inspect(connection: java.sql.Connection): Either[Vector[String], SchemaModel]

trait SchemaDialect:
  def render(operations: Vector[SchemaOperation]): Either[Vector[String], Vector[String]]
