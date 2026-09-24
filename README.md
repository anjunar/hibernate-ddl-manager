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
[architecture](docs/architecture.md) describes the design, the rules and the open points.

Status: prototype for PostgreSQL 14+, not yet a production-ready migration tool. Entities
with the common basic types, enums, `UUID` keys, `@ManyToOne`, `@OneToOne` and `@ManyToMany`
associations, element collections (sets and lists), unique constraints and indexes can be
mapped; generated numeric keys (sequences, identity columns), inheritance and `@Lob` cannot
yet.

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

```scala
val target = HibernateSchemaSource.read(metadata) match
  case Right(model) => model
  case Left(errors) => throw IllegalStateException(errors.mkString("\n"))

val executor = JdbcMigrationExecutor(
  PostgreSqlMigrationBackend,
  ExecutionOptions(lockTimeoutMillis = 5000, statementTimeoutMillis = 30000)
)
executor.migrate(dataSource, target)
```

`migrate` runs synchronously. On `Applied` or `AlreadyApplied` the server may continue; a
`MigrationException` must abort the startup. The executor needs a DataSource without JTA
enlistment, and `hibernate.hbm2ddl.auto` must not be `update`.

There are no migration IDs, checked-in snapshots or hand-maintained revisions. Every
migration stores its target model as JSON in `__hibernate_ddl.schema_history`, and the next
start plans against that model. Without history the previous model is the empty one, and
all tables are created. Because the IDs are stable across all versions, a server may skip
releases.

Under a transactional advisory lock the executor checks the history, plans the changes,
checks the database against the stored model, executes the DDL, checks the target and
writes the new revision, all in one transaction. Drift, an inconsistent history and an
older server after a newer migration block the startup.

## Modules

| sbt project | Contents |
| --- | --- |
| `schemaCore` | Model, validation, diff, operations |
| `schemaHibernate` | `@SchemaId` and `HibernateSchemaSource` |
| `schemaExecutor` | Transaction, planning against the stored model, history |
| `schemaPostgresql` | SQL, catalog checks, locking and history for PostgreSQL |
| `schemaCli` | Demo |

Package root: `com.anjunar.hibernateddl`. The core depends neither on Hibernate nor on a
database driver; the server provides the PostgreSQL JDBC driver.

## Version references

- [Scala 3.9.0](https://www.scala-lang.org/download/3.9.0.html)
- [sbt versions](https://www.scala-sbt.org/download)
- [Hibernate ORM versions](https://hibernate.org/orm/releases/)
