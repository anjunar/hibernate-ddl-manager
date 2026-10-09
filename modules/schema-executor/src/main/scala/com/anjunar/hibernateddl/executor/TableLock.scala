package com.anjunar.hibernateddl.executor

import com.anjunar.hibernateddl.core.*
import java.sql.Connection

/** How [[TransactionalMigrationBackend.lockAndValidate]] locks the tables it inspects until the
  * transaction ends. Exclusive keeps everyone out, as before and after DDL. Shared only keeps
  * schema changes out and lets the application read and write, for checking a schema that is
  * already applied at every server start.
  */
enum TableLock:
  case Exclusive, Shared
