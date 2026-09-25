# Arbeitspaket: Indizes und Unique-Constraints ändern und entfernen

Status: Konzept zur Umsetzung, noch nicht implementiert.

Dieses Arbeitspaket ergänzt die [bestehende Architektur](architecture.md), das
[Backfill-Konzept](backfills-and-not-null.md), die
[Migrationsvorschau](migration-preview-and-preflight.md) und die
[kontrollierten Typänderungen](controlled-type-changes.md). Neue Operations-,
Referenz- und Freigabenamen sind Entwurfsvorschläge.

## Ziel

Das Framework soll vorhandene Indizes und Eindeutigkeitsregeln anhand des
Hibernate-Zielmodells gezielt ersetzen oder entfernen können. Änderungen an
Suchabfragen und fachlichen Eindeutigkeitsregeln sollen dadurch ohne manuell
geschriebene DDL-Migration möglich werden.

Beispiele:

| Änderung | Beispiel |
| --- | --- |
| Index erweitern | `(last_name)` durch `(last_name, first_name)` ersetzen. |
| Spaltenreihenfolge ändern | `(customer_id, created_at)` durch `(created_at, customer_id)` ersetzen. |
| Sortierung ändern | `(customer_id ASC, created_at ASC)` durch `(customer_id ASC, created_at DESC)` ersetzen. |
| Unbenötigten Index entfernen | Eine Indexdefinition aus dem Hibernate-Mapping nehmen. |
| Eindeutigkeit auf mehrere Spalten beziehen | `UNIQUE(email)` durch `UNIQUE(tenant_id, email)` ersetzen. |
| Eindeutigkeit aufheben | Eine bisher eindeutige Spalte darf künftig doppelte Werte enthalten. |
| Zwischen normalem Index und Eindeutigkeitsregel wechseln | Neue Zielstruktur anlegen und die bisherige gezielt entfernen. |

Der Plan prüft neue Regeln, legt neue Strukturen an, entfernt freigegebene alte
Strukturen und validiert den vollständigen Zielzustand. Das Aufheben einer
Eindeutigkeitsregel erfordert eine ausdrückliche Freigabe.

## Ausgangslage

- `IndexModel` beschreibt einen normalen B-Tree-Index durch geordnete Spalten-IDs
  und deren Sortierrichtung. `UniqueKeyModel` beschreibt einen Unique-Constraint
  durch seine geordneten Spalten-IDs.
- Physische Index- und Constraint-Namen gehören nicht zu diesen Modellen.
- `HibernateSchemaSource` liest normale Indizes sowie Eindeutigkeitsregeln aus
  Hibernate. Beispielsweise werden `@Index(unique = true)` und weitere
  Eindeutigkeitsangaben als Unique-Key-Modell behandelt.
- `DiffEngine` unterstützt das Hinzufügen von Indizes und Unique-Constraints.
  Das Entfernen einer Definition bei weiterhin vorhandenen Spalten wird abgelehnt.
- Entfällt eine beteiligte Spalte oder die ganze Tabelle, werden die zugehörigen
  Strukturen bereits als Folge des freigegebenen Objekt-Drops entfernt.
- `CreateIndex` und `AddUniqueKey` lassen PostgreSQL derzeit die physischen Namen
  vergeben. Eine spätere Entfernung darf diese Namen deshalb nicht erraten.
- Das Backend vergleicht vorhandene Strukturen anhand ihrer Definition. Die
  Katalogabfragen lesen auch ihre tatsächlichen Namen.
- Der Executor plant derzeit vor der Prüfung und Sperrung der Bestandstabellen.
  Für die sichere Namensauflösung neuer Drop-Schritte ist eine zusätzliche
  Bindungsphase unter diesen Sperren erforderlich.
- Backfill- und NOT-NULL-Schritte sind im aktuellen Ausführungspfad bereits
  vorhanden und müssen bei kombinierten Änderungen berücksichtigt werden.

## Umfang der ersten Version

Unterstützt werden ausschließlich die bereits abgebildeten Strukturen:

