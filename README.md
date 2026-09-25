# Hibernate DDL Manager

Scala 3 framework for controlled schema evolution from Hibernate metadata. The server calls
it at startup, before it builds the SessionFactory, and the framework changes the database
itself. It detects renames through stable IDs in the entities:

```scala
@Entity
@SchemaId("7f3a9c21")
@Table(name = "customer")
class Customer:
  @Id @SchemaId("0a1b2c3d") var id: java.lang.Long = uninitialized
  @SchemaId("f34e45b6") @Column(name = "nick_name") var nickName: String = uninitialized
```

When the field and column are renamed, the ID stays the same, and the framework generates:

```sql
ALTER TABLE "public"."customer" RENAME COLUMN "nick_name" TO "alias";
```

An ID consists of eight random hex digits, is assigned once and never changed. The
[architecture](docs/architecture.md) describes the design, the rules and the exact scope.

Status: 1.0 for PostgreSQL 14+ and Hibernate ORM 7.4, with a deliberately narrow scope.
Entities with the common basic types, enums, generated keys (UUIDs, sequences, identity
columns), inheritance (all three strategies), `@ManyToOne`, `@OneToOne` and `@ManyToMany`
associations, element collections (sets, lists, ordered lists and maps), secondary tables,
`@Lob`, JSON columns (`jsonb`), unique constraints and indexes can be mapped. The executor
creates, renames, adds and, with explicit approval, drops; it adopts a matching database
without history and accepts changes migrated by hand. Everything else, such as arrays,
collections inside embeddables or type changes, is refused with a message, never guessed.

Hibernate stores `@Lob` values, `Blob` and `Clob` on PostgreSQL as large objects in `oid`
columns. PostgreSQL does not delete a large object when its row is deleted or its value
replaced; run `vacuumlo` regularly to remove orphaned ones. The `lo` extension's
`lo_manage` trigger is no option here, because the executor refuses triggers on managed
tables.

`@JdbcTypeCode(SqlTypes.JSON)` maps a map, a list, a string or any other value to a `jsonb`
column, and on an `@Embedded` property the whole embeddable to one JSON document. Its
properties live inside the document and need no `@SchemaId`; the framework manages the
column, not the document's content. Hibernate guards an enum or a non-null property inside
such a document with a table check, which the model cannot represent, so these are refused.

## Getting started

Requirements: JDK 17 or newer and sbt. Pinned versions: Scala **3.9.0**, sbt **1.12.15**,
Hibernate ORM **7.4.10.Final**, MUnit **1.2.0**.

```sh
sbt test
sbt "schemaCli/run demo"
```

`sbt check` does a clean build and runs the tests. The PostgreSQL tests start a temporary
local database through `embedded-postgres` **2.2.2**; no credentials are needed. The demo
shows a column rename through a stable ID and the resulting SQL, without a database
connection.

### In the cloud

PostgreSQL refuses to start as root, so `embedded-postgres` usually does not run in cloud
containers. There the tests use a server installed with apt. Environment setup script:

```sh
apt-get update
command -v java || apt-get install -y openjdk-21-jdk-headless
apt-get install -y postgresql
curl -fsSL https://github.com/sbt/sbt/releases/download/v1.12.15/sbt-1.12.15.tgz | tar xz -C /opt
ln -sf /opt/sbt/bin/sbt /usr/local/bin/sbt
service postgresql start
su postgres -c "psql -c \"ALTER USER postgres PASSWORD 'postgres'\""
```

Environment variable:

```sh
HIBERNATE_DDL_TEST_POSTGRES=jdbc:postgresql://127.0.0.1:5432/postgres?user=postgres&password=postgres
```

When it is set, the tests use this server instead of `embedded-postgres`. Every test creates
its own temporary database and drops it afterwards. The server needs PostgreSQL 14 or
newer. If it is not running in a new session, `service postgresql start` is enough.

## Reading the Hibernate model

```scala
import com.anjunar.hibernateddl.hibernate.HibernateSchemaSource

val target: Either[Vector[String], SchemaModel] = HibernateSchemaSource.read(metadata)
```

`metadata` is the boot model (`MetadataSources.buildMetadata()`) with the dialect of the
target database. Missing or invalid IDs, duplicate IDs and everything the model cannot
represent come back as a list of errors; missing IDs come with a freshly generated
suggestion.

