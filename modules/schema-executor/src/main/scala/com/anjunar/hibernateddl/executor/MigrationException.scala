package com.anjunar.hibernateddl.executor

import com.anjunar.hibernateddl.core.*
import java.sql.Connection
final class MigrationException(
    message: String,
    val state: FailureState,
    cause: Throwable = null
) extends RuntimeException(message, cause)