- Gültige, normale und nicht eindeutige B-Tree-Indizes über einfache Spalten.
- Einfache und zusammengesetzte Indizes mit der modellierten ASC-/DESC-Reihenfolge.
- Nicht aufschiebbare Unique-Constraints über einfache Spalten.
- Die bisher unterstützten Standardregeln für Kollation, Operator-Klassen und NULL.

Nicht Bestandteil sind Expression- oder Partial-Indizes, INCLUDE-Spalten, andere
Indexmethoden, besondere Operator-Klassen, eigene NULL-Sortierung und
`NULLS NOT DISTINCT`. Eigenständige eindeutige Indizes ohne zugehörigen modellierten
Unique-Constraint bleiben außerhalb des bisherigen Backend-Vertrags.

Primärschlüssel werden nicht geändert. Fremdschlüssel werden weder automatisch
entfernt noch auf eine neue Eindeutigkeitsregel umgebunden. Eine solche Abhängigkeit
blockiert den betroffenen Drop. Es wird kein `CASCADE` verwendet.

Eine bloße Änderung von `@Index(name = ...)` oder eines Constraint-Namens löst
keine fachliche Migration aus, weil diese Namen bisher kein Bestandteil des
Schema-Zielmodells sind. Explizites Umbenennen dieser Datenbankobjekte ist ein
eigenes mögliches Folgefeature.

## Identität über die Definition

Für Planung und Freigaben werden strukturelle Referenzen verwendet:

| Referenz | Bestandteile |
| --- | --- |
| `IndexRef` | Tabellen-ID, Objektart, geordnete Spalten-IDs und Sortierrichtung jeder Spalte. |
| `UniqueKeyRef` | Tabellen-ID, Objektart und geordnete Spalten-IDs. |

Eine Tabellen- oder Spaltenumbenennung verändert diese Referenzen nicht. Ein
Index mit anderer Reihenfolge oder Richtung ist eine neue Definition. Eine
Änderung wird als Hinzufügen der neuen und Entfernen der alten Definition geplant;
das Framework rät keine besondere Beziehung zwischen ähnlich aussehenden Namen.

Die bestehende Reihenfolge in `UniqueKeyModel` bleibt erhalten. Auch wenn zwei
Spaltenreihenfolgen dieselbe Eindeutigkeit ausdrücken können, können ihre
zugrunde liegenden Indizes unterschiedlich nutzbar sein. In Version 1 gilt eine
solche Änderung deshalb ebenfalls als Ersatz mit expliziter Entfernung der alten
Unique-Definition. Eine automatische Äquivalenzoptimierung ist nicht erforderlich.

Es werden keine neuen, vom Entwickler zu vergebenden Index- oder Constraint-IDs
eingeführt. Die Referenzen lassen sich aus vorhandenen Modellen ableiten und
erfordern keine Änderung bestehender Schema-Snapshots.

## Freigaben und Risikobewertung

Neue Eindeutigkeitsregeln können an bestehenden Duplikaten scheitern. Entfernte
Eindeutigkeitsregeln erlauben dagegen zukünftig Daten, die bislang verboten waren.
Das muss im Plan als fachliche Änderung sichtbar sein.

Vorgeschlagen wird eine zusätzliche Freigabe `Approval.DropUniqueKey`, die genau
eine bestehende Unique-Definition bezeichnet. Sie erlaubt weder das Löschen von
Spalten noch das Entfernen anderer Constraints.

Die Freigabe bezieht sich auf eine kanonische Signatur der alten `UniqueKeyRef`:
Definitionsformat, Objektart, Tabellen-ID und geordnete Spalten-IDs werden
eindeutig serialisiert und mit einer versionierten Prüfsumme identifiziert.
Physische Datenbanknamen sind nicht Teil dieser Signatur.

