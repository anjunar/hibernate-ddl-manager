# Arbeitspaket: Backfills und nachträgliche Pflichtfelder

Status: Konzept zur Umsetzung, noch nicht implementiert.

Dieses Arbeitspaket beschreibt Backfills beim Serverstart als Teil einer
Schemaänderung. Es basiert auf dem im Chat besprochenen Konzept und ergänzt die
[bestehende Architektur](architecture.md). Die nachfolgenden API- und Typnamen
sind Entwurfsvorschläge, keine bereits verfügbaren Funktionen.

## Ziel

Das Framework soll neue Pflichtspalten in bestehenden Tabellen und den Wechsel
von nullable zu `NOT NULL` selbstständig durchführen können. Notwendige Werte für
bestehende Datensätze liefert eine ausdrücklich registrierte Backfill-Regel.

Das Hibernate-Mapping beschreibt den gewünschten Schema-Zustand. Die Anwendung
beschreibt die fachliche Befüllungsregel. Das Framework plant die Reihenfolge,
führt die Änderungen aus, prüft das Ergebnis und dokumentiert es dauerhaft.

Es werden keine Ersatzwerte erfunden und keine Scala-Feldinitialisierungen als
Befüllungsregel interpretiert. Bereits vorhandene Nicht-NULL-Werte bleiben erhalten.

## Ausgangslage

- `ColumnModel` enthält die Nullbarkeit bereits; sie gehört zum gespeicherten
  Schema und dessen Fingerprint.
- `DiffEngine` lehnt Änderungen der Nullbarkeit sowie neue nicht-nullbare
  Spalten in bestehenden Tabellen derzeit ab.
- Auch der PostgreSQL-Dialekt lehnt ein direktes `AddColumn` mit `nullable = false`
  ab. Der neue Plan muss deshalb ein nullable Zwischenstadium rendern und danach
  eine gesonderte `SetNotNull`-Operation ausführen.
- Neue Tabellen können bereits direkt mit Pflichtspalten angelegt werden.
- `JdbcMigrationExecutor` führt derzeit gerenderte DDL-Statements aus. Ein
  eigener Ausführungsschritt für Datenänderungen und NULL-Prüfungen fehlt.
- Der Executor verwendet eine eigene JDBC-Transaktion, Migrationssperren und
  Schema-Prüfungen vor und nach der Änderung.
- Die Schemahistorie enthält Modelle, Fingerprints und Statements, aber keine
  Identitäten oder Prüfsummen von Backfill-Regeln.
- Der bisherige `AlreadyApplied`-Pfad entscheidet anhand des Schema-Fingerprints
  und prüft anschließend den tatsächlichen Datenbankzustand.

Ein allgemeiner Planer für beliebige Datenabhängigkeiten ist bislang nicht
vorhanden. Die bestehende deterministische Operationsfolge muss für die hier
definierten Fälle erweitert werden.

## Unterstützte Änderungen

| Änderung | Geplanter Ablauf |
| --- | --- |
| Neue Pflichtspalte in einer gefüllten Tabelle | Nullable anlegen, fehlende Werte befüllen, prüfen, `NOT NULL` setzen. |
| Bestehende nullable Spalte wird Pflichtfeld | Fehlende Werte bei Bedarf befüllen, prüfen, `NOT NULL` setzen. |
| Bestehende Spalte enthält schon keine NULL-Werte | Prüfen und `NOT NULL` setzen; keine Befüllungsregel erforderlich. |
| Neue Pflichtspalte in einer leeren bestehenden Tabelle | Nullable anlegen, Leerzustand unter Sperre prüfen, `NOT NULL` setzen. |
| Neue Tabelle | Direkt mit der gewünschten Nullbarkeit erstellen; kein Daten-Backfill erforderlich. |
| Pflichtfeld wird optional | `NOT NULL` entfernen, soweit Primärschlüssel- und Identity-Regeln dies erlauben. |

Fehlt eine erforderliche Regel oder bleiben nach der Befüllung NULL-Werte übrig,
schlägt die Migration fehl. Eine neue Pflichtspalte wird nicht grundsätzlich ohne
Regel abgelehnt: Eine leere Tabelle benötigt keinen erfundenen Standardwert.

`NOT NULL` untersagt ausschließlich NULL-Werte. Leere Zeichenketten und fachlich
ungeeignete Ersatzwerte benötigen eigene Regeln beziehungsweise Constraints.
Ein Backfill setzt keinen dauerhaften Datenbank-Default für spätere Inserts.

## Deklaration der Backfills

