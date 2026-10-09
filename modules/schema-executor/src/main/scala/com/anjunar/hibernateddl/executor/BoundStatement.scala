package com.anjunar.hibernateddl.executor

import com.anjunar.hibernateddl.core.*
import java.sql.Connection

/** SQL with JDBC parameters, bound in order. */
final case class BoundStatement(sql: String, parameters: Vector[AnyRef])
