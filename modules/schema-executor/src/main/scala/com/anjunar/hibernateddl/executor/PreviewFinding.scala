package com.anjunar.hibernateddl.executor

import com.anjunar.hibernateddl.core.*

/** A plan problem, tied to the step and stable ID it concerns. */
final case class PreviewFinding(code: String, message: String, step: Option[Int] = None, subject: Option[String] = None)