Ein Backfill besitzt:

- Eine dauerhaft eindeutige Backfill-ID, unabhängig von den Schema-IDs.
- Die stabile Schema-ID der Zielspalte.
- Eine ausdrückliche Anwendungsbedingung.
- Einen typisierten, deklarativen Ausdruck für den neuen Wert.

Für dieses Paket genügt die Anwendungsbedingung `BecomesRequired`: Die Zielspalte
wird entweder neu als Pflichtspalte eingeführt oder von nullable auf `NOT NULL`
umgestellt. Die Bedingung wird aus bisherigem Modell und Zielmodell abgeleitet.
Lokale Revisionsnummern einer Datenbank sind keine Versionskennzeichen der
Anwendung und dürfen die Auswahl einer Regel nicht bestimmen.

Vorgeschlagene API:

```scala
val backfills = Vector(
  Backfill.fillNulls(
    id = "user-display-name-v1",
    target = SchemaId("7f3a9c21/4f5a6b7c"),
    when = BackfillTrigger.BecomesRequired,
    value = BackfillValue.literal("Unbekannt")
  )
)

HibernateSchemaMigration.migrate(
  metadata = metadata,
  dataSource = dataSource,
  backfills = backfills
)
```

Die bestehenden Aufrufe ohne Backfills sollen weiterhin verwendbar bleiben.
Der Ersatzwert „Unbekannt“ ist hier eine fachliche Entscheidung der Anwendung.

Die erste Ausdrucksmenge umfasst:

| Ausdruck | Bedeutung |
| --- | --- |
| `Literal` | Typisierter konstanter Wert. |
| `Column` | Wert einer anderen Spalte derselben Zeile, referenziert über ihre Schema-ID. |
| `Coalesce` | Erster Nicht-NULL-Wert der angegebenen Ausdrücke. |
| `Concat` | Verkettung von Textausdrücken; liefert NULL, wenn ein Bestandteil NULL ist. Ersatzwerte müssen ausdrücklich mit `Coalesce` angegeben werden. |

Typen und Zuweisbarkeit werden vor der Ausführung geprüft. Nicht unterstützte
Typen, implizite Typkonvertierungen und unbekannte Spaltenreferenzen werden mit
einer konkreten Meldung abgelehnt. Quellspalten müssen zur gleichen Tabelle wie
die Zielspalte gehören. Die erste Version unterstützt keine Ketten, bei denen
eine Regel auf das Ergebnis einer anderen Regel angewiesen ist.

Werte werden als JDBC-Parameter gebunden; Tabellen- und Spaltennamen werden aus
dem Modell aufgelöst und korrekt gequotet. Beliebiges SQL, JDBC-Callbacks,
EntityManager-Zugriffe und externe Aufrufe gehören nicht zu dieser API.

Die Befüllung entspricht stets diesem Prinzip:

```sql
UPDATE "public"."users"
SET "display_name" = ?
WHERE "display_name" IS NULL;
```

## Planung und Ausführung

Der Plan erhält unterscheidbare Schritte für DDL, Datenänderungen und
Datenprüfungen. Vorgeschlagene Operationen sind `SetNotNull`, `DropNotNull`,
`FillNulls` und `AssertNoNulls`. Eine Datenprüfung muss ein Ergebnis auswerten
können; sie darf nicht wie ein beliebiges DDL-Statement behandelt werden.

Der Ablauf auf einer Verbindung und innerhalb einer Transaktion:

1. Bestehende Migrationssperre erwerben, Historien lesen und ihre Konsistenz
   prüfen. Bekannte Backfill-IDs mit den mitgelieferten Definitionen vergleichen.
2. Schema- und Datenänderungen gemeinsam planen. Statische Fehler und nicht
   unterstützte Abhängigkeiten vor der Änderung von Anwendungstabellen ablehnen.
3. Bestehende Tabellen sperren und den tatsächlichen Zustand gegen das bisherige
   Modell prüfen.
4. Unterstützte Umbenennungen durchführen und neue Pflichtspalten zunächst
   nullable anlegen. Das endgültige Zielmodell bleibt dabei unverändert.
5. Anwendbare Backfills ausschließlich auf Zeilen mit NULL in der Zielspalte
   ausführen und die Anzahl geänderter Zeilen erfassen.
6. Unter derselben Sperre prüfen, ob noch NULL-Werte vorhanden sind. Ohne
   Befüllungsregel entscheidet diese Prüfung, ob die Verschärfung zulässig ist.
