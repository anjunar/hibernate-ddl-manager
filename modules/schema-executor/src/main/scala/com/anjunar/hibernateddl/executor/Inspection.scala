package com.anjunar.hibernateddl.executor

import com.anjunar.hibernateddl.core.*
import java.sql.Connection

/** What a read-only comparison of the database with a model found: differences, and what it
  * could not decide.
  */
final case class Inspection(differences: Vector[String], undecided: Vector[String])
