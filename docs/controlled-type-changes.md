# Arbeitspaket: Kontrollierte Typänderungen

Status: Konzept zur Umsetzung, noch nicht implementiert.

Dieses Arbeitspaket ergänzt die [bestehende Architektur](architecture.md), das
[Backfill-Paket](backfills-and-not-null.md) und die
[Migrationsvorschau mit Vorabprüfungen](migration-preview-and-preflight.md).
Die genannten neuen Typen und Operationen sind Entwurfsvorschläge.

## Ziel

Das Framework soll häufige, werterhaltende Erweiterungen bestehender Spalten
aus dem Hibernate-Zielmodell erkennen und beim Serverstart durchführen.
Entwickler sollen beispielsweise die maximale Textlänge erhöhen können, ohne
dafür eine manuelle SQL-Migration zu schreiben.

Die erste Version unterstützt genau drei Übergänge:

| Ausgangstyp | Zieltyp | Voraussetzung | Beispiel |
| --- | --- | --- | --- |
| `VARCHAR(n)` | `VARCHAR(m)` | `m > n` | `VARCHAR(100)` → `VARCHAR(255)` |
| `INTEGER` | `BIGINT` | Beide Typen sind die bereits unterstützten nativen PostgreSQL-Typen. | Größerer Bereich für einen Zähler. |
| `NUMERIC(p,s)` | `NUMERIC(q,s)` | `q > p`, unveränderte Scale `s` | `NUMERIC(10,2)` → `NUMERIC(14,2)` |

Bei NUMERIC bezeichnet die Precision die Gesamtzahl der Stellen und die Scale
die Zahl der Nachkommastellen. Eine größere Precision bei gleicher Scale
vergrößert den Bereich vor dem Komma. Die erste Version verändert keine
Nachkommastellen und führt keine Rundung als fachliche Transformation ein.