7. `NOT NULL` setzen und weitere Constraints in der für den Plan erforderlichen
   Reihenfolge herstellen. Nicht mehr benötigte Quellspalten erst nach ihren
   Backfills entfernen.
8. Das vollständige Zielmodell gegen die Datenbank prüfen, beide Historien
   schreiben und gemeinsam committen.

Neue oder geänderte CHECK-, Unique- und Fremdschlüsselbedingungen müssen bei der
Reihenfolge berücksichtigt werden. Unauflösbare Zwischenzustände werden vorab
abgelehnt. Datenabhängige Verletzungen führen zum Rollback. Eine gültige SQL-
Anweisung allein genügt nicht als Nachweis für einen korrekten Backfill.

Bei einer fehlgeschlagenen Migration wird der Serverstart abgebrochen. Die
bestehenden Drop-, Rückbenennungs- und Revert-Freigaben bleiben gültig; ein Backfill
erteilt keine zusätzliche Berechtigung zum Löschen von Objekten.

## Historie und Wiederanläufe

Die zusätzliche Tabelle `__hibernate_ddl.backfill_history` soll mindestens
folgende Informationen enthalten:

| Information | Zweck |
| --- | --- |
| Backfill-ID | Eindeutige Identität der Regel. |
| Definitionsprüfsumme | Erkennung nachträglich geänderter Regeln. |
| Zielspalten-ID | Zuordnung unabhängig von physischen Namen. |
| Zugehörige Schema-Revision und Schema-Fingerprints | Nachvollziehbarer Ausgangs- und Zielzustand. |
| Ergebnis | Ausgeführt, bei Neuanlage nicht erforderlich oder als Ausgangszustand übernommen. |
| Anzahl geänderter Zeilen | Tatsächlicher Umfang; ohne UPDATE nicht als ausgeführte Befüllung darstellen. |
| Zeitpunkt | Zeitpunkt der dokumentierten Anwendung. |

Die Prüfsumme wird aus der kanonischen Definition einschließlich Ziel,
Anwendungsbedingung, Ausdruckssemantik und typisierten Literalen berechnet.
Klassen- oder Methodennamen und eine manuell gesetzte Versionsnummer reichen
dafür nicht aus. Das Definitionsformat wird versioniert.

Eine bekannte Backfill-ID mit geänderter Definition ist ein Fehler. Eine neue
fachliche Regel benötigt eine neue ID; dadurch entsteht aber noch kein
ausführbarer Schemaübergang. Eine neue ID darf auch nicht dazu dienen, einen
bereits abgeschlossenen Backfill automatisch zu wiederholen.

- Erfolgreich dokumentierte Backfills werden nicht erneut ausgeführt. Sind bei
  einer späteren Verschärfung erneut NULL-Werte vorhanden, wird eine passende
  neue Regel benötigt oder die Migration schlägt fehl.
- Nach einem bestätigten Rollback existiert kein erfolgreicher Historieneintrag;
  ein neuer Start darf den gesamten Versuch wiederholen.
- Bei unbekanntem Commit-Ausgang muss ein neuer Versuch zunächst beide Historien
  unter der Migrationssperre erneut lesen. Es darf nicht blind wiederholt werden.
- Ein Fehler beim Wiederherstellen der Verbindung nach einem bestätigten Commit
  bleibt `FailureState.Committed`; er darf nicht als Rollback ausgegeben werden.
- Prüfungen bekannter Definitionsprüfsummen müssen vor dem bisherigen
  `AlreadyApplied`-Rücksprung stattfinden.
- Eine neue Regel bei unverändertem Schema startet in diesem Paket keinen
  eigenständigen Datenlauf. Die Planung meldet den fehlenden Schemaübergang.

Ein Transaktions-Rollback nimmt DDL, Backfills und neue Historieneinträge gemeinsam
zurück. Das garantiert höchstens eine erfolgreich dokumentierte Ausführung je
Backfill-ID, nicht die einmalige Ausführung jedes versuchten UPDATEs.

Die neue Historie wird unabhängig versioniert. Das vorhandene Layout von
`schema_history`, bisher gespeicherte Modelle und ihre Fingerprints bleiben
kompatibel. Backfill-Definitionen werden nicht in den Schema-Fingerprint
eingemischt. Fehlerhafte oder inkompatible Historientabellen werden abgelehnt.

## Neuanlage, Übernahme und Versionssprünge

Bei einer neu angelegten Tabelle wird das endgültige Schema direkt erstellt.
Zugehörige Regeln werden mit ihrer Prüfsumme als „bei Neuanlage nicht erforderlich“
dokumentiert. Historische Quellspalten müssen dafür nicht künstlich erzeugt werden.

