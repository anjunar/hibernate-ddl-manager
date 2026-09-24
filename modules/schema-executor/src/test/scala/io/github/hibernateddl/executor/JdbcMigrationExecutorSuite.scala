package io.github.hibernateddl.executor

import io.github.hibernateddl.core.*
import java.lang.reflect.{InvocationHandler, Method, Proxy}
import java.sql.{Connection, SQLException, Statement}
import javax.sql.DataSource
import scala.collection.mutable.ArrayBuffer

class JdbcMigrationExecutorSuite extends munit.FunSuite:
  private val originalTable = TableModel(SchemaId("account"), QualifiedName(SqlIdentifier("account")),
    Vector(ColumnModel(SchemaId("account-name"), SqlIdentifier("name"), SqlType.Text)))
  private val previous = SchemaSnapshot(1, 0, SchemaModel(Vector(originalTable)))
  private val target = SchemaSnapshot(1, 1, SchemaModel(Vector(originalTable.copy(
    name = QualifiedName(SqlIdentifier("accounts")),
    columns = Vector(originalTable.columns.head.copy(name = SqlIdentifier("display_name")))
  ))))
  private val request = MigrationRequest("accounts-v1", previous, target)

  private class Harness:
    val events = ArrayBuffer.empty[String]
    var freshAutoCommit = true
    var failAt = Set.empty[String]
    var recorded: Option[HistoryEntry] = None
    var found: Option[HistoryEntry] = None
    var latest: Option[HistoryEntry] = None
    var driftAt = Set.empty[String]
    var backendErrors = Vector.empty[String]
    var renderErrors = Vector.empty[String]
    var sqlSuffix = ""
    var backendName = "recording-backend"

    def event(name: String): Unit =
      events += name
      if failAt.contains(name) then throw new SQLException(s"Failure at $name")

    private def proxy[A](clazz: Class[A])(dispatch: (String, Array[AnyRef]) => AnyRef): A =
      Proxy.newProxyInstance(clazz.getClassLoader, Array(clazz), new InvocationHandler:
        def invoke(instance: AnyRef, method: Method, args: Array[AnyRef]): AnyRef =
          if method.getName == "toString" then s"Proxy[${clazz.getSimpleName}]"
          else if method.getName == "equals" then Boolean.box(instance.asInstanceOf[AnyRef] eq args(0))
          else if method.getName == "hashCode" then Int.box(System.identityHashCode(instance))
          else dispatch(method.getName, Option(args).getOrElse(Array.empty[AnyRef]))
      ).asInstanceOf[A]

    private var statementNumber = 0
    val connection: Connection = proxy(classOf[Connection]) { (name, args) =>
      name match
        case "getAutoCommit" =>
          event("get-auto-commit")
          Boolean.box(freshAutoCommit)
        case "setAutoCommit" =>
          event(s"auto-commit:${args(0)}")
          null
        case "setTransactionIsolation" =>
          assertEquals(args(0), Int.box(Connection.TRANSACTION_READ_COMMITTED))
          event("read-committed")
          null
        case "createStatement" =>
          statementNumber += 1
          val number = statementNumber
          event(s"statement:$number")
          proxy(classOf[Statement]) { (method, values) =>
            method match
              case "setQueryTimeout" =>
                event(s"timeout:$number:${values(0)}")
                null
              case "execute" =>
                event(s"sql:$number:${values(0)}")
                Boolean.box(false)
              case "close" =>
                event(s"close-statement:$number")
                null
              case other => throw new UnsupportedOperationException(other)
          }
        case "commit" | "rollback" | "close" | "abort" =>
          event(name)
          null
        case other => throw new UnsupportedOperationException(other)
    }

    val dataSource: DataSource = proxy(classOf[DataSource]) { (name, _) =>
      if name == "getConnection" then
        event("connection")
        connection
      else throw new UnsupportedOperationException(name)
    }

    val backend: TransactionalMigrationBackend = new TransactionalMigrationBackend:
      def name: String = backendName
      def validate(request: MigrationRequest): Vector[String] = backendErrors
      def render(operations: Vector[SchemaOperation]): Either[Vector[String], Vector[String]] =
        if renderErrors.nonEmpty then Left(renderErrors)
        else Right(operations.map {
          case _: SchemaOperation.CreateTable => "create table" + sqlSuffix
          case _: SchemaOperation.AddColumn => "add column" + sqlSuffix
          case _: SchemaOperation.RenameTable => "rename table" + sqlSuffix
          case _: SchemaOperation.RenameColumn => "rename column" + sqlSuffix
        })
      private def onConnection(actual: Connection, label: String): Unit =
        assert(actual eq connection)
        event(label)
      def acquireLock(actual: Connection, options: ExecutionOptions): Unit = onConnection(actual, "lock")
      def initializeHistory(actual: Connection): Unit = onConnection(actual, "initialize-history")
      def findHistory(actual: Connection, id: String): Option[HistoryEntry] =
        onConnection(actual, s"find:$id")
        found
      def latestHistory(actual: Connection): Option[HistoryEntry] =
        onConnection(actual, "latest")
        latest
      def lockAndValidate(actual: Connection, expected: SchemaModel): Vector[String] =
        val label = if expected == previous.model then "previous" else "target"
        onConnection(actual, s"validate:$label")
        if driftAt.contains(label) then Vector("physical schema drift") else Vector.empty
      def recordHistory(actual: Connection, entry: HistoryEntry): Unit =
        onConnection(actual, "record-history")
        recorded = Some(entry)

    def migrate(value: MigrationRequest = request, options: ExecutionOptions = ExecutionOptions()): MigrationResult =
      new JdbcMigrationExecutor(backend, options).migrate(dataSource, value)

    def seedApplied(): Unit =
      migrate()
      found = recorded
      latest = recorded
      events.clear()

  test("one connection locks, checks history and schemas, executes SQL, records and commits in order") {
    val h = new Harness
    assertEquals(h.migrate(), MigrationResult("accounts-v1", 1, MigrationStatus.Applied, 2))
    assertEquals(h.events.toVector, Vector(
      "connection", "get-auto-commit", "auto-commit:false", "read-committed", "lock", "initialize-history",
      "find:accounts-v1", "latest", "validate:previous",
      "statement:1", "timeout:1:30", "sql:1:rename table", "close-statement:1",
      "statement:2", "timeout:2:30", "sql:2:rename column", "close-statement:2",
      "validate:target", "record-history", "commit", "close"
    ))
    val entry = h.recorded.get
    assertEquals(entry.previousFingerprint, SchemaFingerprint.of(previous))
    assertEquals(entry.targetFingerprint, SchemaFingerprint.of(target))
    assertEquals(entry.fromRevision, 0L)
    assertEquals(entry.toRevision, 1L)
    assert(entry.checksum.matches("[0-9a-f]{64}"))
  }

  test("invalid IDs, formats, revisions and timeouts are rejected before obtaining a connection") {
    val invalidRequests = Vector(
      request.copy(id = " "), request.copy(id = "x" * 201), request.copy(id = null),
      request.copy(previous = previous.copy(formatVersion = 2)),
      request.copy(target = target.copy(formatVersion = 0)),
      request.copy(previous = previous.copy(revision = -1)),
      request.copy(target = target.copy(revision = 0)),
      request.copy(target = target.copy(revision = 2)),
      request.copy(previous = previous.copy(revision = Long.MaxValue), target = target.copy(revision = Long.MinValue))
    )
    invalidRequests.foreach { invalid =>
      val h = new Harness
      assertEquals(intercept[MigrationException](h.migrate(invalid)).state, FailureState.NotStarted)
      assert(h.events.isEmpty)
    }
    Vector(ExecutionOptions(lockTimeoutMillis = 0), ExecutionOptions(statementTimeoutMillis = -1),
      ExecutionOptions(lockTimeoutMillis = Int.MaxValue), ExecutionOptions(statementTimeoutMillis = Int.MaxValue)
    ).foreach { options =>
      val h = new Harness
      assertEquals(intercept[MigrationException](h.migrate(options = options)).state, FailureState.NotStarted)
      assert(h.events.isEmpty)
    }
  }

  test("unsupported changes and invalid schemas cannot reach the connection or execute a partial rename") {
    val unsupported = request.copy(target = target.copy(model = SchemaModel(Vector(
      target.model.tables.head.copy(columns = Vector(target.model.tables.head.columns.head.copy(dataType = SqlType.Integer)))
    ))))
    val invalid = request.copy(target = target.copy(model = SchemaModel(Vector(originalTable, originalTable))))
    Vector(unsupported, invalid).foreach { value =>
      val h = new Harness
      assertEquals(intercept[MigrationException](h.migrate(value)).state, FailureState.NotStarted)
      assert(h.events.isEmpty)
    }
  }

  test("backend validation, render errors and disallowed risks fail before a connection") {
    val invalid = new Harness
    invalid.backendErrors = Vector("Unsupported backend feature")
    assertEquals(intercept[MigrationException](invalid.migrate()).state, FailureState.NotStarted)
    assert(invalid.events.isEmpty)
    val render = new Harness
    render.renderErrors = Vector("Cannot render plan")
    assertEquals(intercept[MigrationException](render.migrate()).state, FailureState.NotStarted)
    assert(render.events.isEmpty)
    val risk = new Harness
    assertEquals(intercept[MigrationException](risk.migrate(options = ExecutionOptions(allowedRisks = Set(RiskLevel.Safe)))).state,
      FailureState.NotStarted)
    assert(risk.events.isEmpty)
  }

  test("a connection already in a transaction is rejected without commit or rollback") {
    val h = new Harness
    h.freshAutoCommit = false
    assertEquals(intercept[MigrationException](h.migrate()).state, FailureState.NotStarted)
    assertEquals(h.events.toVector, Vector("connection", "get-auto-commit", "close"))
  }

  test("connection acquisition failures are NotStarted") {
    val h = new Harness
    h.failAt = Set("connection")
    assertEquals(intercept[MigrationException](h.migrate()).state, FailureState.NotStarted)
    assertEquals(h.events.toVector, Vector("connection"))
  }

  test("statement, postcheck and history failures roll back and never commit") {
    Vector("sql:2:rename column", "validate:target", "record-history", "close-statement:1", "lock").foreach { stage =>
      val h = new Harness
      h.failAt = Set(stage)
      val error = intercept[MigrationException](h.migrate())
      assertEquals(error.state, FailureState.RolledBack)
      assert(h.events.contains("rollback"))
      assert(!h.events.contains("commit"))
      assertEquals(h.events.last, "close")
      assert(!h.events.contains("auto-commit:true"))
    }
  }

  test("drift before DDL and after DDL both roll back") {
    Vector("previous", "target").foreach { stage =>
      val h = new Harness
      h.driftAt = Set(stage)
      assertEquals(intercept[MigrationException](h.migrate()).state, FailureState.RolledBack)
      assert(!h.events.contains("record-history"))
      assert(!h.events.contains("commit"))
      if stage == "previous" then assert(!h.events.exists(_.startsWith("sql:")))
    }
  }

  test("commit failure remains OutcomeUnknown even if rollback succeeds") {
    val h = new Harness
    h.failAt = Set("commit")
    assertEquals(intercept[MigrationException](h.migrate()).state, FailureState.OutcomeUnknown)
    assertEquals(h.events.takeRight(3).toVector, Vector("commit", "rollback", "close"))
  }

  test("rollback failure aborts the connection and never resets auto-commit") {
    val h = new Harness
    h.failAt = Set("validate:previous", "rollback")
    val error = intercept[MigrationException](h.migrate())
    assertEquals(error.state, FailureState.OutcomeUnknown)
    assertEquals(error.getCause.getSuppressed.map(_.getMessage).toVector, Vector("Failure at rollback"))
    assertEquals(h.events.takeRight(3).toVector, Vector("rollback", "abort", "close"))
    assert(!h.events.contains("auto-commit:true"))
  }

  test("close failure cannot mask a migration failure or turn a known commit into uncertainty") {
    val failed = new Harness
    failed.failAt = Set("validate:previous", "close")
    val error = intercept[MigrationException](failed.migrate())
    assertEquals(error.state, FailureState.RolledBack)
    assert(error.getMessage.contains("validate:previous"))
    assertEquals(error.getSuppressed.map(_.getMessage).toVector, Vector("Failure at close"))
    val applied = new Harness
    applied.failAt = Set("close")
    assertEquals(applied.migrate().status, MigrationStatus.Applied)
  }

  test("already applied validates target drift under the lock without repeating DDL") {
    val h = new Harness
    h.seedApplied()
    assertEquals(h.migrate(), MigrationResult("accounts-v1", 1, MigrationStatus.AlreadyApplied, 0))
    assertEquals(h.events.toVector, Vector(
      "connection", "get-auto-commit", "auto-commit:false", "read-committed", "lock", "initialize-history",
      "find:accounts-v1", "latest", "validate:target", "commit", "close"
    ))
    h.events.clear()
    h.driftAt = Set("target")
    assertEquals(intercept[MigrationException](h.migrate()).state, FailureState.RolledBack)
    assert(!h.events.contains("commit"))
  }

  test("reusing an ID with a changed SQL plan, backend or snapshot is rejected") {
    Vector("sql", "backend", "snapshot").foreach { change =>
      val h = new Harness
      h.seedApplied()
      var next = request
      change match
        case "sql" => h.sqlSuffix = ";"
        case "backend" => h.backendName = "different-backend"
        case "snapshot" =>
          def changed(snapshot: SchemaSnapshot): SchemaSnapshot = snapshot.copy(model = SchemaModel(
            snapshot.model.tables.map(t => t.copy(columns = t.columns.map(_.copy(nullable = false))))
          ))
          next = request.copy(previous = changed(previous), target = changed(target))
        case _ => ()
      val error = intercept[MigrationException](h.migrate(next))
      assert(error.getMessage.contains("different plan"))
      assertEquals(error.state, FailureState.RolledBack)
      assert(!h.events.exists(_.startsWith("sql:")))
    }
  }

  test("plan checksums bind the migration ID") {
    val first = new Harness
    val second = new Harness
    first.migrate()
    second.migrate(request.copy(id = "another-id"))
    assertNotEquals(first.recorded.get.checksum, second.recorded.get.checksum)
  }

  test("an earlier applied migration cannot start a stale server after a later revision") {
    val h = new Harness
    h.seedApplied()
    h.latest = h.latest.map(_.copy(id = "later", toRevision = 2, targetFingerprint = "later-fingerprint"))
    val error = intercept[MigrationException](h.migrate())
    assert(error.getMessage.contains("stale server"))
    assert(!h.events.exists(_.startsWith("validate:")))
    assert(!h.events.contains("commit"))
  }

  test("first migration requires revision zero and later migrations require exact predecessor fingerprint") {
    val fresh = new Harness
    assert(intercept[MigrationException](fresh.migrate(request.copy(
      previous = previous.copy(revision = 1), target = target.copy(revision = 2)
    ))).getMessage.contains("revision 0"))
    assert(!fresh.events.exists(_.startsWith("sql:")))
    Vector("revision", "fingerprint").foreach { change =>
      val h = new Harness
      h.seedApplied()
      h.found = None
      val earlier = request.copy(id = "different-id")
      val next = request.copy(id = "next-id", previous = target,
        target = target.copy(revision = 2, model = previous.model))
      if change == "fingerprint" then h.latest = h.latest.map(_.copy(targetFingerprint = "wrong"))
      val error = intercept[MigrationException](h.migrate(if change == "revision" then earlier else next))
      assert(error.getMessage.contains("Previous snapshot"))
      assert(!h.events.exists(_.startsWith("sql:")))
    }
  }

  test("a matching predecessor permits the next revision") {
    val h = new Harness
    h.seedApplied()
    h.found = None
    val next = MigrationRequest("accounts-v2", target, target.copy(revision = 2, model = previous.model))
    assertEquals(h.migrate(next).status, MigrationStatus.Applied)
    assertEquals(h.recorded.get.fromRevision, 1L)
    assertEquals(h.recorded.get.toRevision, 2L)
    assertEquals(h.recorded.get.previousFingerprint, SchemaFingerprint.of(target))
  }

  test("JDBC query timeout rounds up to a positive second") {
    val h = new Harness
    h.migrate(options = ExecutionOptions(statementTimeoutMillis = 1))
    assert(h.events.contains("timeout:1:1"))
  }

  test("a no-op revision checks both snapshots and records history without creating SQL statements") {
    val h = new Harness
    val unchanged = request.copy(target = previous.copy(revision = 1))
    assertEquals(h.migrate(unchanged).statementCount, 0)
    assert(!h.events.exists(_.startsWith("statement:")))
    assert(h.events.contains("record-history"))
    assert(h.events.contains("commit"))
  }
