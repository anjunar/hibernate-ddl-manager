package com.anjunar.hibernateddl.hibernate

import com.anjunar.hibernateddl.core.{SqlIdentifier, TableCheck}
import com.anjunar.hibernateddl.hibernate.annotation.SchemaId
import jakarta.persistence.{CheckConstraint, Column, Entity, Id, Table}
import munit.FunSuite

import java.util

@Entity
@SchemaId("f4971acd")
@Table(name = "unnamed_check", schema = "public", check = Array(new CheckConstraint(constraint = "id > 0")))
class UnnamedCheckedPost:
  @Id
  @SchemaId("4ac9e501")
  var id: Int = 0
