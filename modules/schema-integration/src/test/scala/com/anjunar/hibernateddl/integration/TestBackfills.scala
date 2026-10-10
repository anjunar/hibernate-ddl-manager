package com.anjunar.hibernateddl.integration

import com.anjunar.hibernateddl.core.*
import com.anjunar.hibernateddl.hibernate.annotation.SchemaId as Id
import jakarta.persistence.*

import scala.compiletime.uninitialized

/** Registered in META-INF/services for every integration test. */
final class TestBackfills extends BackfillProvider:
  def backfills: Seq[Backfill] = Seq(TestBackfills.nick)

object TestBackfills:
  val nick: Backfill = Backfill.fillNulls(
    "member-nick-v1",
    SchemaId("c1d2e3f4/1b2c3d4e"),
    BackfillTrigger.BecomesRequired,
    BackfillValue.literal("anonymous")
  )
