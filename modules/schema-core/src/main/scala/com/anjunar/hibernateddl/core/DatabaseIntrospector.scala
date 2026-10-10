package com.anjunar.hibernateddl.core

import java.sql.Connection

trait DatabaseIntrospector:
  def inspect(connection: Connection): Either[Vector[String], SchemaModel]
