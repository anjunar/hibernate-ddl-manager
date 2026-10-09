package com.anjunar.hibernateddl.executor

import com.anjunar.hibernateddl.core.*
enum CheckStatus:
  /** The checked condition holds in the state read. */
  case Passed
  /** A blocker was found. */
  case Failed
  /** The plan does not need the check. */
  case NotApplicable
  /** Not selected, or not run after an earlier failure. */
  case NotRun
  /** Could not be decided, for example after a timeout, without privileges or for an unsupported form. */
  case Inconclusive

