package io.github.hibernateddl.executor

import io.github.hibernateddl.core.*
import java.lang.reflect.{InvocationHandler, Method, Proxy}
import java.sql.{Connection, SQLException, Statement}
import javax.sql.DataSource
import scala.collection.mutable.ArrayBuffer

class JdbcMigrationExecutorSuite extends munit.FunSuite:
  private val name = ColumnModel(SchemaId("account-name"), SqlIdentifier("name"), SqlType.Text)
  private val account = TableModel(SchemaId("account"), QualifiedName(SqlIdentifier("account")), Vector(name))
  private val initial = SchemaModel(Vector(account))
  private val renamed = SchemaModel(Vector(account.copy(
    name = QualifiedName(SqlIdentifier("accounts")),
    columns = Vector(name.copy(name = SqlIdentifier("display_name")))
  )))
  private val empty = SchemaModel(Vector.empty)

  private class Harness:
    val events = ArrayBuffer.empty[String]
    var freshAutoCommit = true
    var failAt = Set.empty[String]
    /** Committed history; a recorded entry becomes part of it only when the transaction commits. */
    var history = Vector.empty[HistoryEntry]
    private var pending = Option.empty[HistoryEntry]
    var driftAt = Set.empty[String]
    var backendErrors = Vector.empty[String]
    var renderErrors = Vector.empty[String]

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
        case "commit" =>
          event(name)
          history ++= pending
          pending = None
          null
        case "rollback" | "close" | "abort" =>
          event(name)
          pending = None
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
      def validate(model: SchemaModel): Vector[String] = backendErrors
      def render(operations: Vector[SchemaOperation]): Either[Vector[String], Vector[String]] =
        if renderErrors.nonEmpty then Left(renderErrors)
        else Right(operations.map {
          case _: SchemaOperation.CreateTable => "create table"
          case _: SchemaOperation.AddColumn => "add column"
          case _: SchemaOperation.RenameTable => "rename table"
          case _: SchemaOperation.RenameColumn => "rename column"
        })
      private def onConnection(actual: Connection, label: String): Unit =
        assert(actual eq connection)
        event(label)
      def acquireLock(actual: Connection, options: ExecutionOptions): Unit = onConnection(actual, "lock")
      def initializeHistory(actual: Connection): Unit = onConnection(actual, "initialize-history")
      def readHistory(actual: Connection): Vector[HistoryEntry] =
        onConnection(actual, "read-history")
        history
      def lockAndValidate(actual: Connection, expected: SchemaModel): Vector[String] =
        val label = expected.tables.map(_.name.name.value).mkString(",")
        onConnection(actual, s"validate:$label")
        if driftAt.contains(label) then Vector("physical schema drift") else Vector.empty
      def recordHistory(actual: Connection, entry: HistoryEntry): Unit =
        onConnection(actual, "record-history")
        pending = Some(entry)

    def migrate(target: SchemaModel, options: ExecutionOptions = ExecutionOptions()): MigrationResult =
      new JdbcMigrationExecutor(backend, options).migrate(dataSource, target)

    def seed(models: SchemaModel*): Unit =
      models.foreach(model => migrate(model))
      events.clear()
      statementNumber = 0

    def refused(target: SchemaModel, options: ExecutionOptions = ExecutionOptions()): MigrationException =
      val before = history
      val error = intercept[MigrationException](migrate(target, options))
      assertEquals(history, before)
      assert(!events.contains("commit"), events)
      error

  test("the first start creates the schema from the empty model and stores the model as revision 1") {
    val h = new Harness
    assertEquals(h.migrate(initial), MigrationResult(1, MigrationStatus.Applied, 1))
    assertEquals(h.events.toVector, Vector(
      "connection", "get-auto-commit", "auto-commit:false", "read-committed", "lock", "initialize-history",
      "read-history", "validate:", "statement:1", "timeout:1:30", "sql:1:create table", "close-statement:1",
      "validate:account", "record-history", "commit", "close"
    ))
    val entry = h.history.head
    assertEquals(entry.revision, 1L)
    assertEquals(entry.previousFingerprint, SchemaFingerprint.of(empty))
    assertEquals(entry.targetFingerprint, SchemaFingerprint.of(initial))
    assertEquals(SchemaModelJson.decode(entry.model), Right(initial))
    assertEquals(entry.statements, Vector("create table"))
  }

  test("a later start plans against the stored model and records the next revision") {
    val h = new Harness
    h.seed(initial)
    assertEquals(h.migrate(renamed), MigrationResult(2, MigrationStatus.Applied, 2))
    assertEquals(h.events.toVector, Vector(
      "connection", "get-auto-commit", "auto-commit:false", "read-committed", "lock", "initialize-history",
      "read-history", "validate:account",
      "statement:1", "timeout:1:30", "sql:1:rename table", "close-statement:1",
      "statement:2", "timeout:2:30", "sql:2:rename column", "close-statement:2",
      "validate:accounts", "record-history", "commit", "close"
    ))
    val entry = h.history.last
    assertEquals(entry.revision, 2L)
    assertEquals(entry.previousFingerprint, SchemaFingerprint.of(initial))
    assertEquals(entry.targetFingerprint, SchemaFingerprint.of(renamed))
  }

  test("an unchanged model validates the database under the lock without DDL or history") {
    val h = new Harness
    h.seed(initial)
    val reordered = SchemaModel(initial.tables.map(t => t.copy(columns = t.columns.reverse)).reverse)
    assertEquals(h.migrate(reordered), MigrationResult(1, MigrationStatus.AlreadyApplied, 0))
    assertEquals(h.events.toVector, Vector(
      "connection", "get-auto-commit", "auto-commit:false", "read-committed", "lock", "initialize-history",
      "read-history", "validate:account", "commit", "close"
    ))
    h.events.clear()
    h.driftAt = Set("account")
    assertEquals(h.refused(initial).state, FailureState.RolledBack)
  }

  test("an empty model on a database without history is revision 0 and records nothing") {
    val h = new Harness
    assertEquals(h.migrate(empty), MigrationResult(0, MigrationStatus.AlreadyApplied, 0))
    assert(h.history.isEmpty)
  }

  test("a server that skipped releases applies all changes against the model applied last") {
    val h = new Harness
    h.seed(initial)
    val bio = ColumnModel(SchemaId("account-bio"), SqlIdentifier("bio"), SqlType.Text)
    val latest = SchemaModel(renamed.tables.map(t => t.copy(columns = t.columns :+ bio)))
    assertEquals(h.migrate(latest), MigrationResult(2, MigrationStatus.Applied, 3))
    assert(h.events.containsSlice(Vector("sql:1:rename table", "close-statement:1")))
    assert(h.events.contains("sql:2:rename column"))
    assert(h.events.contains("sql:3:add column"))
  }

  test("a server with an older schema cannot start after a newer migration") {
    val h = new Harness
    h.seed(initial, renamed)
    val error = h.refused(initial)
    assertEquals(error.state, FailureState.RolledBack)
    assert(error.getMessage.contains("equals revision 1, but the database is at revision 2"), error.getMessage)
    assert(!h.events.exists(_.startsWith("validate:")))
  }

  test("renaming back to an earlier name is refused like an older server") {
    val h = new Harness
    h.seed(initial, renamed)
    val bio = ColumnModel(SchemaId("account-bio"), SqlIdentifier("bio"), SqlType.Text)
    val reverted = SchemaModel(initial.tables.map(t => t.copy(columns = t.columns :+ bio)))
    val error = h.refused(reverted)
    assert(error.getMessage.contains("Renaming table 'account' back to its name from revision 1"), error.getMessage)
    assert(error.getMessage.contains("Renaming column 'account-name' back to its name from revision 1"), error.getMessage)
    assert(!h.events.exists(_.startsWith("sql:")))
  }

  test("unsupported changes, render errors and disallowed risks are refused under the lock before DDL") {
    val retyped = SchemaModel(Vector(account.copy(columns = Vector(name.copy(dataType = SqlType.Integer)))))
    val h = new Harness
    h.seed(initial)
    assert(h.refused(retyped).getMessage.contains("Changing type"))
    assert(h.events.containsSlice(Vector("read-history", "rollback", "close")), h.events)
    val render = new Harness
    render.renderErrors = Vector("Cannot render plan")
    assert(render.refused(initial).getMessage.contains("Cannot render plan"))
    val risk = new Harness
    assert(risk.refused(initial, ExecutionOptions(allowedRisks = Set(RiskLevel.Safe))).getMessage.contains("risks"))
    Vector(h, render, risk).foreach { harness =>
      assert(!harness.events.exists(event => event.startsWith("sql:") || event.startsWith("validate:")))
    }
  }

  test("invalid targets, backend validation errors and invalid timeouts fail before a connection") {
    val invalid = new Harness
    assertEquals(invalid.refused(SchemaModel(Vector(account, account))).state, FailureState.NotStarted)
    val backend = new Harness
    backend.backendErrors = Vector("Unsupported backend feature")
    assert(backend.refused(initial).getMessage.contains("Target schema: Unsupported backend feature"))
    val harnesses = Vector(invalid, backend) ++ Vector(ExecutionOptions(lockTimeoutMillis = 0),
      ExecutionOptions(statementTimeoutMillis = -1), ExecutionOptions(lockTimeoutMillis = Int.MaxValue),
      ExecutionOptions(statementTimeoutMillis = Int.MaxValue)
    ).map { options =>
      val h = new Harness
      assertEquals(h.refused(initial, options).state, FailureState.NotStarted)
      h
    }
    harnesses.foreach(h => assert(h.events.isEmpty))
  }

  test("an inconsistent history is refused before planning") {
    def entry(revision: Long, previous: SchemaModel, target: SchemaModel) = HistoryEntry(revision,
      SchemaFingerprint.of(previous), SchemaFingerprint.of(target), SchemaModelJson.encode(target), Vector.empty)
    val valid = Vector(entry(1, empty, initial), entry(2, initial, renamed))
    Vector(
      "expected revision 2" -> Vector(valid(0), valid(1).copy(revision = 3)),
      "expected revision 1" -> Vector(valid(1)),
      "previous fingerprint" -> Vector(valid(0), valid(1).copy(previousFingerprint = SchemaFingerprint.of(empty))),
      "does not match its fingerprint" -> Vector(valid(0), valid(1).copy(model = SchemaModelJson.encode(initial))),
      "unreadable" -> Vector(valid(0), valid(1).copy(model = "{}"))
    ).foreach { (reason, history) =>
      val h = new Harness
      h.history = history
      val error = h.refused(renamed)
      assert(error.getMessage.contains("Schema history is inconsistent"), error.getMessage)
      assert(error.getMessage.contains(reason), error.getMessage)
      assert(!h.events.exists(_.startsWith("validate:")))
    }
  }

  test("a connection already in a transaction is rejected without commit or rollback") {
    val h = new Harness
    h.freshAutoCommit = false
    assertEquals(h.refused(initial).state, FailureState.NotStarted)
    assertEquals(h.events.toVector, Vector("connection", "get-auto-commit", "close"))
  }

  test("connection acquisition failures are NotStarted") {
    val h = new Harness
    h.failAt = Set("connection")
    assertEquals(h.refused(initial).state, FailureState.NotStarted)
    assertEquals(h.events.toVector, Vector("connection"))
  }

  test("statement, postcheck and history failures roll back and never commit") {
    Vector("sql:2:rename column", "validate:accounts", "record-history", "close-statement:1", "lock", "read-history")
      .foreach { stage =>
        val h = new Harness
        h.seed(initial)
        h.failAt = Set(stage)
        val error = h.refused(renamed)
        assertEquals(error.state, FailureState.RolledBack)
        assert(h.events.contains("rollback"))
        assertEquals(h.events.last, "close")
        assert(!h.events.contains("auto-commit:true"))
      }
  }

  test("drift before DDL and after DDL both roll back") {
    Vector("account", "accounts").foreach { stage =>
      val h = new Harness
      h.seed(initial)
      h.driftAt = Set(stage)
      assertEquals(h.refused(renamed).state, FailureState.RolledBack)
      assert(!h.events.contains("record-history"))
      if stage == "account" then assert(!h.events.exists(_.startsWith("sql:")))
    }
  }

  test("commit failure remains OutcomeUnknown even if rollback succeeds") {
    val h = new Harness
    h.failAt = Set("commit")
    assertEquals(intercept[MigrationException](h.migrate(initial)).state, FailureState.OutcomeUnknown)
    assertEquals(h.events.takeRight(3).toVector, Vector("commit", "rollback", "close"))
  }

  test("rollback failure aborts the connection and never resets auto-commit") {
    val h = new Harness
    h.failAt = Set("validate:", "rollback")
    val error = h.refused(initial)
    assertEquals(error.state, FailureState.OutcomeUnknown)
    assertEquals(error.getCause.getSuppressed.map(_.getMessage).toVector, Vector("Failure at rollback"))
    assertEquals(h.events.takeRight(3).toVector, Vector("rollback", "abort", "close"))
    assert(!h.events.contains("auto-commit:true"))
  }

  test("close failure cannot mask a migration failure or turn a known commit into uncertainty") {
    val failed = new Harness
    failed.failAt = Set("validate:", "close")
    val error = failed.refused(initial)
    assertEquals(error.state, FailureState.RolledBack)
    assert(error.getMessage.contains("validate:"))
    assertEquals(error.getSuppressed.map(_.getMessage).toVector, Vector("Failure at close"))
    val applied = new Harness
    applied.failAt = Set("close")
    assertEquals(applied.migrate(initial).status, MigrationStatus.Applied)
  }

  test("JDBC query timeout rounds up to a positive second") {
    val h = new Harness
    h.migrate(initial, ExecutionOptions(statementTimeoutMillis = 1))
    assert(h.events.contains("timeout:1:1"))
  }
