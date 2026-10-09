package com.anjunar.hibernateddl.executor

import com.anjunar.hibernateddl.core.*

/** Which data checks a preview runs: none, or whether at least one problematic row exists. */
enum DataChecks:
  case Skip, Existence