Die API soll die Signatur aus einer strukturierten Referenz erzeugen können;
Anwender müssen sie nicht selbst berechnen. Der Plan beziehungsweise die
Fehlermeldung gibt die passende Freigabe aus. Für die vorhandene Einstellung
`hibernate.ddl_manager.approvals` bietet sich ein Eintrag wie
`drop-unique:<versionierte-signatur>` an. Seine Darstellung enthält keine
zusätzlichen Kommas, die mit der bestehenden Listensyntax kollidieren könnten.

Es gelten folgende Regeln:

- Jede ausdrücklich geplante Entfernung einer Unique-Definition auf weiterhin
  vorhandenen Spalten benötigt ihre eigene Freigabe, auch beim Ersetzen durch
  eine neue Definition. Version 1 versucht nicht, eine fachliche Verschärfung
  oder Gleichwertigkeit als automatische Ausnahme zu beweisen.
- Das Entfernen eines normalen, nicht eindeutigen Index benötigt keine neue
  fachliche Freigabe. Es bleibt eine sichtbare Änderung mit möglichen Folgen
  für Abfrage- und Schreibgeschwindigkeit.
- Beide Operationen unterliegen der vorhandenen Risikosteuerung, zunächst als
  `RiskLevel.Locking`. Die Aufhebung von Eindeutigkeit wird zusätzlich über die
  spezifische Freigabe abgesichert und im Bericht gekennzeichnet.
- `Approval.Drop(tableOrColumnId)` ist keine pauschale Freigabe für eine beliebige
  Unique-Regel auf einer weiterhin vorhandenen Tabelle und Spalte.
- Fällt eine Regel automatisch mit ihrer freigegebenen Tabelle oder einer ihrer
  Spalten weg, bleibt die bisherige Drop-Semantik bestehen. Dafür wird keine
  zusätzliche Unique-Freigabe verlangt und kein zweiter Drop-Schritt erzeugt.
- Unbenutzte Freigaben lösen keine Aktion aus. Unbekannte oder fehlerhafte neue
  Einstellungseinträge werden wie bisher als Konfigurationsfehler behandelt.

## Auflösung der tatsächlichen Datenbankobjekte

Neue Operationen, beispielsweise `DropIndex` und `DropUniqueKey`, tragen zuerst
ihre strukturelle Referenz. Der reine Planer führt keine JDBC-Abfrage aus.

Nach der Sperrung und erfolgreichen Prüfung des Ausgangsmodells löst das Backend
jede Referenz auf genau ein bestehendes Datenbankobjekt auf. Die Zuordnung umfasst
Tabelle, geordnete Spalten, Richtung, Indexmethode und alle bereits unterstützten
Eigenschaften. Bei Unique-Regeln kommen Constraint-Art und die Verbindung zum
zugrunde liegenden Index hinzu.

Fehlende oder mehrdeutige Treffer sind Fehler. Ein Objekt wird nicht allein
wegen seines Namens oder einer teilweise passenden Spaltenliste entfernt.
Zusätzliche gleichartige Datenbankobjekte werden nicht willkürlich ausgewählt.

Ein aufgelöster normaler Index wird mit seinem tatsächlichen, qualifizierten
Namen entfernt. Ein Unique-Constraint wird über den tatsächlichen Constraint-Namen
auf der zugehörigen Tabelle entfernt:

```sql
DROP INDEX "public"."users_last_name_idx";

ALTER TABLE "public"."users"
  DROP CONSTRAINT "users_email_key";
```

Die Namen im Beispiel stehen für aus dem Katalog gelesene Werte. Alle Bezeichner
werden durch den vorhandenen Dialekt korrekt gequotet. Die regulären Drop-Schritte
verwenden kein `IF EXISTS`, das eine unerwartet fehlende Struktur verbergen könnte.