## Migrating the database at server startup

With `schema-integration` on the classpath, Hibernate settings switch the migration on:

```properties
hibernate.ddl_manager.enabled=true
hibernate.hbm2ddl.auto=validate
hibernate.default_schema=public
```

Every table and sequence needs an explicit schema, from `@Table(schema)` or
`hibernate.default_schema`.

While Hibernate builds the SessionFactory, and before its own schema validation, the
integrator reads the entities, migrates the database through one connection of Hibernate's
ConnectionProvider and gives the connection back. Any problem throws a
`MigrationException`, so the SessionFactory and the server do not start. Hibernate's own
schema management may at most validate: `hibernate.hbm2ddl.auto` and
`jakarta.persistence.schema-generation.database.action` must be unset, `none` or
`validate`. Further settings:

| Setting | Meaning |
| --- | --- |
| `hibernate.ddl_manager.adopt_existing_schema` | `true` adopts a matching database without history |
| `hibernate.ddl_manager.approvals` | Comma-separated `drop:<id>`, `rename-back:<id>`, `revert:<revision>` |
| `hibernate.ddl_manager.accept_manual_migration` | Fingerprint of a target migrated by hand |
| `hibernate.ddl_manager.lock_timeout_millis` | Default 5000 |
| `hibernate.ddl_manager.statement_timeout_millis` | Default 30000 |

An unknown `hibernate.ddl_manager.*` setting is an error, so a typo cannot switch off a
safeguard. With JTA or multi-tenancy the integrator refuses to run; there, and wherever
the server controls the bootstrap itself, call the migration explicitly between building
the metadata and building the SessionFactory, with a DataSource whose connections are not
enlisted in JTA:

```scala
import com.anjunar.hibernateddl.integration.HibernateSchemaMigration

val metadata = MetadataSources(registry).addAnnotatedClass(classOf[Customer]).buildMetadata()
HibernateSchemaMigration.migrate(metadata, dataSource, ExecutionOptions(lockTimeoutMillis = 5000))
val sessionFactory = metadata.buildSessionFactory()
```

`migrate` runs synchronously. On any `MigrationStatus` the server may continue; a
`MigrationException` must abort the startup. Settings in the registry refine the options
passed in. `JdbcMigrationExecutor` with `PostgreSqlMigrationBackend` is the same step
without Hibernate, for a `SchemaModel` from any source.

There are no migration IDs, checked-in snapshots or hand-maintained revisions. Every
migration stores its target model as JSON in `__hibernate_ddl.schema_history`, and the next
start plans against that model. Without history the previous model is the empty one, and
all tables are created. Because the IDs are stable across all versions, a server may skip
releases.

An existing database without history, for example one that `hbm2ddl` created, is refused
by default. With `ExecutionOptions(adoptExistingSchema = true)` the executor adopts it as
revision 1 without executing any DDL, but only if every table and sequence of the target
exists and the database matches the target exactly. One difference is common: Hibernate
names enum CHECK constraints the PostgreSQL way (`letter_status_check`), while the framework
names them after a hash of the column ID. The refusal lists both names; rename the constraint
with `ALTER TABLE … RENAME CONSTRAINT … TO …` and start again. The executor also compares
each check's definition, so a renamed constraint passes only if it enforces exactly the
modeled values. Hibernate's enum and discriminator checks do; an `@OrderColumn`'s
`position >= 0` differs from the modeled range and must be replaced.

Under a transactional advisory lock the executor checks the history, plans the changes,
checks the database against the stored model, executes the DDL, checks the target and
writes the new revision, all in one transaction. Drift, an inconsistent history and an
older server after a newer migration block the startup. While a migration runs, its tables
are locked exclusively. A start without changes only checks the database under a shared
lock that keeps schema changes out, so an application running on other nodes keeps reading
and writing.

### Drops and returns need an approval

Removing an entity, a property or a generated key drops its table, column or sequence, and
with it the data. A server with an older model would do the same, as well as rename things
back. The executor therefore refuses these changes until the options approve each one
explicitly:

