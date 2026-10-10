package com.anjunar.hibernateddl.core
enum TypeChange:
  case Unchanged

  /** `rule` says why the change is allowed. */
  case Widening(rule: String, rewrite: TableRewrite)

  /** `reason` completes "The change ...". */
  case Unsupported(rejection: TypeChangeRejection, reason: String)
