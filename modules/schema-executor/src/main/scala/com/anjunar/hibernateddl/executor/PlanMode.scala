package com.anjunar.hibernateddl.executor

import com.anjunar.hibernateddl.core.*

/** NoChange: the target is applied. Adoption and ManualMigration record the database as it is. */
enum PlanMode:
  case NoChange, Migration, Adoption, ManualMigration
