# Review: Hibernate DDL Manager

Stand: 25. September 2026, Commit `f265b8f`. Geprüft wurden der Hibernate-Importer, der PostgreSQL-Executor samt Zustandsprüfung und die automatische Integration beim Aufbau der `SessionFactory`. Die Befunde unten wurden mit PostgreSQL und Hibernate 7.4.10.Final reproduziert. `P1` bezeichnet einen Fehler, der eine falsche Schemafreigabe oder eine stillschweigende Abschwächung von Datenbankregeln ermöglicht; `P2` bezeichnet einen Fehler mit erheblicher Auswirkung in einem bestimmten Betriebsfall.

## Befunde

### 1. P1 – Hibernate-Spaltenoptionen gehen beim Import verloren

**Stelle:** [HibernateSchemaSource.scala, `columnModel`](modules/schema-hibernate/src/main/scala/com/anjunar/hibernateddl/hibernate/HibernateSchemaSource.scala#L430).

Der Importer lehnt Defaults, Generated-Ausdrücke und eigene Collations ab, prüft aber die frei formulierten Optionen einer Hibernate-Spalte nicht. Mit `@Column(options = "CHECK (score > 0)")` erzeugt Hibernate eine CHECK-Regel. `HibernateSchemaSource.read` liefert dennoch erfolgreich ein `ColumnModel` ohne diese Regel. Der Executor kann daraufhin eine Tabelle ohne die von der Entity verlangte Einschränkung anlegen und sie als gültigen Zielzustand protokollieren.

**Korrektur:** Nichtleere Spaltenoptionen vor der Modellfreigabe ablehnen, bis jede unterstützte Form ausdrücklich in das kanonische Modell übernommen und nach der Ausführung geprüft wird. Ein Integrationstest muss das von Hibernate erzeugte DDL mit dem importierten Modell für denselben Mapping-Fall abgleichen.

### 2. P1 – CHECK-Drift bleibt bei unverändertem Namen unentdeckt

**Stelle:** [PostgreSqlMigrationBackend.scala, `compareChecks`](modules/schema-postgresql/src/main/scala/com/anjunar/hibernateddl/postgresql/PostgreSqlMigrationBackend.scala#L327).

Die Katalogabfrage liest den Constraint-Namen, den Validierungsstatus und die Spalte, aber nicht den Ausdruck. Der aus der Soll-Definition berechnete Name beweist nicht, dass die Datenbank noch genau diesen Ausdruck enthält. In der Reproduktion wurde `score BETWEEN 1 AND 10` unter demselben Namen durch `score > -100` ersetzt. Ein Wert `-5` ließ sich speichern; der erneute Start mit dem ursprünglichen Modell lieferte dennoch `AlreadyApplied`.

**Korrektur:** Für die unterstützten CHECK-Formen den tatsächlichen Katalogausdruck lesen und nach einer eng definierten, PostgreSQL-spezifischen Normalisierung mit der Soll-Regel vergleichen. Nicht eindeutig interpretierbare Ausdrücke müssen die Freigabe blockieren. Ein Test sollte einen inhaltlich veränderten Ausdruck mit unverändertem Namen enthalten.

### 3. P2 – Eine manuelle Migration kann nicht ausgeführte Drops verbuchen

**Stelle:** [JdbcMigrationExecutor.scala, `acceptManual`](modules/schema-executor/src/main/scala/com/anjunar/hibernateddl/executor/JdbcMigrationExecutor.scala#L160).

`acceptManualMigration` prüft nur die Objekte des neuen Zielmodells. Bei einem leeren Ziel ist diese Prüfung leer: Nach einer erfolgreichen Tabellenanlage konnte der Test ohne manuelles DDL eine weitere Revision als `ManuallyMigrated` verbuchen, obwohl die vorher verwaltete Tabelle noch existierte. Sie ist anschließend aus der Zustandsprüfung verschwunden, während ihre ID als entfernt gilt. Dasselbe Grundproblem betrifft alte Namen nach einer angeblich manuell ausgeführten Umbenennung.

**Korrektur:** Neben dem Zielzustand die Abwesenheit aller vorher verwalteten Tabellen und Sequenzen prüfen, die im Ziel entfernt oder unter anderem Namen geführt werden. Für verbleibende Tabellen muss die Spaltenprüfung den gesamten verwalteten Umfang abdecken. Die Historie erst danach fortschreiben.

### 4. P2 – Die Integration gibt Pool-Verbindungen mit geänderter Isolation zurück

**Stellen:** [JdbcMigrationExecutor.scala, Transaktionsbeginn](modules/schema-executor/src/main/scala/com/anjunar/hibernateddl/executor/JdbcMigrationExecutor.scala#L37) und [SchemaMigrationIntegrator.scala, Rückgabe an den Provider](modules/schema-integration/src/main/scala/com/anjunar/hibernateddl/integration/SchemaMigrationIntegrator.scala#L63).

Der Executor setzt die Isolation auf `READ_COMMITTED`. Der Provider-Wrapper stellt beim Schließen nur `autoCommit` wieder her. Im Reproduktionstest kam eine ursprünglich `SERIALIZABLE`-Verbindung mit JDBC-Isolationswert `2` (`READ_COMMITTED`) statt `8` zurück. Ein Pool ohne eigenen Reset gibt sie anschließend mit schwächerer Isolation an die Anwendung weiter.

**Korrektur:** Ursprüngliche Isolation beim Entleihen erfassen und nach Ende der Migration vor der Rückgabe an den Provider wiederherstellen. Erfolg, bestätigten Rollback und ungewissen Commit-Ausgang getrennt testen, damit ein Reset-Fehler nicht als erfolgreicher Serverstart verborgen bleibt.

### 5. P2 – Änderungen an Identity-Sequenzen umgehen die Drift-Prüfung

**Stelle:** [PostgreSqlMigrationBackend.scala, `inspectColumns`](modules/schema-postgresql/src/main/scala/com/anjunar/hibernateddl/postgresql/PostgreSqlMigrationBackend.scala#L505).

Für eine Identity-Spalte wird geprüft, ob `attidentity = 'd'` gilt. Die Parameter ihrer impliziten Sequenz werden nicht geprüft; `inspectSequence` läuft nur für separat modellierte Sequenzen. Nach `ALTER TABLE public.review ALTER COLUMN id SET MAXVALUE 2 SET CYCLE` akzeptierte der Executor das ursprüngliche Modell weiterhin als bereits angewendet. Damit kann die Identitätsgenerierung Werte wiederholen und spätere Inserts an Schlüsselkollisionen scheitern lassen.

**Korrektur:** Die zur Identity-Spalte gehörige Sequenz über die PostgreSQL-Kataloge ermitteln und die im Modell vorausgesetzten Parameter einschließlich Start, Inkrement, Grenzen, Cache und Zyklus verifizieren. Nicht repräsentierte Abweichungen vor `AlreadyApplied`, Adoption und Ziel-Commit ablehnen.

## Verifikation

Der reguläre Lauf `sbt -batch test` bestand: **184 Tests erfolgreich, einer übersprungen** (der PostgreSQL-15-spezifische Fall auf PostgreSQL 14). Fünf zusätzliche, temporär in `schema-integration` ausgeführte Regressionstests schlugen jeweils an der erwarteten Stelle fehl und bestätigten die fünf Befunde. Die Reproduktionsquellen liegen unter `target/review/`; sie gehören nicht zum regulären Build. Die temporär in den Testbaum kopierten Dateien wurden danach entfernt.

Der Produktivcode wurde für dieses Review nicht geändert. Die Reihenfolge für eine Korrektur ist: zuerst den verlustbehafteten Hibernate-Import und die CHECK-Drift schließen, dann die manuelle Übernahmeprüfung, den Verbindungszustand und die Identity-Sequenzen absichern.
