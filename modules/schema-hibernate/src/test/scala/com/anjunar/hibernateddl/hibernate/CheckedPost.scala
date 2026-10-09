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