Bei `adoptExistingSchema` und beim Akzeptieren einer manuellen Migration wird die
tatsächliche Pflichtfeldbedingung mit dem Zielzustand geprüft. Ein entsprechender
Historieneintrag dokumentiert die geprüfte Übernahme. Er behauptet nicht, dass
das Framework die fachliche Befüllungsregel ausgeführt hat.

Ein Beispiel für übersprungene Releases:

1. Version 1 besitzt `firstName` und `lastName`.
2. Version 2 führt das Pflichtfeld `displayName` mit einer Befüllungsregel ein.
3. Version 3 entfernt `firstName` und `lastName`.

Beim direkten Upgrade von Version 1 auf Version 3 werden die Quellen über ihre
stabilen IDs aus dem Ausgangsmodell aufgelöst und vor ihren Drops verwendet.
Umbenennungen müssen bei der Auflösung der zum Ausführungszeitpunkt gültigen Namen
berücksichtigt werden. Ein unterstützter Plan führt den Backfill vor den Drops aus.

Fehlt eine notwendige Quelle oder ist die Abhängigkeit nicht unterstützt, wird
der Upgradepfad vorab mit einer verständlichen Meldung abgelehnt. Gegebenenfalls
ist eine Zwischenversion erforderlich. Fehlende Regeln oder Quellen dürfen nicht
stillschweigend zu einer ausgelassenen Befüllung führen.

Alte Regeln müssen für alle weiterhin unterstützten Upgradepfade mitgeliefert
werden. Nicht anwendbare historische Regeln werden anhand des Ausgangs- und
Zielzustands eingeordnet. Mehrere noch nicht abgeschlossene Regeln, die für
dieselbe Zielspalte gleichzeitig anwendbar sind, sind ein Planungsfehler.

## Teilaufgaben

| Paket | Bereich | Ergebnis |
| --- | --- | --- |
| AP 1 | `schema-core` | Unveränderliche Backfill-Definitionen, typisierte Ausdrücke, Anwendungsbedingungen, Validierungen und Operationen für die Nullbarkeit. |
| AP 2 | Planer in `schema-core` | Gemeinsamer Plan aus DDL, Befüllung und Prüfung; Zwischenschritte für neue Pflichtspalten; Auflösung von IDs, Renames, Constraints und Quelldrops. |
| AP 3 | `schema-postgresql` | Parametrisierte Updates, NULL-Prüfungen, `SET/DROP NOT NULL`, zusätzliche Historientabelle und Lesen/Schreiben ihrer Einträge. |
| AP 4 | `schema-executor` | Ausführung der neuen Schritte, Definitionen prüfen, Wiederanläufe, aktualisierter `AlreadyApplied`-Pfad und atomare Historisierung. |
| AP 5 | `schema-integration` | Optionale Backfill-Registry für den expliziten Aufruf sowie ein Provider-SPI für die automatische Startintegration; beide Wege verwenden dieselbe Validierung. |
| AP 6 | Tests, CLI und Dokumentation | Prüffälle der Abnahme, Darstellung der neuen Planschritte, Fehlermeldungen, Beispiele und Aktualisierung der unterstützten Änderungen. |

AP 1 und AP 2 definieren zuerst den gemeinsamen Vertrag. Darauf bauen Backend
und Executor auf; anschließend folgen Integration und vollständige Abnahme.
Tests werden zusammen mit den jeweiligen Teilaufgaben ergänzt.

Für das Provider-SPI bietet sich ein `BackfillProvider` über `ServiceLoader` an.
Provider liefern ausschließlich Definitionen und dürfen keine SessionFactory oder
EntityManager voraussetzen. Doppelte IDs aus mehreren Providern werden als Fehler
behandelt. Die bisherige Einschränkung der automatischen Integration für JTA und
Multi-Tenancy wird durch dieses Paket nicht aufgehoben.

Es wird kein neues allgemeines Framework-Modul benötigt. `schema-core` bleibt
unabhängig von JDBC und Hibernate. Die Änderungen am Ausführungsplan müssen auch
bei den vorhandenen Dialekt-, Backend- und CLI-Schnittstellen berücksichtigt werden.

## Abnahmekriterien

- [ ] Eine neue Pflichtspalte in einer gefüllten Tabelle wird befüllt und ist
  anschließend tatsächlich `NOT NULL`.
- [ ] Dieselbe Änderung ohne notwendige Befüllungsregel scheitert ohne teilweise
  übernommenes Schema oder neue erfolgreiche Historieneinträge.
