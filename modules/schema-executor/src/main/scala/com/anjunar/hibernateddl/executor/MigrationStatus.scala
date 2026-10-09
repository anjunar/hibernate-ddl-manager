package com.anjunar.hibernateddl.executor

import com.anjunar.hibernateddl.core.*
import java.sql.Connection

/** Adopted: an existing database matched the target and was recorded as revision 1.
  * ManuallyMigrated: the database matched a target migrated by hand and was recorded as the
  * next revision.
  */
enum MigrationStatus:
  case Applied, AlreadyApplied, Adopted, ManuallyMigrated
