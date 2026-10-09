package com.anjunar.hibernateddl.core

trait DatabaseIntrospector:
  def inspect(connection: java.sql.Connection): Either[Vector[String], SchemaModel]