- [ ] Leere Tabellen und vorhandene Spalten ohne NULL-Werte benötigen keine
  Ersatzwerte.
- [ ] Beim Verschärfen einer bestehenden Spalte bleiben vorhandene Werte erhalten;
  ausschließlich NULL-Zeilen werden befüllt.
- [ ] NULL in Quellausdrücken, `Coalesce` und die definierte `Concat`-Semantik werden
  geprüft. Verbleibende NULL-Werte verhindern `NOT NULL` und den Commit.
- [ ] Typfehler, unbekannte IDs, falsche Tabellenzuordnung, doppelte IDs und
  konkurrierende Regeln werden vor Änderungen an Anwendungstabellen abgelehnt.
- [ ] Parameterwerte mit Sonderzeichen und gequotete Bezeichner funktionieren ohne
  Einbettung von Werten in SQL-Text.
- [ ] CHECK-, Unique- und FK-Verletzungen sowie ein Fehler nach dem UPDATE rollen
  DDL, Datenänderungen und beide Historien gemeinsam zurück.
- [ ] Ein wiederholter Serverstart führt einen abgeschlossenen Backfill nicht
  erneut aus. Eine geänderte Definition wird auch bei gleichem Schema abgelehnt.
- [ ] Zwei parallele Serverstarts verwenden die vorhandene Migrationssperre;
  derselbe Backfill wird nur einmal erfolgreich dokumentiert.
- [ ] Wiederanläufe nach Rollback oder unbekanntem Commit-Ausgang sowie Fehler bei
  der Verbindungswiederherstellung behalten korrekte Fehlerzustände.
- [ ] Neuanlage, Übernahme und manuell migrierte Zielzustände werden korrekt
  unterschieden und nicht als ausgeführte Datenbefüllung ausgegeben.
- [ ] Unterstützte Versionssprünge und Renames funktionieren. Quellspalten werden
  erst nach ihrer Nutzung gelöscht; nicht unterstützte Abhängigkeiten werden
  vorab erklärt und abgelehnt.
- [ ] `NOT NULL` kann für normale Spalten entfernt werden; unzulässige Lockerungen
  bei Primärschlüssel- und Identity-Spalten werden abgelehnt.
- [ ] Vorhandene Schemahistorien bleiben lesbar und behalten ihre Fingerprints.
  Bestehende API-Aufrufe ohne Backfills funktionieren weiterhin.
- [ ] Beide Integrationswege liefern dieselben Regeln an den Executor. Fehler
  verhindern einen regulären Abschluss des Hibernate-Bootstraps.
- [ ] Die Dokumentation beschreibt Registrierung, Beispiele, Wiederanläufe,
  Versionssprünge und die Betriebsgrenzen. Die relevanten Tests und der bestehende
  Projektcheck `sbt check` bestehen.

## Betriebsgrenzen und Folgearbeiten

Dieses Paket befüllt Daten im Zusammenhang mit der Einführung von Pflichtfeldern
in einer gemeinsamen Starttransaktion. Es umfasst keine eigenständigen
Datenmigrationen bei unverändertem Schema und keine Befüllung ausschließlich
optional bleibender neuer Spalten.

Ebenfalls außerhalb dieses Pakets liegen tabellenübergreifende Berechnungen,
beliebige Typkonvertierungen, Datenaufteilungen, allgemeines Überschreiben
vorhandener Werte, externe Aufrufe, Hintergrundjobs und die Verarbeitung in
Teiltransaktionen mit gespeicherten Zwischenständen.

Große UPDATEs und Prüfungen können den Serverstart verlängern und bestehende
Anwendungen durch die gehaltenen Sperren blockieren. Der bisherige Executor
verwendet bei Schemaänderungen `ACCESS EXCLUSIVE` auf den modellierten bestehenden
Tabellen. Backfills verlängern diese Sperrdauer; es werden nicht ausschließlich
die geänderten Zeilen gesperrt. Die bisherigen Sperr- und Statement-Timeouts
bleiben wirksam. Eine alte Serverversion, die weiterhin NULL schreibt, kann nach
Einführung von `NOT NULL` nicht unverändert weiterarbeiten.

Für große Tabellen und unterbrechungsfreie Deployments ist ein separates
Arbeitspaket erforderlich: Schema zunächst kompatibel erweitern, Daten in
fortsetzbaren Schritten befüllen, die schreibenden Anwendungen umstellen und erst
anschließend die Pflichtfeldbedingungen durchsetzen.
