# Hibernate DDL Manager

Controlled schema evolution from Hibernate metadata. The server migrates the database at startup, and renames stay
renames because every table and column carries a stable ID.

| Version | Platform | Scala | License |
| --- | --- | --- | --- |
| 1.0.1 | JVM | 3.9 | MIT |

Documentation: [English](https://docs.anjunar.com/en/hibernate-ddl-manager) · [Deutsch](https://docs.anjunar.com/de/hibernate-ddl-manager)
Website: [English](https://anjunar.com/en/hibernate-ddl-manager) · [Deutsch](https://anjunar.com/de/hibernate-ddl-manager)

## Installation

`schema-integration` brings every other module along. The application provides Hibernate ORM 7.4 and the PostgreSQL
JDBC driver. The artifacts are built with Scala 3.9 for Java 17 or newer, so a Scala project needs Scala 3.9 or later.

```scala
libraryDependencies += "com.anjunar.hibernateddl" %% "schema-integration" % "1.0.1"
```

```xml
<dependency>
  <groupId>com.anjunar.hibernateddl</groupId>
  <artifactId>schema-integration_3</artifactId>
  <version>1.0.1</version>
</dependency>
```

## First example

The entity and its properties carry IDs. Rename the field and its column, keep the ID, and the next start renames the
column in place:

```scala
import com.anjunar.hibernateddl.hibernate.annotation.SchemaId
import jakarta.persistence.*
import scala.compiletime.uninitialized

@Entity
@SchemaId("7f3a9c21")
@Table(name = "customer")
class Customer:
  @Id @SchemaId("0a1b2c3d") var id: java.lang.Long = uninitialized
  @SchemaId("f34e45b6") @Column(name = "alias") var alias: String = uninitialized // was nick_name
```

```sql
ALTER TABLE "public"."customer" RENAME COLUMN "nick_name" TO "alias";
```

Three Hibernate settings turn the migration on. Hibernate itself may only validate afterwards.

```properties
hibernate.ddl_manager.enabled=true
hibernate.hbm2ddl.auto=validate
hibernate.default_schema=public
```

## The principle

**01 / Identity – Stable IDs instead of names.** Eight random hex digits per entity and property, assigned once.
Names may change freely; the ID tells the framework what stayed.

**02 / History – A stored model instead of scripts.** Every migration stores its target model in the database. The
next start plans against it, so a server may skip releases.

**03 / Safety – Refuse instead of guess.** What loses data needs an approval, what cannot be planned is refused with
a message. Nothing is guessed.

## Contents

Start with stable IDs and the startup migration; the other pages cover changes that need a decision.

**Basics**
- [Stable IDs](https://docs.anjunar.com/en/hibernate-ddl-manager/stable-ids) – how `@SchemaId` turns a rename into a rename instead of a drop and a create
- [Migrating at startup](https://docs.anjunar.com/en/hibernate-ddl-manager/startup) – the integrator, the explicit call and what happens inside one migration

**Changes**
- [Approvals](https://docs.anjunar.com/en/hibernate-ddl-manager/approvals) – drops, renames back and reverts run only when each one is approved
- [Required columns and backfills](https://docs.anjunar.com/en/hibernate-ddl-manager/backfills) – how a column becomes required, and how existing rows get their values

**Operations**
- [Preview](https://docs.anjunar.com/en/hibernate-ddl-manager/preview) – what the next start would do to a database, read-only, from code or the command line
- [Existing databases](https://docs.anjunar.com/en/hibernate-ddl-manager/existing-databases) – adopting a database without history and accepting changes migrated by hand

**Reference**
- [Settings](https://docs.anjunar.com/en/hibernate-ddl-manager/settings) – every `hibernate.ddl_manager` setting and every execution option
- [Scope and limits](https://docs.anjunar.com/en/hibernate-ddl-manager/scope) – what can be mapped, which changes are planned and what is refused

## Limits

Version 1.0 targets PostgreSQL 14+ and Hibernate ORM 7.4, with a deliberately narrow scope.

- **Mapped:** the common basic types, enums, generated keys (UUIDs, sequences, identity columns), inheritance with
  all three strategies, `@ManyToOne`, `@OneToOne` and `@ManyToMany`, element collections (sets, lists, ordered lists
  and maps), secondary tables, `@Lob`, JSON columns (`jsonb`), unique constraints and indexes.
- **Planned without a decision:** create, rename, add, and widen a column (a longer `VARCHAR`, `INTEGER` to
  `BIGINT`, a larger `NUMERIC` precision with the same scale); replace and drop indexes, replace unique keys.
- **Planned with an approval:** dropping a table, column or sequence, renaming back to an earlier name, reverting to
  an earlier revision, dropping a unique key.
- **Refused with a message:** mappings the model cannot represent, such as arrays or collections inside embeddables,
  and changes that need a data migration, such as any other type change or a primary key change. Migrate the latter
  by hand and accept the result with `acceptManualMigration`.
- With JTA or multi-tenancy the integrator refuses to run; call `HibernateSchemaMigration.migrate` explicitly
  between building the metadata and the SessionFactory.
- `@Lob`, `Blob` and `Clob` become PostgreSQL large objects that are not deleted with their row. Run `vacuumlo`
  regularly; the `lo_manage` trigger is no option because the executor refuses triggers on managed tables.

## Modules

| sbt project | Contents |
| --- | --- |
| `schemaCore` | Model, validation, diff, operations |
| `schemaHibernate` | `@SchemaId` and `HibernateSchemaSource` |
| `schemaExecutor` | Transaction, planning against the stored model, history, preview |
| `schemaPostgresql` | SQL, catalog checks, locking and history for PostgreSQL |
| `schemaIntegration` | `HibernateSchemaMigration` and the opt-in Hibernate integrator |
| `schemaCli` | Demo and the read-only `preview` command, not published |

Package root: `com.anjunar.hibernateddl`. The core depends neither on Hibernate nor on a database driver.

## Development

Requirements: JDK 17 or newer and sbt 2. Pinned versions: Scala 3.9.0, sbt 2.0.9, Hibernate ORM 7.4.10.Final,
MUnit 1.2.0.

```bash
sbt --server check
sbt "schemaCli/run demo"
```

`check` does a clean build and runs every test with `testFull`; sbt 2's `test` is incremental and replays unchanged
tests from its cache, even after `clean`. The PostgreSQL tests start a temporary database through
`embedded-postgres` 2.2.2, no credentials needed. The demo shows a column rename through a stable ID and the
resulting SQL, without a database connection.

### In the cloud

PostgreSQL refuses to start as root, so `embedded-postgres` usually does not run in cloud containers. There the tests
use a server installed with apt:

```bash
apt-get update
command -v java || apt-get install -y openjdk-21-jdk-headless
apt-get install -y postgresql
curl -fsSL https://github.com/sbt/sbt/releases/download/v2.0.9/sbt-2.0.9.tgz | tar xz -C /opt
ln -sf /opt/sbt/bin/sbt /usr/local/bin/sbt
service postgresql start
su postgres -c "psql -c \"ALTER USER postgres PASSWORD 'postgres'\""
```

```bash
export HIBERNATE_DDL_TEST_POSTGRES='jdbc:postgresql://127.0.0.1:5432/postgres?user=postgres&password=postgres'
```

When the variable is set, the tests use this server instead of `embedded-postgres`. Every test creates its own
temporary database and drops it afterwards. If the server is not running in a new session,
`service postgresql start` is enough.

### Releasing

Set the version in `build.sbt` and in this README (facts row and installation examples) with one command; `--check`
changes nothing and fails when the files disagree, as CI does on every push:

```bash
scripts/set-version.sh 1.0.1
scripts/set-version.sh --check
```

After `sbt --server check` passes, one script signs every published module, bundles `target/sona-staging` and
uploads it to the Sonatype Central Portal. It waits until Maven Central has published the release:

```powershell
.\scripts\publish-central.ps1
```

```bash
scripts/publish-central.sh
```

Without a version the scripts read the one in `build.sbt`. Credentials come from `SONATYPE_CENTRAL_USERNAME` and
`SONATYPE_CENTRAL_PASSWORD`, or from the lines `user=` and `password=` in `~/.sbt/sonatype_central_credentials`.
`-PublishingType USER_MANAGED` (`--publishing-type USER_MANAGED`) stops after validation so the release is published
by hand in the portal; `-SkipPublishSigned` uploads an existing staging directory again. A `-SNAPSHOT` version goes
to Central's snapshot repository with `sbt --server publishSigned` instead.

## License

Hibernate DDL Manager is available under the [MIT License](LICENSE).