Werterhaltung und Betriebsaufwand sind getrennte Eigenschaften. Auch ein
werterhaltender Übergang kann Sperren sowie ein Neuschreiben der Tabelle oder
ihrer Indizes erfordern. Der Bericht muss diese Auswirkungen ausdrücklich zeigen.
Siehe [PostgreSQL 14: ALTER TABLE](https://www.postgresql.org/docs/14/sql-altertable.html).

## Ausgangslage

- `ColumnModel.dataType` enthält bereits den Spaltentyp einschließlich
  VARCHAR-Länge beziehungsweise NUMERIC-Precision und -Scale.
- `SchemaModelJson` und `SchemaFingerprint` speichern und berücksichtigen diese
  Informationen bereits.
- `HibernateSchemaSource` liefert die Typen aus den Hibernate-Bootmetadaten.
- `DiffEngine` lehnt derzeit jede Änderung von `dataType` mit dem Hinweis auf
  eine manuelle Migration ab.
- `SchemaOperation` und `PostgreSqlDialect` besitzen noch keine Operation für
  den Typwechsel einer bestehenden Spalte.
- CHECK-Änderungen werden im heutigen Diff nur geplant, wenn der Spaltentyp
  unverändert bleibt. Diese Bedingung muss gezielt überarbeitet werden.
- Das Backend prüft reale Typen, Parameter, Constraints, Indizes und gegebenenfalls
  Identity-Sequenzen vor und nach einer Migration.
- Der Executor führt Schemaänderungen unter Sperren in einer gemeinsamen
  Transaktion aus und speichert anschließend das vollständige Zielmodell.

Dieses Paket nutzt die bestehende stabile Identität einer Spalte. Ein Typwechsel
ist keine neue Spalte und darf nicht durch Löschen und Neuanlegen umgesetzt werden.

## Verbindliche Grenzen der ersten Version

Die drei Übergänge gelten zunächst für normale, bereits vorhandene Spalten.
Nullable und nicht-nullbare Spalten sind gleichermaßen möglich; ihre Nullbarkeit
bleibt durch die Typänderung selbst unverändert.

| Situation | Verhalten in Version 1 |
| --- | --- |
| Einer der drei Übergänge auf einer normalen Spalte | Planen, sofern Modell, Abhängigkeiten und Backend-Prüfungen passen. |
| Identischer Typ mit identischen Parametern | Keine Typänderungsoperation. |
| Kleinere VARCHAR-Länge oder kleinere NUMERIC-Precision | Ablehnen, auch wenn aktuelle Werte hineinpassen könnten. |
| Änderung der NUMERIC-Scale | Ablehnen, auch bei gleichzeitig größerer Precision. |
| `BIGINT` → `INTEGER` | Ablehnen. |
| Andere Typwechsel, etwa Text → Datum, Text → UUID oder Änderungen an Zeittypen | Ablehnen; keine fachliche Konvertierung erraten. |
| Spalte gehört im Ausgangs- oder Zielmodell zu einem Primärschlüssel | Gezielte Ablehnung als noch nicht unterstützte Schlüsseländerung. |
| Spalte ist im Ausgangs- oder Zielmodell Teil einer FK-Verbindung, auf einer der beiden Seiten | Gezielte Ablehnung; kein automatisches Umbauen der Referenzbeziehung. |
| Spalte ist eine Identity-Spalte | Gezielte Ablehnung; die zugehörige Sequenz ist Bestandteil der Typ- und Generierungssemantik. |
| Modellierter Unique-Constraint oder einfacher B-Tree-Index über der Spalte | Unterstützen und nach der Typänderung vollständig prüfen. |
| Modellierter Spalten-CHECK | Unterstützen; Definition bei Bedarf als geordneten Teil des Plans neu herstellen. |

Die Einschränkungen für Schlüssel gelten auch dann, wenn der Typübergang für
sich betrachtet werterhaltend wäre. Zusammengesetzte Schlüssel und Referenzen
aus anderen modellierten Tabellen müssen dabei vollständig untersucht werden.
Eine normale Spalte wird nicht ausgeschlossen, nur weil ihre Tabelle an anderer
Stelle einen Primärschlüssel, Fremdschlüssel oder eine Identity-Spalte besitzt.

Unmodellierte Eigenschaften und Abhängigkeiten werden nicht stillschweigend
übernommen. Defaults, andere Kollationen, Domains und weitere bisher nicht
unterstützte Datenbankmerkmale bleiben außerhalb des freigegebenen Schemas.
Abhängige Views oder andere nicht unterstützte Objekte werden nicht automatisch
gelöscht oder neu aufgebaut. Es wird kein `CASCADE` verwendet.

Die erlaubten Übergänge bilden eine abgeschlossene Liste. Ein PostgreSQL-Cast
allein ist kein ausreichender Grund, einen weiteren Typwechsel zu erlauben.
Auch eine Drop- oder Revert-Freigabe erweitert diese Liste nicht.

## Nutzung durch die Anwendung

Die Änderung kommt weiterhin aus dem Hibernate-Mapping. Bei einer Textspalte
wird beispielsweise `@Column(length = 100)` zu `@Column(length = 255)` geändert.
Die vorhandene `@SchemaId` bleibt erhalten.

Der bisherige Aufruf bleibt bestehen:

```scala
HibernateSchemaMigration.migrate(
  metadata = metadata,
  dataSource = dataSource,
  options = ExecutionOptions()
)
```

Es ist keine zusätzliche Migrations-ID, Backfill-Regel oder manuell formulierte
USING-Klausel erforderlich. Maßgeblich ist der tatsächlich aus Hibernate gelesene
SQL-Typ, nicht allein der Scala-Typ einer Eigenschaft.

Ein erstes Beispiel für das geplante SQL:

```sql
ALTER TABLE "public"."users"
  ALTER COLUMN "display_name" TYPE varchar(255);
```

Die beiden weiteren Übergänge verwenden denselben Mechanismus mit `bigint`
beziehungsweise `numeric(14,2)` als Zieltyp. Typen und Parameter werden aus dem
validierten Modell gerendert. Benutzerdefinierte SQL-Fragmente oder beliebige
Konvertierungsfunktionen sind nicht Teil dieses Pakets.

## Klassifikation und Planungsmodell

Eine gemeinsame Regelkomponente, beispielsweise `TypeChangeRules`, entscheidet
anhand von Ausgangs- und Zieltyp zwischen:

- `Unchanged`: keine Operation erforderlich.
- `Widening`: einer der ausdrücklich erlaubten Übergänge.
- `Unsupported`: konkreter Grund für die Ablehnung.

Ungültige Längen, Precision oder Scale werden durch die vorhandenen Modell- und
PostgreSQL-Grenzprüfungen abgelehnt. Die Entscheidung über Abhängigkeiten erfolgt
zusätzlich anhand beider vollständiger Modelle und des geprüften Datenbankzustands.

Eine neue Operation `ChangeColumnType` enthält mindestens Tabellen-ID,
qualifizierten Tabellennamen, Spalten-ID, zum Ausführungszeitpunkt gültigen
Spaltennamen sowie Ausgangs- und Zieltyp.

Der Planer und der PostgreSQL-Renderer müssen denselben erlaubten Übergängen
folgen. Auch eine direkt konstruierte Operation darf den Renderer nicht dazu
bringen, einen nicht unterstützten Typwechsel auszugeben.

Für die vorhandene Risikosteuerung wird `RiskLevel.Locking` verwendet. Zusätzlich
soll die strukturierte Darstellung festhalten, dass Bestandswerte erhalten
bleiben und dass ein Tabellen- oder Indexumbau möglich beziehungsweise zu
erwarten ist. Eine Einstufung als `Safe` allein wegen der Werterhaltung wäre
irreführend. Eine neue interaktive Freigabe ist für die drei erlaubten Fälle
nicht vorgesehen; die bestehenden `allowedRisks` bleiben wirksam.

## Constraints, Indizes und Reihenfolge

Ein unterstützter kombinierter Plan berücksichtigt folgende Reihenfolge:

1. Ausgangs- und Zielmodell prüfen, Übergänge klassifizieren und nicht unterstützte
   Schlüssel- oder sonstige Abhängigkeiten ablehnen.
2. Unter den bestehenden Migrationssperren den realen Ausgangszustand prüfen.
   Vorhandene CHECKs müssen vor ihrer eventuellen Entfernung vollständig geprüft sein.
3. Unterstützte Tabellen- und Spaltenumbenennungen ausführen. Die Identitäten
   bleiben erhalten; nachfolgende Schritte verwenden die dann gültigen Namen.
4. Modellierte CHECKs auf den geänderten Spalten als Teil des Plans entfernen,
   wenn sie für den Typwechsel neu hergestellt werden. Keine anderen Constraints
   oder unbekannten Objekte entfernen.
5. Die Typänderungen durchführen.
6. Gegebenenfalls die separat geplanten Backfills und Datenprüfungen ausführen.
7. CHECKs im Zieltyp wiederherstellen, neue Unique-Constraints und Indizes sowie
   gegebenenfalls die geplanten NOT-NULL-Bedingungen in ihrer Abhängigkeitsfolge
   herstellen.
8. Den vollständigen Zielzustand einschließlich Constraints und Indizes prüfen
   und die Migration gemeinsam mit ihrer Historie committen.

Für vorhandene CHECKs soll Version 1 eine deterministische Wiederherstellung
der unterstützten Definition im Zieltyp vorsehen. Das kann auch bei fachlich
unveränderter Regel nötig sein, weil der Backend-Vergleich die konkrete vom
Server normalisierte Definition einschließlich Typbezügen prüft. Dafür können
die bestehenden `ChangeCheck`-Schritte vor und nach dem Typwechsel genutzt werden.

Beim gleichzeitigen Wechsel von Typ und CHECK darf die heutige Bedingung
`oldColumn.dataType == newColumn.dataType` die CHECK-Änderung nicht mehr unterdrücken.
Der vollständige Ziel-CHECK wird genau einmal hergestellt. Wurde ein CHECK im
Zielmodell ausdrücklich entfernt, wird er entsprechend der bisherigen Semantik
nicht wieder angelegt.

PostgreSQL passt Indizes und einfache Constraints beim Typwechsel an; hierfür
kann ein Neuaufbau erforderlich sein. Bestehende unterstützte Unique-Constraints
und B-Tree-Indizes sollen deshalb grundsätzlich erhalten und durch PostgreSQL
angepasst werden. Der Plan entfernt sie nicht pauschal vorsorglich. Anschließend
werden Eigenschaften, Spaltenfolge und Verwendbarkeit erneut geprüft.
Siehe [PostgreSQL 14: SET DATA TYPE](https://www.postgresql.org/docs/14/sql-altertable.html).

Unauflösbare Abhängigkeiten werden als Planungsfehler zurückgegeben. Bekannte
externe Abhängigkeiten, insbesondere betroffene Views, sollen durch Katalogabfragen
vor DDL erkannt werden. Eine zusätzliche Datenbankablehnung während der Ausführung
bleibt möglich und führt zum Abbruch der gesamten Transaktion.

## Zusammenspiel mit Backfills und NOT NULL

Die Typänderungen dieses Pakets erhalten die bestehenden Werte. Sie führen
selbst keine Befüllung fehlender Werte durch und gelten nicht als ausgeführter
Backfill. Für sie ist deshalb kein eigener Eintrag in der Backfill-Historie nötig.

Nach Umsetzung des Backfill-Pakets kann derselbe Deployment-Schritt beispielsweise
eine Textspalte vergrößern, fehlende Werte ergänzen und sie anschließend zum
Pflichtfeld machen. Die Vergrößerung muss vor einer Befüllung erfolgen, deren
Ergebnis die bisherige Längenbegrenzung überschreiten könnte.

Ausdruckstypen und Constraints müssen für den jeweiligen Zwischenzustand gelten.
Wenn eine Quelle selbst ihren Typ wechselt, müssen sowohl Planer als auch
Vorschau diesen Zeitpunkt berücksichtigen. Nicht unterstützte Kombinationen
werden vorab als solche abgelehnt und nicht durch Umordnen auf Verdacht ausgeführt.

Ohne implementiertes Backfill-Paket bleiben Nullbarkeitsänderungen im bestehenden
Framework abgelehnt. Die eigenständigen drei Typübergänge dürfen unabhängig davon
umgesetzt und verwendet werden.

## Migrationsvorschau und Betriebsaufwand

Die geplante Vorschau zeigt je Typänderung:

- Stabile Spalten-ID, physischen Namen und Ausgangs- sowie Zieltyp.
- Die Regel, aufgrund derer der Übergang erlaubt oder abgelehnt wird.
- Betroffene Constraints, Indizes und nicht unterstützte Abhängigkeiten.
- Geplantes SQL und erforderliche Sperren.
- Werterhaltung getrennt von Hinweisen auf Tabellen- und Indexumbau.
- Soweit verfügbar Tabellengröße und ausdrücklich als Schätzung markierte
  Zeilenanzahl; keine garantierte Dauer oder präzise Speicherbedarfsprognose.

Die Werterhaltung der drei Übergänge folgt aus den freigegebenen Typregeln und
dem geprüften Ausgangstyp. Dafür ist keine vollständige Suche nach Werten nötig,
die bereits im kleineren Ausgangsbereich liegen müssen. Zusätzliche neue
Constraints und Backfills benötigen weiterhin ihre eigenen Vorabprüfungen.

Eine Vorschau darf weder `ALTER COLUMN TYPE` probeweise ausführen noch aus
vermeintlich passenden Bestandswerten einen nicht freigegebenen Übergang erlauben.
Unterstützte Projektionen erfolgen lesend und berücksichtigen den Zieltyp,
wenn nachfolgende Ausdrücke oder Constraints davon abhängen.

Der bestehende Executor verwendet bei Schemaänderungen `ACCESS EXCLUSIVE` auf
den modellierten Bestandstabellen. Diese Sperren werden nicht durch das neue
Feature reduziert. Insbesondere beim Wechsel von INTEGER zu BIGINT muss mit
einem Neuschreiben der Tabelle gerechnet werden. Optimierungen bei anderen
Übergängen dürfen nicht als allgemeine Zusage einer sofortigen oder sperrfreien
Migration erscheinen.

Tabellen- und Indexumbauten können Zeit und zusätzlichen Speicher beanspruchen.
Das gehört in die Auswirkungsbeschreibung; eine verlässliche Vorhersage für eine
konkrete Produktionstabelle ist nicht Bestandteil dieses Pakets.
Siehe [PostgreSQL 14: Hinweise zu ALTER TABLE](https://www.postgresql.org/docs/14/sql-altertable.html#SQL-ALTERTABLE-NOTES).

## Historie, Wiederanläufe und Kompatibilität

Das Zielmodell wird wie bisher mit der normalen Schema-Revision und dem
ausgeführten SQL gespeichert. Die Typänderung verändert den Ziel-Fingerprint,
weil der Spaltentyp bereits Bestandteil seiner Definition ist.

Es werden keine neuen Felder allein für VARCHAR-Länge, Precision oder Scale
benötigt. Vorhandene Modelle, JSON-Formatversionen und Schema-Fingerprints bleiben
lesbar beziehungsweise unverändert interpretierbar. Die Darstellung neuer
Operationen im Vorschauformat muss dessen Versionierungsregeln beachten.

- Ein erneuter Start mit bereits angewendetem Ziel prüft die Datenbank und
  führt den Typwechsel nicht erneut aus.
- Bei einem bestätigten Rollback werden Typwechsel, CHECK-Anpassungen,
  gegebenenfalls Backfills und neue Historieneinträge gemeinsam zurückgenommen.
- Bei `FailureState.OutcomeUnknown` muss ein neuer Versuch zuerst die Historie
  unter der Migrationssperre erneut auswerten.
- `FailureState.Committed` bleibt ein bestätigter Commit mit nachfolgendem
  Verbindungsproblem und darf nicht als erfolgreicher Rollback behandelt werden.
- Die bisherigen Regeln gegen versehentliche Rückkehr zu alten Modellen gelten
  weiterhin. Eine genehmigte Rückkehr macht eine erforderliche Verkleinerung nicht
  automatisch zu einer unterstützten Typänderung.

Unterstützte Versionssprünge werden direkt zwischen dem zuletzt gespeicherten
und dem aktuellen Modell bewertet. `VARCHAR(100)` → `VARCHAR(500)` benötigt
keine Zwischenversion mit `VARCHAR(255)`. Ein dabei auftretender nicht erlaubter
Übergang bleibt dagegen blockiert.

Die Prüfung des tatsächlichen Ausgangszustands und der Freigaben erfolgt bei
der Ausführung erneut, auch wenn zuvor eine Vorschau erstellt wurde.

## Teilaufgaben

| Paket | Bereich | Ergebnis |
| --- | --- | --- |
| AP 1 | `schema-core` | Abgeschlossene Übergangsregeln und strukturierte Ablehnungsgründe; neue Operation `ChangeColumnType`. |
| AP 2 | `DiffEngine` beziehungsweise gemeinsamer Planer | Pauschale Typablehnung ersetzen; Schlüsselabhängigkeiten beider Modelle prüfen; Reihenfolge mit Renames und CHECKs planen. |
| AP 3 | `schema-postgresql` | Übergänge und Typgrenzen auch im Renderer validieren; korrekt gequotetes `ALTER COLUMN TYPE`; Katalogprüfung relevanter Abhängigkeiten und vollständige Nachprüfung. |
| AP 4 | `schema-executor` | Neue Operation in Risiko-, Ausführungs- und Historienpfad integrieren; bestehende Transaktions- und Fehlersemantik erhalten. |
| AP 5 | Backfill- und Vorschauintegration | Zieltypen bei Ausdrücken und Projektionen berücksichtigen; Operationen, Ablehnungen, Sperren und Umbauhinweise darstellen. |
| AP 6 | `schema-hibernate`, Integrationstests und Dokumentation | Mappingänderungen bis zur realen Datenbank nachweisen; Regeln, Beispiele, Grenzen und unterstützte Übergänge dokumentieren. |

AP 1 bis AP 4 können ohne die beiden anderen Arbeitspakete umgesetzt werden.
AP 5 vervollständigt danach die gemeinsame Nutzung. Der gemeinsame Planer der
Vorschau soll verwendet werden, sobald er verfügbar ist; es entsteht keine
zweite Liste erlaubter Typwechsel für die Vorschau.

## Abnahmekriterien

- [ ] Die drei erlaubten Übergänge werden erkannt; identische Typen erzeugen
  keine Typänderungsoperation.
- [ ] Nicht erlaubte Richtungen, Scale-Änderungen und alle anderen Typfamilien
  werden mit Ausgangstyp, Zieltyp und konkretem Grund abgelehnt.
- [ ] Die PostgreSQL-Grenzen für VARCHAR und NUMERIC werden eingehalten, auch
  bei direkt konstruierten `ChangeColumnType`-Operationen.
- [ ] Änderungen im Hibernate-Mapping erzeugen mit unveränderter Schema-ID den
  erwarteten Zieltyp und einen Typwechsel, ohne Drop und Neuanlage der Spalte.
- [ ] VARCHAR-Werte einschließlich Mehrbyte-Zeichen, Leerstrings und NULL bleiben
  erhalten; anschließend können längere Werte bis zur neuen Grenze gespeichert werden.
- [ ] INTEGER-Grenzwerte, negative Werte, `0`, positive Werte und NULL bleiben
  erhalten; anschließend lassen sich Werte außerhalb des INTEGER-Bereichs speichern.
- [ ] NUMERIC-Werte werden ohne Rundung oder Scale-Änderung übernommen; anschließend
  lassen sich größere Beträge mit derselben Zahl an Nachkommastellen speichern.
- [ ] Die tatsächliche Nullbarkeit bleibt bei einem reinen Typwechsel unverändert.
- [ ] Betroffene Primärschlüssel-, FK- und Identity-Spalten werden vor Änderungen
  an Anwendungstabellen abgelehnt; unbeteiligte Schlüssel derselben Tabelle
  verhindern einen erlaubten Typwechsel nicht.
- [ ] Unterstützte Unique-Constraints, einfache und zusammengesetzte B-Tree-Indizes
  bleiben korrekt und verwendbar. Indizes mit absteigender Sortierung werden berücksichtigt.
- [ ] Modellierte CHECKs bleiben nach einem Typwechsel semantisch korrekt und
  bestehen die genaue Backend-Prüfung. Gleichzeitige CHECK-Änderungen werden
  weder ausgelassen noch doppelt angelegt.
- [ ] Kombinierte Tabellen- und Spaltenrenames verwenden in allen nachfolgenden
  Anweisungen die richtigen Namen; gequotete Bezeichner funktionieren.
- [ ] Bekannte nicht unterstützte Abhängigkeiten, etwa betroffene Views, werden
  verständlich gemeldet. Es gibt kein automatisches `CASCADE`.
- [ ] Ein Fehler nach einer Typänderung stellt bei bestätigtem Rollback den alten
  Typ, Daten, Constraints und bisherigen Historienstand wieder her.
- [ ] Wiederholte und parallele Serverstarts führen die Änderung nur einmal
  erfolgreich aus; unbekannte Commit-Ausgänge und Verbindungsfehler behalten die
  korrekten Fehlerzustände.
- [ ] Alte gespeicherte Modelle und Fingerprints bleiben kompatibel; nur das
  tatsächlich geänderte Zielmodell erhält einen neuen Fingerprint.
- [ ] Direkte erlaubte Versionssprünge funktionieren. Revert- oder Drop-Freigaben
  ermöglichen keine nicht unterstützte Verkleinerung.
- [ ] Nach Umsetzung des Backfill-Pakets funktioniert Vergrößern → Befüllen →
  Pflichtfeld einschließlich Constraints in einer Transaktion; Fehler rollen
  alle beteiligten Änderungen gemeinsam zurück.
- [ ] Nach Umsetzung der Vorschau erscheinen dieselben Entscheidungen und
  Schritte wie im Executor. Werterhaltung, Sperren, mögliche Umbauten und
  unvollständige Prüfungen werden getrennt dargestellt.
- [ ] Vorhandene Tests bleiben gültig, neue PostgreSQL-Integrationstests prüfen
  echte Daten und Constraints, und der vollständige Projektcheck `sbt check` besteht.

## Folgearbeiten und Betriebsgrenzen

Verkleinerungen mit ausdrücklicher Freigabe und Datenprüfung, fachliche
Konvertierungen mit einer deklarativen Regel sowie koordinierte Änderungen an
Primärschlüsseln, Fremdschlüsseln und Identity-Sequenzen sind eigene Erweiterungen.
Auch andere prinzipiell werterhaltende Übergänge werden erst nach einer gezielten
Erweiterung der Regeln und Tests aufgenommen.

Dieses Paket bietet keinen unterbrechungsfreien Typwechsel auf großen Tabellen.
Der Serverstart wartet auf die Migration; die vorhandenen Timeouts bleiben
wirksam. Lange Umbauten können ein separates Deployment-Verfahren erfordern.

Ein größerer Datenbanktyp garantiert außerdem nicht die Kompatibilität alter
Anwendungsversionen. Sobald Werte außerhalb des bisherigen Bereichs geschrieben
werden, können alte Anwendungen sie möglicherweise nicht mehr verarbeiten.
Koordinierte parallele Serverversionen sind deshalb kein Versprechen dieses Pakets.
