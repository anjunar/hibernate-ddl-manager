package com.anjunar.hibernateddl.executor

import com.anjunar.hibernateddl.core.*
enum PreviewOutcome:
  /** A complete plan without blockers, and every required check passed. */
  case Ready
  /** A blocker was found. */
  case Blocked
  /** No blocker was found, but a required check is open. */
  case Incomplete
