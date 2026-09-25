package com.anjunar.hibernateddl.executor

import com.anjunar.hibernateddl.core.*
import java.lang.reflect.{InvocationHandler, Method, Proxy}
import java.sql.{Connection, SQLException}
import javax.sql.DataSource
import scala.collection.mutable.ArrayBuffer

class JdbcMigrationPreviewSuite extends munit.FunSuite:
  private val name = ColumnModel(SchemaId("account-name"), SqlIdentifier("name"), SqlType.Text)
  private val account = TableModel(SchemaId("account"), QualifiedName(SqlIdentifier("account")), Vector(name))
  private val target = SchemaModel(Vector(account))

  private class Harness:
    val events = ArrayBuffer.empty[String]
    var failAt = Set.empty[String]
    var history = Option.empty[Vector[HistoryEntry]]
    def event(label: String): Unit =
      events += label
      if failAt.contains(label) then throw new SQLException(s"Failure at $label")
    val connection: Connection = Proxy.newProxyInstance(classOf[Connection].getClassLoader, Array(classOf[Connection]),
      new InvocationHandler:
        def invoke(proxy: AnyRef, method: Method, args: Array[AnyRef]): AnyRef = method.getName match
          case "getAutoCommit" => event("get-auto-commit"); java.lang.Boolean.TRUE
          case "setAutoCommit" => event(s"auto-commit:${args(0)}"); null
          case name @ ("rollback" | "commit" | "close" | "abort") => event(name); null
          case other => throw new UnsupportedOperationException(other)
    ).asInstanceOf[Connection]
    val dataSource: DataSource = Proxy.newProxyInstance(classOf[DataSource].getClassLoader, Array(classOf[DataSource]),
      (_, method, _) => if method.getName == "getConnection" then connection else throw new UnsupportedOperationException(method.getName)
    ).asInstanceOf[DataSource]
    val backend: PreviewBackend = new PreviewBackend:
      def validate(model: SchemaModel): Vector[String] = Vector.empty
      def render(operations: Vector[SchemaOperation]): Either[Vector[String], Vector[String]] =
        Right(operations.map(_.getClass.getSimpleName))
      def nullCount(table: QualifiedName, column: SqlIdentifier): String = "count"
      def renderFill(fill: NullFill): Either[Vector[String], BoundStatement] = Right(BoundStatement("fill", Vector.empty))
      def beginReadOnly(actual: Connection, options: ExecutionOptions): Unit = event("read-only")
      def readHistoryIfPresent(actual: Connection): Option[Vector[HistoryEntry]] = { event("read-history"); history }
      def readBackfillsIfPresent(actual: Connection): Vector[BackfillRecord] = { event("read-backfills"); Vector.empty }
      def existingRelations(actual: Connection, model: SchemaModel): Vector[QualifiedName] = { event("existing"); Vector.empty }
      def inspect(actual: Connection, expected: SchemaModel): Inspection = { event("inspect"); Inspection(Vector.empty, Vector.empty) }
      def dataCheck(actual: Connection, query: DataQuery, count: Boolean): Long = { event("data"); 0 }
      def estimateRows(actual: Connection, table: QualifiedName): Option[Long] = { event("estimate"); None }
      def tableSize(actual: Connection, table: QualifiedName): Option[Long] = { event("size"); None }
      def typeChangeBlockers(actual: Connection, table: QualifiedName, column: SqlIdentifier): Vector[String] =
        event("dependents")
        Vector.empty
      def bindDrop(actual: Connection, operation: SchemaOperation, table: QualifiedName,
          columns: Vector[SqlIdentifier]): Either[Vector[String], String] = { event("bind"); Right("bound") }

  test("a preview reads inside a read-only transaction that it rolls back, and never commits") {
    val h = new Harness
    val report = new JdbcMigrationPreview(h.backend).preview(h.dataSource, target)
    assertEquals(report.outcome, PreviewOutcome.Ready)
    assertEquals(h.events.toVector, Vector("get-auto-commit", "auto-commit:false", "read-only", "read-history",
      "read-backfills", "existing", "existing", "rollback", "auto-commit:true", "close"))
  }

  test("a failing read ends the checks, keeps what was found and still gives the connection back") {
    val h = new Harness
    h.failAt = Set("existing")
    val report = new JdbcMigrationPreview(h.backend).preview(h.dataSource, target)
    assertEquals(report.outcome, PreviewOutcome.Incomplete)
    assertEquals(report.checks.map(c => c.code -> c.status), Vector(PreviewCheck.HistoryReadable -> CheckStatus.Passed,
      PreviewCheck.HistoryConsistent -> CheckStatus.Passed, PreviewCheck.NamesFree -> CheckStatus.Inconclusive,
      PreviewCheck.NamesFree -> CheckStatus.NotRun))
    assert(!h.events.contains("commit"))
    assertEquals(h.events.takeRight(3).toVector, Vector("rollback", "auto-commit:true", "close"))
  }

  test("a connection that cannot be restored is aborted, never handed back in the transaction") {
    val h = new Harness
    h.failAt = Set("auto-commit:true")
    new JdbcMigrationPreview(h.backend).preview(h.dataSource, target)
    assertEquals(h.events.takeRight(3).toVector, Vector("auto-commit:true", "abort", "close"))
    assert(!h.events.contains("commit"))
  }

  test("invalid inputs fail before any connection") {
    val h = new Harness
    val invalid = Backfill.fillNulls(" ", name.id, BackfillTrigger.BecomesRequired, BackfillValue.literal("x"))
    assertEquals(intercept[MigrationException](new JdbcMigrationPreview(h.backend).preview(h.dataSource, target, Vector(invalid)))
      .state, FailureState.NotStarted)
    assert(h.events.isEmpty)
  }
