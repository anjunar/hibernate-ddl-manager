package com.anjunar.hibernateddl.cli

import com.anjunar.hibernateddl.core.*
import com.anjunar.hibernateddl.postgresql.PostgreSqlDialect

/** The command line: a review-only demo and a read-only preview. Neither migrates a database. */
object Main:
  private val usage = s"""Hibernate DDL Manager
    |Usage: sbt "schemaCli/run demo"
    |       sbt "schemaCli/run preview --target target-schema.json"
    |       sbt "schemaCli/run --help"
    |
    |demo    Show a stable-ID column rename and its PostgreSQL SQL.
    |        This starter does not connect to or migrate a database.
    |
    |${PreviewCommand.usage}
    |""".stripMargin

  def main(args: Array[String]): Unit =
    args.toVector match
      case "preview" +: rest =>
        val outcome = PreviewCommand.run(rest, sys.env)
        if outcome.stdout.nonEmpty then print(outcome.stdout)
        if outcome.stderr.nonEmpty then Console.err.println(outcome.stderr)
        sys.exit(outcome.exitCode)
      case other => run(other) match
        case Right(output) => println(output)
        case Left(error) =>
          Console.err.println(error)
          sys.exit(2)

  def run(args: Vector[String]): Either[String, String] = args match
    case Vector() | Vector("--help") | Vector("-h") => Right(usage)
    case Vector("demo") => demo()
    case _ => Left(s"Unknown command: ${args.mkString(" ")}\n$usage")

  private def demo(): Either[String, String] =
    val login = ColumnModel(
      SchemaId("7f3a9c21/f34e45b6"), SqlIdentifier("username"), SqlType.Varchar(255), nullable = false
    )
    val users = TableModel(
      SchemaId("7f3a9c21"),
      QualifiedName(SqlIdentifier("users"), schema = Some(SqlIdentifier("public"))),
      Vector(login)
    )
    val previous = SchemaModel(Vector(users))
    val desired = SchemaModel(Vector(users.copy(
      columns = Vector(login.copy(name = SqlIdentifier("login_name")))
    )))

    for
      operations <- DiffEngine.diff(previous, desired).left.map(_.mkString("\n"))
      sql <- PostgreSqlDialect.render(operations).left.map(_.mkString("\n"))
    yield
      val steps = operations.map(operation => s"[${operation.risk}] $operation").mkString("\n")
      s"""Hibernate DDL Manager - review-only demo
         |Stable ID: 7f3a9c21/f34e45b6
         |
         |$steps
         |
         |PostgreSQL SQL (not executed):
         |${sql.mkString("\n")}
         |
         |Database drift, locks and application compatibility must be checked before deployment.
         |""".stripMargin
