package com.anjunar.hibernateddl.executor

import com.anjunar.hibernateddl.core.*
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
    /** The connection's isolation level; it arrives as SERIALIZABLE. */
    var isolation = Connection.TRANSACTION_SERIALIZABLE
    var failAt = Set.empty[String]
    /** Committed history; a recorded entry becomes part of it only when the transaction commits. */
    var history = Vector.empty[HistoryEntry]
    private var pending = Option.empty[HistoryEntry]
    var driftAt = Set.empty[String]
    var backendErrors = Vector.empty[String]
    var renderErrors = Vector.empty[String]
    /** What every NULL count returns, and how many rows every fill updates. */
    var nullRows = 0L
    var filledRows = 0
    /** Committed backfill records; recorded ones become part of them only when the transaction commits. */
    var backfills = Vector.empty[BackfillRecord]
    private var pendingBackfills = Vector.empty[BackfillRecord]
    /** Names of relations that exist in the database outside any history. */
    var existing = Set.empty[String]

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
        case "getTransactionIsolation" => Int.box(isolation)
        case "setTransactionIsolation" =>
          val level = args(0).asInstanceOf[Integer].intValue
          event(if level == Connection.TRANSACTION_READ_COMMITTED then "read-committed" else s"isolation:$level")
          isolation = level
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
              case "executeQuery" =>
                event(s"query:$number:${values(0)}")
                var read = false
                proxy(classOf[java.sql.ResultSet]) { (method, _) =>
                  method match
                    case "next" =>
                      val first = !read
                      read = true
                      Boolean.box(first)
                    case "getLong" => Long.box(nullRows)
                    case "close" => null
                    case other => throw new UnsupportedOperationException(other)
                }
              case "close" =>
                event(s"close-statement:$number")
                null
              case other => throw new UnsupportedOperationException(other)
          }
        case "prepareStatement" =>
          statementNumber += 1
          val number = statementNumber
          event(s"statement:$number")
          proxy(classOf[java.sql.PreparedStatement]) { (method, values) =>
            method match
              case "setQueryTimeout" =>
                event(s"timeout:$number:${values(0)}")
                null
              case "setObject" =>
                event(s"parameter:$number:${values(0)}:${values(1)}")
                null
              case "executeUpdate" =>
                event(s"update:$number:${args(0)}")
                Int.box(filledRows)
              case "close" =>
                event(s"close-statement:$number")
                null
              case other => throw new UnsupportedOperationException(other)
          }
        case "commit" =>
          event(name)
          history ++= pending
          backfills ++= pendingBackfills
          pending = None
          pendingBackfills = Vector.empty
          null
        case "rollback" | "close" | "abort" =>
          event(name)
          pending = None
          pendingBackfills = Vector.empty
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
          case _: SchemaOperation.AddForeignKey => "add foreign key"
          case _: SchemaOperation.AddUniqueKey => "add unique key"
          case _: SchemaOperation.CreateIndex => "create index"
          case _: SchemaOperation.ChangeCheck => "change check"
          case _: SchemaOperation.CreateSequence => "create sequence"
          case _: SchemaOperation.RenameSequence => "rename sequence"
          case _: SchemaOperation.DropColumn => "drop column"
          case _: SchemaOperation.DropTables => "drop tables"
          case _: SchemaOperation.DropSequence => "drop sequence"
          case _: SchemaOperation.SetNotNull => "set not null"
          case _: SchemaOperation.DropNotNull => "drop not null"
        })
      def nullCount(table: QualifiedName, column: SqlIdentifier): String = s"count nulls ${column.value}"
      def renderFill(fill: NullFill): Either[Vector[String], BoundStatement] =
        Right(BoundStatement(s"fill ${fill.column.value}", Vector(fill.value.toString)))
      def readBackfills(actual: Connection): Vector[BackfillRecord] =
        onConnection(actual, "read-backfills")
        backfills
      def recordBackfill(actual: Connection, record: BackfillRecord): Unit =
        onConnection(actual, s"record-backfill:${record.id}")
        pendingBackfills :+= record
      private def onConnection(actual: Connection, label: String): Unit =
        assert(actual eq connection)
        event(label)
      def acquireLock(actual: Connection, options: ExecutionOptions): Unit = onConnection(actual, "lock")
      def initializeHistory(actual: Connection): Unit = onConnection(actual, "initialize-history")
      def readHistory(actual: Connection): Vector[HistoryEntry] =
        onConnection(actual, "read-history")
        history
      def existingRelations(actual: Connection, model: SchemaModel): Vector[QualifiedName] =
        onConnection(actual, "existing-relations")
        (model.tables.map(_.name) ++ model.sequences.map(_.name)).filter(name => existing.contains(name.name.value))
      def lockAndValidate(actual: Connection, expected: SchemaModel, lock: TableLock): Vector[String] =
        val label = expected.tables.map(_.name.name.value).mkString(",")
        onConnection(actual, if lock == TableLock.Shared then s"validate-shared:$label" else s"validate:$label")
        if driftAt.contains(label) then Vector("physical schema drift") else Vector.empty
      def recordHistory(actual: Connection, entry: HistoryEntry): Unit =
        onConnection(actual, "record-history")
        pending = Some(entry)

    def migrate(
        target: SchemaModel,
        options: ExecutionOptions = ExecutionOptions(),
        backfills: Vector[Backfill] = Vector.empty
    ): MigrationResult =
      new JdbcMigrationExecutor(backend, options).migrate(dataSource, target, backfills)

    def seed(models: SchemaModel*): Unit =
      models.foreach(model => migrate(model))
      events.clear()
      statementNumber = 0

    def refused(
        target: SchemaModel,
        options: ExecutionOptions = ExecutionOptions(),
        backfills: Vector[Backfill] = Vector.empty
    ): MigrationException =
      val before = history
      val error = intercept[MigrationException](migrate(target, options, backfills))
      assertEquals(history, before)
      assert(!events.contains("commit"), events)
      error

  test("the first start creates the schema from the empty model and stores the model as revision 1") {
    val h = new Harness
    assertEquals(h.migrate(initial), MigrationResult(1, MigrationStatus.Applied, 1))
    assertEquals(h.events.toVector, Vector(
      "connection", "get-auto-commit", "auto-commit:false", "read-committed", "lock", "initialize-history",
      "read-history", "read-backfills", "existing-relations", "validate:", "statement:1", "timeout:1:30", "sql:1:create table", "close-statement:1",
      "validate:account", "record-history", "commit", "auto-commit:true", "isolation:8", "close"
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
      "read-history", "read-backfills", "validate:account",
      "statement:1", "timeout:1:30", "sql:1:rename table", "close-statement:1",
      "statement:2", "timeout:2:30", "sql:2:rename column", "close-statement:2",
      "validate:accounts", "record-history", "commit", "auto-commit:true", "isolation:8", "close"
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
      "read-history", "read-backfills", "validate-shared:account", "commit", "auto-commit:true", "isolation:8", "close"
    ))
    h.events.clear()
    h.driftAt = Set("account")
    assertEquals(h.refused(initial).state, FailureState.RolledBack)
  }

  test("a database without history that already contains the target is refused unless adoption is enabled") {
    val h = new Harness
    h.existing = Set("account")
    val error = h.refused(initial)
    assertEquals(error.state, FailureState.RolledBack)
    assert(error.getMessage.contains("no schema history but already contains account of the target schema"), error.getMessage)
    assert(error.getMessage.contains("enable adoptExistingSchema"), error.getMessage)
    assert(!h.events.exists(event => event.startsWith("sql:") || event.startsWith("validate:")), h.events)
  }

  test("an existing database that matches the target exactly is adopted as revision 1 without DDL") {
    val h = new Harness
    h.existing = Set("account")
    val adopt = ExecutionOptions(adoptExistingSchema = true)
    assertEquals(h.migrate(initial, adopt), MigrationResult(1, MigrationStatus.Adopted, 0))
    assertEquals(h.events.toVector, Vector(
      "connection", "get-auto-commit", "auto-commit:false", "read-committed", "lock", "initialize-history",
      "read-history", "read-backfills", "existing-relations", "validate:account", "record-history", "commit", "auto-commit:true", "isolation:8", "close"
    ))
    assertEquals(h.history, Vector(HistoryEntry(1, SchemaFingerprint.of(empty), SchemaFingerprint.of(initial),
      SchemaModelJson.encode(initial), Vector.empty)))
    h.events.clear()
    assertEquals(h.migrate(initial, adopt), MigrationResult(1, MigrationStatus.AlreadyApplied, 0))
    assertEquals(h.migrate(renamed, adopt), MigrationResult(2, MigrationStatus.Applied, 2))
    assertEquals(h.events.count(_ == "existing-relations"), 0)
  }

  test("adoption is refused when a table or sequence is missing or the database differs from the target") {
    val sequence = SequenceModel(SchemaId("account-sequence"), QualifiedName(SqlIdentifier("account_seq")), 1, 50)
    val adopt = ExecutionOptions(adoptExistingSchema = true)
    val partial = new Harness
    partial.existing = Set("account")
    val missing = partial.refused(SchemaModel(initial.tables, Vector(sequence)), adopt)
    assert(missing.getMessage.contains("requires every table and sequence of the target; missing: account_seq"), missing.getMessage)
    assert(!partial.events.exists(_.startsWith("validate:")), partial.events)
    val drifted = new Harness
    drifted.existing = Set("account")
    drifted.driftAt = Set("account")
    val drift = drifted.refused(initial, adopt)
    assert(drift.getMessage.contains("Adopted schema does not match database: physical schema drift"), drift.getMessage)
    assert(!drifted.events.contains("record-history"), drifted.events)
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

  test("an intended return to an earlier model runs with approvals for the revert and each rename back") {
    val h = new Harness
    h.seed(initial, renamed)
    val error = h.refused(initial, ExecutionOptions(approvals = Set(Approval.Revert(1))))
    assert(error.getMessage.contains("approve it with Approval.RenameBack(\"account\")"), error.getMessage)
    assert(error.getMessage.contains("approve it with Approval.RenameBack(\"account-name\")"), error.getMessage)
    assert(h.refused(initial).getMessage.contains("approve it with Approval.Revert(1)"))
    val approved = Set(Approval.Revert(1), Approval.RenameBack(account.id), Approval.RenameBack(name.id))
    assertEquals(h.migrate(initial, ExecutionOptions(approvals = approved)), MigrationResult(3, MigrationStatus.Applied, 2))
    assertEquals(SchemaModelJson.decode(h.history.last.model), Right(initial))
  }

  test("dropping a table, column or sequence deletes data and needs an approval per stable ID") {
    val bio = ColumnModel(SchemaId("account-bio"), SqlIdentifier("bio"), SqlType.Text)
    val note = TableModel(SchemaId("note"), QualifiedName(SqlIdentifier("note")), Vector(name.copy(id = SchemaId("note-text"))))
    val sequence = SequenceModel(SchemaId("account-sequence"), QualifiedName(SqlIdentifier("account_seq")), 1, 50)
    val h = new Harness
    h.seed(SchemaModel(Vector(account.copy(columns = Vector(name, bio)), note), Vector(sequence)))
    val error = h.refused(initial, ExecutionOptions(approvals = Set(Approval.Drop(note.id))))
    assert(error.getMessage.contains("Dropping column 'account-bio' (account.bio) deletes its data; " +
      "if intended, approve it with Approval.Drop(\"account-bio\")"), error.getMessage)
    assert(error.getMessage.contains("Dropping sequence 'account-sequence' (account_seq)"), error.getMessage)
    assert(!error.getMessage.contains("Dropping table"), error.getMessage)
    assert(!h.events.exists(event => event.startsWith("sql:") || event.startsWith("validate:")), h.events)
    val approved = Set[Approval](Approval.Drop(bio.id), Approval.Drop(note.id), Approval.Drop(sequence.id))
    val risk = h.refused(initial, ExecutionOptions(approvals = approved, allowedRisks = Set(RiskLevel.Safe, RiskLevel.Locking)))
    assert(risk.getMessage.contains("Migration risks are not allowed: Destructive"), risk.getMessage)
    assertEquals(h.migrate(initial, ExecutionOptions(approvals = approved)), MigrationResult(2, MigrationStatus.Applied, 3))
    assertEquals(h.history.last.statements, Vector("drop column", "drop tables", "drop sequence"))
  }

  test("a dropped ID is retired: it may never come back, not even with approvals") {
    val bio = ColumnModel(SchemaId("account-bio"), SqlIdentifier("bio"), SqlType.Text)
    val withBio = SchemaModel(Vector(account.copy(columns = Vector(name, bio))))
    val h = new Harness
    h.seed(withBio)
    assertEquals(h.migrate(initial, ExecutionOptions(approvals = Set(Approval.Drop(bio.id)))).revision, 2L)
    h.events.clear()
    val note = ColumnModel(SchemaId("account-note"), SqlIdentifier("note"), SqlType.Text)
    val reused = SchemaModel(Vector(account.copy(columns = Vector(name, note, bio.copy(name = SqlIdentifier("biography"))))))
    val error = h.refused(reused, ExecutionOptions(approvals = Set(Approval.Revert(1))))
    assert(error.getMessage.contains("Stable ID 'account-bio' was dropped after revision 1 and is retired"), error.getMessage)
    val reverted = h.refused(withBio, ExecutionOptions(approvals = Set(Approval.Revert(1))))
    assert(reverted.getMessage.contains("'account-bio' was dropped after revision 1"), reverted.getMessage)
    assert(!h.events.exists(_.startsWith("validate:")), h.events)
    assertEquals(h.migrate(SchemaModel(Vector(account.copy(columns = Vector(name, note))))).revision, 3L)
  }

  test("a change the executor cannot plan is recorded after an operator migrated it by hand") {
    val retyped = SchemaModel(Vector(account.copy(columns = Vector(name.copy(dataType = SqlType.Varchar(200))))))
    val fingerprint = SchemaFingerprint.of(retyped)
    val h = new Harness
    h.seed(initial)
    val refused = h.refused(retyped)
    assert(refused.getMessage.contains(s"start once with acceptManualMigration = \"$fingerprint\""), refused.getMessage)
    val other = h.refused(retyped, ExecutionOptions(acceptManualMigration = Some(SchemaFingerprint.of(renamed))))
    assert(other.getMessage.contains(s"but this target is $fingerprint"), other.getMessage)
    h.driftAt = Set("account")
    val drift = h.refused(retyped, ExecutionOptions(acceptManualMigration = Some(fingerprint)))
    assert(drift.getMessage.contains("Manually migrated schema does not match database"), drift.getMessage)
    h.driftAt = Set.empty
    h.events.clear()
    val manual = ExecutionOptions(acceptManualMigration = Some(fingerprint))
    assertEquals(h.migrate(retyped, manual), MigrationResult(2, MigrationStatus.ManuallyMigrated, 0))
    assertEquals(h.events.toVector, Vector(
      "connection", "get-auto-commit", "auto-commit:false", "read-committed", "lock", "initialize-history",
      "read-history", "read-backfills", "existing-relations", "validate:account", "record-history", "commit", "auto-commit:true", "isolation:8", "close"
    ))
    assertEquals(h.history.last, HistoryEntry(2, SchemaFingerprint.of(initial), fingerprint,
      SchemaModelJson.encode(retyped), Vector.empty))
    assertEquals(h.migrate(retyped, manual), MigrationResult(2, MigrationStatus.AlreadyApplied, 0))
    val fresh = new Harness
    assert(fresh.refused(initial, ExecutionOptions(acceptManualMigration = Some(SchemaFingerprint.of(initial))))
      .getMessage.contains("adoptExistingSchema"))
  }

  test("a manual migration is refused while a table or sequence the target no longer has still exists") {
    val sequence = SequenceModel(SchemaId("account-sequence"), QualifiedName(SqlIdentifier("account_seq")), 1, 50)
    val h = new Harness
    h.seed(SchemaModel(initial.tables, Vector(sequence)))
    h.existing = Set("account", "account_seq")
    val dropped = h.refused(empty, ExecutionOptions(acceptManualMigration = Some(SchemaFingerprint.of(empty))))
    assert(dropped.getMessage.contains("account, account_seq of the previous schema still exist"), dropped.getMessage)
    val manual = ExecutionOptions(acceptManualMigration = Some(SchemaFingerprint.of(renamed)))
    assert(h.refused(renamed, manual).getMessage.contains("account, account_seq of the previous schema still exist"))
    h.existing = Set("accounts")
    assertEquals(h.migrate(renamed, manual), MigrationResult(2, MigrationStatus.ManuallyMigrated, 0))
  }

  test("a column becomes required only after a count under the lock finds no NULL") {
    val required = SchemaModel(Vector(account.copy(columns = Vector(name.copy(nullable = false)))))
    val h = new Harness
    h.seed(initial)
    h.nullRows = 2
    val error = h.refused(required)
    assert(error.getMessage.contains("Column 'account-name' (account.name) becomes required, but 2 rows hold NULL; " +
      "register a backfill for it or fill the rows before the migration"), error.getMessage)
    assert(!h.events.exists(_.startsWith("sql:")), h.events)
    h.nullRows = 0
    h.events.clear()
    assertEquals(h.migrate(required), MigrationResult(2, MigrationStatus.Applied, 1))
    assertEquals(h.events.toVector.filter(e => e.startsWith("validate") || e.startsWith("query") || e.startsWith("sql"))
      .map(_.replaceAll(":[0-9]+:", ":")), Vector("validate:account", "query:count nulls name", "sql:set not null",
        "validate:account"))
    assertEquals(h.history.last.statements, Vector("set not null"))
    assertEquals(h.migrate(initial, ExecutionOptions(approvals = Set(Approval.Revert(1)))).statementCount, 1)
    assertEquals(h.history.last.statements, Vector("drop not null"))
  }

  private val required = SchemaModel(Vector(account.copy(columns = Vector(name.copy(nullable = false)))))
  private def fill(id: String, value: String = "Unknown", target: SchemaId = name.id) =
    Backfill.fillNulls(id, target, BackfillTrigger.BecomesRequired, BackfillValue.literal(value))

  test("a backfill fills the NULLs of a column that becomes required, before the count, and is recorded once") {
    val h = new Harness
    h.seed(initial)
    h.filledRows = 3
    val rule = fill("name-v1")
    val result = h.migrate(required, backfills = Vector(rule))
    assertEquals(result, MigrationResult(2, MigrationStatus.Applied, 2,
      Vector(BackfillOutcome("name-v1", BackfillResult.Executed, Some(3L)))))
    assertEquals(h.events.toVector.filter(e => e.startsWith("update") || e.startsWith("query") || e.startsWith("sql") ||
      e.startsWith("parameter") || e.startsWith("record")).map(_.replaceAll(":[0-9]+:", ":")), Vector(
      "parameter:1:Literal(Text(Unknown),Text)", "update:fill name", "query:count nulls name", "sql:set not null",
      "record-history", "record-backfill:name-v1"))
    assertEquals(h.history.last.statements, Vector("fill name", "set not null"))
    val record = h.backfills.head
    assertEquals((record.id, record.checksum, record.target, record.revision, record.result, record.updatedRows),
      ("name-v1", BackfillChecksum.of(rule), name.id, 2L, BackfillResult.Executed, Some(3L)))
    h.events.clear()
    assertEquals(h.migrate(required, backfills = Vector(rule)), MigrationResult(2, MigrationStatus.AlreadyApplied, 0))
    assert(!h.events.exists(e => e.startsWith("update") || e.startsWith("record")), h.events)
  }

  test("a recorded backfill with another definition blocks the start, even with the schema applied") {
    val h = new Harness
    h.seed(initial)
    h.migrate(required, backfills = Vector(fill("name-v1")))
    h.events.clear()
    val changed = h.refused(required, backfills = Vector(fill("name-v1", "Anonymous")))
    assertEquals(changed.state, FailureState.RolledBack)
    assert(changed.getMessage.contains("Backfill 'name-v1' was recorded in revision 2 with another definition; " +
      "a changed rule needs a new ID"), changed.getMessage)
    assert(!h.events.exists(_.startsWith("validate")), h.events)
  }

  test("NULLs left without a backfill, after a recorded one or after a pending one name the reason") {
    val h = new Harness
    h.seed(initial)
    h.nullRows = 4
    assert(h.refused(required).getMessage.contains("but 4 rows hold NULL; register a backfill for it"))
    assert(h.refused(required, backfills = Vector(fill("name-v1"))).getMessage
      .contains("but 4 rows hold NULL; backfill 'name-v1' left them NULL"))
    assertEquals(h.history.size, 1)
    assertEquals(h.backfills, Vector.empty)
    h.nullRows = 0
    h.migrate(required, backfills = Vector(fill("name-v1")))
    h.migrate(initial, ExecutionOptions(approvals = Set(Approval.Revert(1))), Vector(fill("name-v1")))
    h.nullRows = 1
    h.events.clear()
    assert(h.refused(SchemaModel(Vector(account.copy(columns = Vector(name.copy(name = SqlIdentifier("full"),
      nullable = false))))), backfills = Vector(fill("name-v1"))).getMessage
      .contains("backfill 'name-v1' was recorded in revision 2 and does not run again; a new rule for it needs a new ID"))
  }

  test("backfills that compete for a column, chain or do not type-check are refused before any change") {
    val h = new Harness
    h.seed(initial)
    val competing = h.refused(required, backfills = Vector(fill("a"), fill("b")))
    assert(competing.getMessage.contains("Backfills 'a', 'b' all fill column 'account-name'; only one may apply"),
      competing.getMessage)
    val count = ColumnModel(SchemaId("account-count"), SqlIdentifier("count"), SqlType.Integer)
    val typed = SchemaModel(Vector(account.copy(columns = Vector(name, count.copy(nullable = false)))))
    h.migrate(SchemaModel(Vector(account.copy(columns = Vector(name, count)))))
    h.events.clear()
    val wrong = h.refused(typed, backfills = Vector(fill("count-v1", "3", count.id)))
    assert(wrong.getMessage.contains("the constant '3' cannot fill a column of type Integer"), wrong.getMessage)
    val chained = SchemaModel(Vector(account.copy(columns = Vector(name.copy(nullable = false), count.copy(nullable = false)))))
    val chain = Backfill.fillNulls("count-v1", count.id, BackfillTrigger.BecomesRequired,
      BackfillValue.coalesce(BackfillValue.column(name.id), BackfillValue.literal(1L)))
    assert(h.refused(chained, backfills = Vector(fill("name-v1"), chain)).getMessage
      .contains("reads column 'account-name', which another backfill fills"))
    assert(!h.events.exists(e => e.startsWith("validate") || e.startsWith("update")), h.events)
    val invalid = new Harness
    assertEquals(invalid.refused(initial, backfills = Vector(fill(" "))).state, FailureState.NotStarted)
  }

  test("a backfill whose column a new table creates required, or that adoption finds required, is recorded but not run") {
    val bio = ColumnModel(SchemaId("profile-bio"), SqlIdentifier("bio"), SqlType.Text, nullable = false)
    val profile = TableModel(SchemaId("profile"), QualifiedName(SqlIdentifier("profile")), Vector(bio))
    val rules = Vector(fill("bio-v1", target = bio.id), fill("unused-v1", target = SchemaId("elsewhere")))
    val h = new Harness
    h.seed(initial)
    val created = h.migrate(SchemaModel(Vector(account, profile)), backfills = rules)
    assertEquals(created.backfills, Vector(BackfillOutcome("bio-v1", BackfillResult.NotRequiredOnCreation, None)))
    assertEquals(created.pendingBackfills, Vector("unused-v1"))
    assert(!h.events.exists(_.startsWith("update")), h.events)
    val adopted = new Harness
    adopted.existing = Set("account")
    val adoption = adopted.migrate(required, ExecutionOptions(adoptExistingSchema = true), Vector(fill("name-v1")))
    assertEquals(adoption.backfills, Vector(BackfillOutcome("name-v1", BackfillResult.Adopted, None)))
    assertEquals(adopted.backfills.map(r => (r.revision, r.result)), Vector(1L -> BackfillResult.Adopted))
    val manual = new Harness
    manual.seed(initial)
    manual.existing = Set("account")
    val options = ExecutionOptions(acceptManualMigration = Some(SchemaFingerprint.of(required)))
    assertEquals(manual.migrate(required, options, Vector(fill("name-v1"))).backfills,
      Vector(BackfillOutcome("name-v1", BackfillResult.Adopted, None)))
  }

  test("a failure after the fill rolls back the schema history and the backfill history together") {
    val h = new Harness
    h.seed(initial)
    h.failAt = Set("record-backfill:name-v1")
    assertEquals(h.refused(required, backfills = Vector(fill("name-v1"))).state, FailureState.RolledBack)
    assert(h.events.exists(_.startsWith("update")), h.events)
    assertEquals((h.history.size, h.backfills), (1, Vector.empty))
  }

  test("an inconsistent backfill history is refused before planning") {
    val rule = fill("name-v1")
    def record(revision: Long, format: Int = BackfillChecksum.Format) = BackfillRecord("name-v1", format,
      BackfillChecksum.of(rule), name.id, revision, SchemaFingerprint.of(empty), SchemaFingerprint.of(initial),
      BackfillResult.Adopted, None)
    Vector(record(2) -> "revision 2 with its fingerprints is not in the schema history",
      record(1, 9) -> "its definition format 9 is unknown", record(1).copy(updatedRows = Some(1)) -> "only an executed backfill"
    ).foreach { (broken, reason) =>
      val h = new Harness
      h.seed(initial)
      h.backfills = Vector(broken)
      val error = h.refused(required, backfills = Vector(rule))
      assert(error.getMessage.contains(s"Backfill history is inconsistent at backfill 'name-v1': $reason"), error.getMessage)
    }
  }

  test("renaming a sequence back to an earlier name is refused like an older server") {
    val sequence = SequenceModel(SchemaId("account-sequence"), QualifiedName(SqlIdentifier("account_seq")), 1, 50)
    val renamedSequence = sequence.copy(name = QualifiedName(SqlIdentifier("accounts_seq")))
    val h = new Harness
    h.seed(SchemaModel(initial.tables, Vector(sequence)), SchemaModel(initial.tables, Vector(renamedSequence)))
    val bio = ColumnModel(SchemaId("account-bio"), SqlIdentifier("bio"), SqlType.Text)
    val reverted = SchemaModel(initial.tables.map(t => t.copy(columns = t.columns :+ bio)), Vector(sequence))
    val error = h.refused(reverted)
    assert(error.getMessage.contains("Renaming sequence 'account-sequence' back to its name from revision 1"), error.getMessage)
  }

  test("unsupported changes, render errors and disallowed risks are refused under the lock before DDL") {
    val retyped = SchemaModel(Vector(account.copy(columns = Vector(name.copy(dataType = SqlType.Integer)))))
    val h = new Harness
    h.seed(initial)
    assert(h.refused(retyped).getMessage.contains("Changing type"))
    assert(h.events.containsSlice(Vector("read-history", "read-backfills", "rollback", "auto-commit:true", "isolation:8", "close")), h.events)
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
        // Auto-commit comes back only after the confirmed rollback.
        assertEquals(h.events.takeRight(4).toVector, Vector("rollback", "auto-commit:true", "isolation:8", "close"))
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

  test("commit failure remains OutcomeUnknown even if rollback succeeds; the ended transaction is restored") {
    val h = new Harness
    h.failAt = Set("commit")
    assertEquals(intercept[MigrationException](h.migrate(initial)).state, FailureState.OutcomeUnknown)
    assertEquals(h.events.takeRight(5).toVector, Vector("commit", "rollback", "auto-commit:true", "isolation:8", "close"))
    assertEquals(h.isolation, java.sql.Connection.TRANSACTION_SERIALIZABLE)
  }

  test("the connection goes back with its auto-commit mode and isolation level after success and rollback") {
    val applied = new Harness
    applied.migrate(initial)
    assertEquals(applied.events.takeRight(4).toVector, Vector("commit", "auto-commit:true", "isolation:8", "close"))
    val rolledBack = new Harness
    rolledBack.driftAt = Set("account")
    assertEquals(rolledBack.refused(initial).state, FailureState.RolledBack)
    assertEquals(rolledBack.events.takeRight(4).toVector, Vector("rollback", "auto-commit:true", "isolation:8", "close"))
    Vector(applied, rolledBack).foreach(h => assertEquals(h.isolation, java.sql.Connection.TRANSACTION_SERIALIZABLE))
  }

  test("a connection that cannot be restored is aborted, and the failure is reported even after a commit") {
    val committed = new Harness
    committed.failAt = Set("isolation:8")
    val error = intercept[MigrationException](committed.migrate(initial))
    assertEquals(error.state, FailureState.Committed)
    assert(error.getMessage.contains("committed revision 1"), error.getMessage)
    assertEquals(committed.history.size, 1)
    assertEquals(committed.events.takeRight(5).toVector, Vector("commit", "auto-commit:true", "isolation:8", "abort", "close"))
    val rolledBack = new Harness
    rolledBack.driftAt = Set("account")
    rolledBack.failAt = Set("auto-commit:true")
    val refused = rolledBack.refused(initial)
    assertEquals(refused.state, FailureState.RolledBack)
    assert(refused.getCause.getSuppressed.map(_.getMessage).contains("Failure at auto-commit:true"))
    assertEquals(rolledBack.events.takeRight(4).toVector, Vector("rollback", "auto-commit:true", "abort", "close"))
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
