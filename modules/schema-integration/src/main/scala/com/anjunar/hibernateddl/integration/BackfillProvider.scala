package com.anjunar.hibernateddl.integration

import com.anjunar.hibernateddl.core.Backfill

/** Supplies backfills to both ways of starting the migration: [[HibernateSchemaMigration]] and
  * the integrator find every provider through Java's ServiceLoader, via Hibernate's class
  * loader service (`META-INF/services/com.anjunar.hibernateddl.integration.BackfillProvider`).
  * A provider only describes rules; it must not need a SessionFactory or an EntityManager.
  * Keep the rules of every release that a server may still skip.
  */
trait BackfillProvider:
  def backfills: Seq[Backfill]
