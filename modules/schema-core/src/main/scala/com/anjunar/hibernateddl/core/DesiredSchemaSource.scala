package com.anjunar.hibernateddl.core

trait DesiredSchemaSource[Input]:
  def read(input: Input): Either[Vector[String], SchemaModel]