Ein Index, der einen Unique-Constraint oder Primärschlüssel trägt, darf nicht als
normaler Index entfernt werden. Die Aufhebung der Eindeutigkeitsregel erfolgt
über den Constraint; dessen unterstützender Index wird nicht als separater
normaler Index weitergeführt. Soll danach ein normaler Index vorhanden sein,
muss er ausdrücklich im Zielmodell stehen. PostgreSQL unterscheidet entsprechend
zwischen dem [Entfernen eines Index](https://www.postgresql.org/docs/14/sql-dropindex.html)
und der Verwaltung von [Unique-Constraints](https://www.postgresql.org/docs/14/ddl-constraints.html#DDL-CONSTRAINTS-UNIQUE-CONSTRAINTS).

Auch tatsächliche FK-Abhängigkeiten müssen geprüft werden. Das Modell erlaubt
FK-Ziele nur auf Primärschlüsseln; daraus darf der Resolver nicht ableiten, dass
ein gleichartig definierter Unique-Constraint im Katalog zwangsläufig keine
abhängigen Fremdschlüssel besitzt. Entscheidend ist die konkrete Objektbindung.

Die aufgelösten Namen und Objektkennungen gelten nur für die aktuelle
Transaktion. Nach Tabellen- oder Spaltenrenames müssen nachfolgende Anweisungen
die jeweils gültigen Tabellen- und Spaltennamen verwenden. Die gebundenen alten
Objekte dürfen nicht durch später erzeugte Ersatzobjekte verwechselt werden.

Dies erfordert eine gebundene Ausführungsform der symbolischen Drop-Schritte.
Der statische Renderer darf dafür weder Namen erraten noch leere SQL-Statements
liefern. Für die Schemahistorie werden die tatsächlich ausgeführten SQL-Anweisungen
mit den aufgelösten Namen erfasst.

## Ablauf und Abhängigkeiten

Für eine reine Index- oder Unique-Änderung gilt:

1. Historie und Modelle prüfen und den vollständigen Definitionsvergleich planen.
2. Erforderliche Freigaben und zugelassene Risiken prüfen.
3. Tabellen mit dem bestehenden Executor sperren und das Ausgangsschema prüfen.
4. Zu entfernende Objekte aus dem Katalog binden und ihre Abhängigkeiten prüfen.
5. Unterstützte Renames und notwendige vorbereitende Schemaänderungen ausführen.
6. Neue Regeln gegen die zu diesem Zeitpunkt gültigen Daten prüfen und die
   erforderlichen neuen Indizes beziehungsweise Unique-Constraints anlegen.
7. Die alten, aufgelösten Strukturen gezielt entfernen.
8. Das vollständige Zielmodell validieren, Historie schreiben und committen.

Neue Ersatzstrukturen werden grundsätzlich vor der Entfernung der alten
angelegt. PostgreSQL darf ihre neuen physischen Namen wie bisher vergeben; es
muss kein noch belegter Name wiederverwendet werden. Die Historie identifiziert
den Zielzustand weiterhin anhand der Definitionen.

Eine nicht herstellbare neue Eindeutigkeitsregel muss den gesamten Versuch
scheitern lassen. Die alte Regel darf dadurch nicht dauerhaft verschwinden.

Bei zusammengesetzten Migrationen ist die tatsächliche Abhängigkeitsfolge
maßgeblich: Eine neue Spalte muss vor ihrem Index vorhanden sein, ein Backfill
kann vor der neuen Eindeutigkeitsprüfung erforderlich sein, und ein Typwechsel
muss bei der Bindung beziehungsweise Ausführung berücksichtigt werden.

Version 1 unterstützt keine beliebige Folge vorübergehender Lockerungen. Wenn
beispielsweise ein Backfill nur nach dem vorgezogenen Entfernen der alten
Unique-Regel ausführbar wäre und der Planer dafür keinen unterstützten Ablauf
besitzt, wird die Kombination vorab erklärt und abgelehnt. Ein gültiges Zielmodell
allein beweist nicht, dass alle Zwischenzustände ausführbar sind.

Spalten- und Tabellen-Drops laufen weiterhin nach ihren abhängigen Arbeiten.
Automatisch mitentfernte Indizes und Regeln werden im Bericht als Auswirkungen
des jeweiligen Drops gezeigt, ohne doppelte Ausführungsoperationen zu erzeugen.

## Datenprüfung und Beispiel Mandanten-Eindeutigkeit

Beim Übergang von `UNIQUE(email)` zu `UNIQUE(tenant_id, email)` ist im Beispiel
`tenant_id` bereits vorhanden und ein Pflichtfeld. Der Ablauf ist:

1. Die neue Kombination auf gültige Eindeutigkeit prüfen.
2. Den neuen Unique-Constraint anlegen.
3. Die alte, global geltende Eindeutigkeitsregel mit passender Freigabe entfernen.

Anschließend kann dieselbe E-Mail-Adresse in unterschiedlichen Mandanten
verwendet werden. Innerhalb eines Mandanten bleibt sie eindeutig.

Der umgekehrte Übergang kann dagegen an bereits vorhandenen gleichen
E-Mail-Adressen verschiedener Mandanten scheitern. Es werden keine Duplikate
automatisch gelöscht oder zusammengeführt.

Die Prüfungen verwenden die tatsächlich modellierte PostgreSQL-NULL-Semantik:
Bei den hier unterstützten Unique-Constraints können Schlüssel mit mindestens
einem NULL-Wert mehrfach vorkommen. Ein `GROUP BY` mit pauschaler Zählung aller
NULL-Gruppen wäre deshalb keine korrekte Verletzungsprüfung. Eine zusätzliche
Pflichtfeldbedingung muss ausdrücklich im Zielmodell stehen.
Siehe [PostgreSQL 14: Eindeutigkeit und NULL](https://www.postgresql.org/docs/14/ddl-constraints.html#DDL-CONSTRAINTS-UNIQUE-CONSTRAINTS).

Bei geplanten Backfills müssen die Prüfungen deren erwartete Ergebnisse und
erhaltene Bestandswerte berücksichtigen. Beim tatsächlichen Anlegen des
Constraints validiert die Datenbank die Bedingung erneut unter den gehaltenen
Sperren. Eine vorherige Vorschau ersetzt diese Absicherung nicht.

## Migrationsvorschau

Die Vorschau soll folgende Informationen zeigen:

- Alte und neue Definition mit Spaltenreihenfolge, Richtung und stabilen IDs.
- Hinzufügen, explizites Entfernen oder implizites Entfernen durch einen Objekt-Drop.
- Aufgelöste aktuelle Datenbanknamen, soweit lesend zuverlässig bestimmbar.
- Fehlende Unique-Freigaben einschließlich ihres kopierbaren Konfigurationseintrags.
- Gefundene Duplikate oder offene erforderliche Datenprüfungen.
- Blockierende FK- oder andere Abhängigkeiten.
- Erforderliche Sperren und mögliche Folgen für Abfragen nach einem Indexabbau.

Kann ein Objekt nicht eindeutig aufgelöst werden, wird die Vorschau blockiert.
Eine technisch nicht durchführbare Prüfung bleibt entsprechend dem
Vorschau-Arbeitspaket unvollständig und wird nicht als bestanden dargestellt.

Die Vorschau verwendet denselben Definitionsvergleich und dieselben
Freigaberegeln wie der Executor. Ihre Namensauflösung ist rein lesend; die
Ausführung bindet die Objekte später unter ihren Sperren erneut. Ein Bericht
wird nicht als bereits freigegebene ausführbare SQL-Datei übernommen.

## Historie, Transaktionen und Kompatibilität

Index- und Unique-Definitionen sind bereits Bestandteile von `SchemaModelJson`
und `SchemaFingerprint`. Für diese Erweiterung werden keine physischen Namen
in bestehende Modelle eingeführt und keine bisherigen Fingerprints umgedeutet.
Die neue Signatur einer Unique-Freigabe ist unabhängig vom Schema-Fingerprint.

Die bestehende Schemahistorie speichert das neue Zielmodell und die tatsächlich
ausgeführten Statements. Ein Wiederanlauf mit angewendetem Ziel prüft den Zustand,
ohne dieselben Strukturen erneut zu erstellen oder zu entfernen.

Bei bestätigtem Rollback werden neue Strukturen entfernt und zuvor entfernte
Strukturen zusammen mit eventuellen Schema- und Datenänderungen wiederhergestellt.
Auch die Historie bleibt dann auf dem alten Stand. `OutcomeUnknown` und
`Committed` behalten ihre bestehenden Bedeutungen; ein unklarer Commit-Ausgang
darf nicht als bestätigter Rollback ausgegeben werden.

Die bisherigen Regeln für Drift, inkonsistente Historien, stillgelegte Schema-IDs
und Rückkehr zu früheren Modellen bleiben wirksam. Eine Revert-Freigabe ersetzt
nicht die zusätzliche Freigabe einer dabei ausdrücklich entfernten Unique-Regel.

## Teilaufgaben

| Paket | Bereich | Ergebnis |
| --- | --- | --- |
| AP 1 | `schema-core` | Strukturelle Referenzen und neue symbolische Operationen für Index- und Unique-Drops. |
| AP 2 | `DiffEngine` beziehungsweise gemeinsamer Planer | Differenzen vollständig planen; Ersetzen als Hinzufügen und Entfernen; Reihenfolge und implizite Drops berücksichtigen. |
| AP 3 | `schema-postgresql` | Eindeutige Katalogauflösung, Abhängigkeitsprüfung und korrekt gebundene Drop-Anweisungen ohne Namensannahmen. |
| AP 4 | `schema-executor` | Unique-Freigaben, Bindung nach Ausgangsprüfung und Sperrung, Ausführung und Erfassung des tatsächlichen SQL in der Historie. |
| AP 5 | `schema-integration` | Neue Freigabe über explizite Optionen und `MigrationSettings` identisch unterstützen; Parser und Fehlermeldungen ergänzen. |
| AP 6 | Vorschau und CLI | Definitionsänderungen, reale Namen, fehlende Freigaben, Datenprüfungen und Abhängigkeiten darstellen. |
| AP 7 | Hibernate- und PostgreSQL-Tests, Dokumentation | Mappingänderungen bis zur realen Datenbank prüfen; Beispiele, Grenzen und Betriebsverhalten dokumentieren. |

Die Kernfunktion kann vor Fertigstellung der Vorschau umgesetzt werden.
Definitionsvergleich und Auflösung sollen anschließend von beiden Wegen genutzt
werden. Bestehende Backfill-Schritte bleiben Bestandteil des gemeinsamen Plans.
Eine neue allgemeine Modulstruktur ist nicht notwendig.

## Abnahmekriterien

- [ ] Normale Indizes lassen sich auf weiterhin vorhandenen Spalten entfernen.
- [ ] Erweiterungen, veränderte Reihenfolge und ASC-/DESC-Änderungen erzeugen die
  richtige neue Definition und entfernen genau die alte.
- [ ] Unique-Regeln lassen sich mit passender Freigabe entfernen oder ersetzen;
  eine fehlende oder auf eine andere Definition bezogene Freigabe blockiert vor DDL.
- [ ] Neue Unique-Regeln scheitern bei echten Duplikaten. NULL-Werte und
  zusammengesetzte Schlüssel werden nach der unterstützten Semantik behandelt.
- [ ] Unique → normaler Index und normaler Index → Unique funktionieren, ohne
  einen constraintgebundenen Index als normalen Index zu löschen.
- [ ] Beim Aufheben einer Unique-Regel bleiben Zeilen und Spalten erhalten;
  danach können die vom neuen Zielmodell erlaubten Duplikate geschrieben werden.
- [ ] Tatsächliche Namen aus Neuanlage, Übernahme oder früherer Umbenennung
  werden korrekt aufgelöst. Gequotete Bezeichner und mehrere Schemas funktionieren.
- [ ] Identische Definitionen auf unterschiedlichen Tabellen oder Schemas werden
  nicht verwechselt. Fehlende oder mehrdeutige Katalogtreffer werden abgelehnt.
- [ ] Primärschlüssel, ihre Indizes, fremde Abhängigkeiten und nicht unterstützte
  Indexarten werden nicht versehentlich entfernt. Abhängige FK-Regeln führen zu
  einer verständlichen Ablehnung ohne `CASCADE`.
- [ ] Neue Ersatzobjekte können vor den alten entstehen, ohne Namenskollisionen
  oder eine anschließende Verwechslung beim Drop.
- [ ] Tabellen- und Spaltenrenames erhalten die strukturelle Zuordnung und
  verwenden bei der Ausführung die richtigen physischen Namen.
- [ ] Ein Spalten- oder Tabellen-Drop erzeugt keine zusätzlichen Drops für
  automatisch mitentfernte Strukturen und verlangt dafür keine doppelte Freigabe.
- [ ] Geänderte Namen im Hibernate-Mapping allein verändern keinen unveränderten
  Index- oder Unique-Zielzustand. Alle maßgeblichen Hibernate-Eindeutigkeitsquellen
  werden berücksichtigt; das Entfernen nur einer redundanten Angabe genügt nicht.
- [ ] API und Einstellungssyntax erzeugen dieselbe spezifische Unique-Freigabe.
  Neue Einträge kollidieren nicht mit der vorhandenen kommagetrennten Syntax.
- [ ] Fehler beim Aufbau einer Ersatzstruktur oder nach einem Drop rollen bei
  bestätigtem Rollback Strukturen, Datenänderungen und Historie gemeinsam zurück.
- [ ] Wiederholte und parallele Starts verändern eine abgeschlossene Migration
  nicht erneut; Commit- und Verbindungsfehler behalten die bestehenden Fehlerzustände.
- [ ] Alte Modelle und Fingerprints bleiben lesbar und kompatibel. Die Historie
  enthält die tatsächlich ausgeführten, aufgelösten SQL-Anweisungen.
- [ ] Unterstützte Kombinationen mit Backfills, NOT NULL und späteren
  Typänderungen haben eine geprüfte Reihenfolge. Nicht unterstützte Kombinationen
  werden vorab erklärt und nicht teilweise übernommen.
- [ ] Nach Umsetzung der Vorschau stimmen Entscheidungen und Freigaben mit dem
  Executor überein; ein späterer Lauf prüft Katalogbindungen und Daten erneut.
- [ ] Bestehende Tests bleiben gültig, neue Integrationsfälle prüfen echte
  PostgreSQL-Objekte und Daten, und `sbt check` besteht.

## Betriebsgrenzen und Folgearbeiten

Die erste Version bleibt in der bestehenden gemeinsamen Starttransaktion.
Sie verwendet die aktuellen Migrations- und Tabellensperren. Das Anlegen eines
großen Ersatzindex kann deshalb den Start verlängern und andere Zugriffe blockieren;
vorübergehend können alte und neue Indexstrukturen Speicher benötigen.

Ein Indexabbau garantiert keine Leistungsverbesserung. Er kann Schreibarbeit
reduzieren, aber zugleich wichtige Abfragen verlangsamen. Der Bericht beschreibt
die geplante Änderung, ohne ihre Wirkung auf unbekannte Abfragen vorherzusagen.

`CREATE INDEX CONCURRENTLY` und `DROP INDEX CONCURRENTLY` sind nicht Bestandteil
dieses Pakets. Ihr Ablauf passt nicht in die vorhandene gemeinsame Transaktion
und benötigt ein eigenes Konzept für Zwischenstände, Fehler und Wiederanläufe.
Siehe [PostgreSQL 14: gleichzeitiger Indexaufbau](https://www.postgresql.org/docs/14/sql-createindex.html#SQL-CREATEINDEX-CONCURRENTLY)
und [DROP INDEX](https://www.postgresql.org/docs/14/sql-dropindex.html).

Weitere mögliche Folgearbeiten sind Spezialindizes, explizite Objektumbenennungen
und koordinierte Änderungen von Fremd- und Primärschlüsseln. Das vorliegende Paket
automatisiert weder fachliche Datenbereinigung noch die Auflösung doppelter Werte.
