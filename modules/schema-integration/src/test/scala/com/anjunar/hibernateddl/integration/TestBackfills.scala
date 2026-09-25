package com.anjunar.hibernateddl.integration

import com.anjunar.hibernateddl.core.*
import com.anjunar.hibernateddl.hibernate.annotation.SchemaId as Id
import jakarta.persistence.*

import scala.compiletime.uninitialized

/** A member whose nickname is optional, as an earlier release maps it. */
@Entity
@Table(name = "member")
@Id("c1d2e3f4")
class Member:
  @jakarta.persistence.Id @Id("0a1b2c3d") var id: java.lang.Long = uninitialized
  @Id("1b2c3d4e") var nick: String = uninitialized

/** The same member once the nickname is required. */
@Entity
@Table(name = "member")
@Id("c1d2e3f4")
class RequiredMember:
  @jakarta.persistence.Id @Id("0a1b2c3d") var id: java.lang.Long = uninitialized
  @Id("1b2c3d4e") @Column(nullable = false) var nick: String = uninitialized

/** Registered in META-INF/services for every integration test. */
final class TestBackfills extends BackfillProvider:
  def backfills: Seq[Backfill] = Seq(TestBackfills.nick)

object TestBackfills:
  val nick: Backfill = Backfill.fillNulls("member-nick-v1", SchemaId("c1d2e3f4/1b2c3d4e"), BackfillTrigger.BecomesRequired,
    BackfillValue.literal("anonymous"))
