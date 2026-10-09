package com.anjunar.hibernateddl.core
final case class QualifiedName(
    name: SqlIdentifier,
    schema: Option[SqlIdentifier] = None,
    catalog: Option[SqlIdentifier] = None
):
  def display: String = (catalog.toVector ++ schema.toVector :+ name).map(_.value).mkString(".")
