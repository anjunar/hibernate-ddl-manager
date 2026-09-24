# Hibernate DDL Manager

Scala-3-Framework für kontrollierte Schema-Evolution aus Hibernate-Metadaten. Der Server
ruft es beim Start auf, bevor er die SessionFactory baut, und das Framework ändert die
Datenbank selbst. Umbenennungen erkennt es über stabile IDs in den Entities:

```scala
@Entity
@SchemaId("7f3a9c21")
@Table(name = "customer")
class Customer:
  @Id @SchemaId("0a1b2c3d") var id: java.lang.Long = uninitialized
  @SchemaId("f34e45b6") @Column(name = "nick_name") var nickName: String = uninitialized
```

Werden Feld und Spalte umbenannt, bleibt die ID gleich, und das Framework erzeugt:

```sql
ALTER TABLE "public"."customer" RENAME COLUMN "nick_name" TO "alias";
```

Eine ID besteht aus acht zufälligen Hex-Ziffern, wird einmal vergeben und nie geändert.
Aufbau, Regeln und offene Punkte beschreibt die [Architektur](docs/architecture.md).

Stand: Prototyp für PostgreSQL 14+, noch kein produktionsfähiges Migrationstool. Eine
typische Entity mit `UUID`, Zeitstempeln oder Fremdschlüsseln ist noch nicht abbildbar.

## Starten

Voraussetzungen: JDK 17 oder neuer und sbt. Fixierte Versionen: Scala **3.9.0**,
sbt **1.12.15**, Hibernate ORM **7.4.10.Final**, MUnit **1.2.0**.

```sh
sbt test
sbt "schemaCli/run demo"
```

`sbt check` baut sauber und testet. Die PostgreSQL-Tests starten über `embedded-postgres`
**2.2.2** eine temporäre lokale Datenbank; Zugangsdaten sind nicht nötig. Die Demo zeigt
einen Spalten-Rename über eine stabile ID und das zugehörige SQL, ohne Datenbankverbindung.

### In der Cloud

PostgreSQL verweigert den Start als root, deshalb läuft `embedded-postgres` in
Cloud-Containern meist nicht. Dort nutzen die Tests einen per apt installierten Server.
Setup-Skript der Umgebung:

```sh
apt-get update
command -v java || apt-get install -y openjdk-21-jdk-headless
apt-get install -y postgresql
curl -fsSL https://github.com/sbt/sbt/releases/download/v1.12.15/sbt-1.12.15.tgz | tar xz -C /opt
ln -sf /opt/sbt/bin/sbt /usr/local/bin/sbt
service postgresql start
su postgres -c "psql -c \"ALTER USER postgres PASSWORD 'postgres'\""
```

Umgebungsvariable:

```sh
HIBERNATE_DDL_TEST_POSTGRES=jdbc:postgresql://127.0.0.1:5432/postgres?user=postgres&password=postgres
```

Ist sie gesetzt, verwenden die Tests diesen Server statt `embedded-postgres`. Jeder Test legt
eine eigene temporäre Datenbank an und löscht sie danach wieder. Der Server braucht
PostgreSQL 14 oder neuer. Läuft er in einer neuen Session nicht, genügt
`service postgresql start`.

## Hibernate-Modell lesen

```scala
import io.github.hibernateddl.hibernate.HibernateSchemaSource

val target: Either[Vector[String], SchemaModel] = HibernateSchemaSource.read(metadata)
```

`metadata` ist das Boot-Modell (`MetadataSources.buildMetadata()`) mit dem Dialekt der
Zieldatenbank. Fehlende oder ungültige IDs, doppelte IDs und alles, was das Modell nicht
abbilden kann, kommen als Fehlerliste zurück; für fehlende IDs mit einem frisch erzeugten
Vorschlag.

## Datenbank beim Serverstart migrieren

```scala
val executor = JdbcMigrationExecutor(
  PostgreSqlMigrationBackend,
  ExecutionOptions(lockTimeoutMillis = 5000, statementTimeoutMillis = 30000)
)
executor.migrate(dataSource, MigrationRequest("002-customer-alias", previous, target))
```

`migrate` arbeitet synchron. Bei `Applied` oder `AlreadyApplied` darf der Server
fortfahren; eine `MigrationException` muss den Start abbrechen. Der Executor braucht eine
DataSource ohne JTA-Einbindung, und `hibernate.hbm2ddl.auto` darf nicht `update` sein.

Vorgänger- und Zielmodell werden heute als `SchemaSnapshot` mit fortlaufenden Revisionen
übergeben; der erste Vorgänger hat Revision `0`. Unter einem transaktionalen Advisory-Lock
prüft der Executor die Datenbank gegen den Vorgänger, führt die DDL aus, prüft das Ziel und
schreibt die History in `__hibernate_ddl.schema_history`, alles in einer Transaktion.
Drift, abweichende Pläne und ein älterer Server nach einer neueren Migration blockieren
den Start.

## Module

| sbt-Projekt | Inhalt |
| --- | --- |
| `schemaCore` | Modell, Validierung, Diff, Operationen |
| `schemaHibernate` | `@SchemaId` und `HibernateSchemaSource` |
| `schemaExecutor` | Transaktion, Planprüfung, History-Abgleich |
| `schemaPostgresql` | SQL, Katalogprüfung, Sperren und History für PostgreSQL |
| `schemaCli` | Demo |

Paketbasis: `io.github.hibernateddl`. Der Kern hängt weder von Hibernate noch von einem
Datenbanktreiber ab; den PostgreSQL-JDBC-Treiber liefert der Server.

## Versionsreferenzen

- [Scala 3.9.0](https://www.scala-lang.org/download/3.9.0.html)
- [sbt-Versionen](https://www.scala-sbt.org/download)
- [Hibernate ORM-Versionen](https://hibernate.org/orm/releases/)
