package com.anjunar.hibernateddl.hibernate

import com.anjunar.hibernateddl.hibernate.annotation.SchemaId
import jakarta.persistence.{Access, AccessType, CheckConstraint, Column, Entity, Enumerated, EnumType, GeneratedValue, GenerationType, Id, Table, UniqueConstraint, Version}

import java.lang
import java.time.Instant
import java.util

@Entity
@SchemaId("d4f39c20")
@Access(AccessType.FIELD)
@Table(
  name = "blog_post",
  schema = "public",
  uniqueConstraints = Array(new UniqueConstraint(name = "uq_blog_post_slug", columnNames = Array("slug"))),
  check = Array(new CheckConstraint(
    name = "ck_blog_post_publication",
    constraint = "(status = 'DRAFT' AND published_at IS NULL) OR (status = 'PUBLISHED' AND published_at IS NOT NULL)"
  ))
)
class TutorialPost:
  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  @SchemaId("a2473e8b")
  @Column(nullable = false, updatable = false)
  var id: util.UUID = null

  @Version
  @SchemaId("dcb0681e")
  @Column(nullable = false)
  var version: lang.Long = null

  @SchemaId("682d9ace")
  @Column(nullable = false, length = 220)
  var slug: String = ""

  @SchemaId("46fdb02a")
  @Column(nullable = false, length = 180)
  var title: String = ""

  @SchemaId("7b20efc1")
  @Column(nullable = false, columnDefinition = "text")
  var content: String = ""

  @SchemaId("cf271a06")
  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 24)
  var status: TutorialPublicationStatus = TutorialPublicationStatus.DRAFT

  @SchemaId("398bfd50")
  @Column(name = "published_at")
  var publishedAt: Instant = null