```scala
ExecutionOptions(approvals = Set(
  Approval.Drop(SchemaId("7f3a9c21/f34e45b6")),  // a column, table or sequence
  Approval.RenameBack(SchemaId("7f3a9c21")),     // back to a name from an earlier revision
  Approval.Revert(3)                             // a target equal to revision 3
))
```

The refusal names every approval that the plan needs. Approvals only permit: one whose
change is not planned has no effect. A dropped ID is retired for good; reusing it is refused
even with approvals. Drops run last and without `CASCADE`, so a view that depends on a
dropped column makes the migration fail and roll back.

### Required columns

A new required column in an existing table is added nullable first; a column becomes
required with `SET NOT NULL` after the executor has counted under the lock that no row holds
NULL. Any NULL left makes the migration fail and roll back, so a new required column works
in an empty table, and a column becomes required once its rows are filled. A required
column becomes optional again with `DROP NOT NULL`; primary key and identity columns stay
required.

The values for existing rows come from a backfill that the application registers; the
framework never invents one:

```scala
val backfills = Vector(
  Backfill.fillNulls(
    id = "user-display-name-v1",
    target = SchemaId("7f3a9c21/4f5a6b7c"),
    when = BackfillTrigger.BecomesRequired,
    value = BackfillValue.coalesce(
      BackfillValue.concat(BackfillValue.column(SchemaId("7f3a9c21/1a2b3c4d")), BackfillValue.literal(" "),
        BackfillValue.column(SchemaId("7f3a9c21/2b3c4d5e"))),
      BackfillValue.literal("Unknown"))
  )
)
HibernateSchemaMigration.migrate(metadata, dataSource, backfills = backfills)
```

For the integrator, and for the explicit call as well, a `BackfillProvider` supplies rules
through `META-INF/services/com.anjunar.hibernateddl.integration.BackfillProvider`; the
explicit call adds its `backfills` to those, and an ID found twice is refused. Keep the rules
of every release that a server may still skip.

When the target column becomes required, the backfill runs
`UPDATE … SET "display_name" = … WHERE "display_name" IS NULL` right before `SET NOT NULL`,
with every constant as a JDBC parameter. Values are constants, other columns of the same
row by stable ID, `coalesce` and `concat` (NULL if any part is NULL); a constant must fit the
column without conversion or rounding, and a source column must have the target's type or
be text for text. A backfill runs once: it is recorded with its definition's checksum in
`__hibernate_ddl.backfill_history`, and the same ID with another definition blocks the start.
A column that a new table creates required, or that adoption or a manual migration finds
required, records the backfill without running it. Backfills for a release that a server
skips still run, from columns the same migration drops only afterwards. A rule whose column
does not become required stays pending and appears in `MigrationResult.pendingBackfills`.
Backfills run inside the migration's transaction while its tables are locked exclusively;
large tables and deployments without downtime need a separate, stepwise data migration.

### Changes the executor cannot plan

Type and primary key changes, among others, need a data migration and are refused. The refusal names the target's fingerprint. Change the database by hand to exactly
the target schema, then start once with
`ExecutionOptions(acceptManualMigration = Some("<fingerprint>"))`: the executor checks the
database against the target under the lock, checks that every table or sequence the target
dropped or renamed is gone under its old name, and records the target as the next revision
without executing DDL (`ManuallyMigrated`). An option naming another target is refused, so it cannot
accept a later change by accident.

## Modules

| sbt project | Contents |
| --- | --- |
| `schemaCore` | Model, validation, diff, operations |
| `schemaHibernate` | `@SchemaId` and `HibernateSchemaSource` |
| `schemaExecutor` | Transaction, planning against the stored model, history |
| `schemaPostgresql` | SQL, catalog checks, locking and history for PostgreSQL |
| `schemaIntegration` | `HibernateSchemaMigration` and the opt-in Hibernate integrator |
| `schemaCli` | Demo |

Package root: `com.anjunar.hibernateddl`. The core depends neither on Hibernate nor on a
database driver; the server provides the PostgreSQL JDBC driver.

## Version references

- [Scala 3.9.0](https://www.scala-lang.org/download/3.9.0.html)
- [sbt versions](https://www.scala-sbt.org/download)
- [Hibernate ORM versions](https://hibernate.org/orm/releases/)
