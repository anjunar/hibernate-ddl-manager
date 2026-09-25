# Arbeitspaket: Migrationsvorschau und Vorabprüfungen

Status: Konzept zur Umsetzung, noch nicht implementiert.

Dieses Arbeitspaket ergänzt die [bestehende Architektur](architecture.md) und das
[Arbeitspaket für Backfills und NOT NULL](backfills-and-not-null.md). Es beschreibt
eine Vorschau gegen die tatsächliche Datenbank, ohne Schema, Anwendungsdaten oder
Migrationshistorie zu verändern. API- und Typnamen sowie CLI-Aufrufe sind
Entwurfsvorschläge und noch keine verfügbaren Funktionen.

## Ziel und Nutzen

Vor einem Deployment soll sichtbar werden, welche Änderungen der nächste
Serverstart plant, welche Datenbedingungen diese Änderungen verhindern und welche
Freigaben oder weiteren Prüfungen noch fehlen.

Die Vorschau liefert:

- Änderungen und SQL in geplanter Ausführungsreihenfolge.
- Betroffene Tabellen und Spalten mit ihren stabilen IDs.
- Bestehende Abweichungen zwischen Datenbank und gespeicherten Modellen.
- Fehlende Drop-, Rückbenennungs- oder Revert-Freigaben und gesperrte Risikoklassen.
- Vorabprüfungen für neue Constraints und, nach Umsetzung des Backfill-Pakets,
  Pflichtfelder und Befüllungsregeln.
- Hinweise auf erforderliche Sperren sowie optionale Zeilenzahlen und
  ausdrücklich gekennzeichnete Schätzungen.
- Einen lesbaren Bericht und ein versioniertes JSON-Format für Build-Pipelines.

Eine erfolgreiche Vorschau besagt, dass die durchgeführten Prüfungen am gelesenen
Zustand keine Blockade gefunden haben. Sie garantiert weder die spätere
Ausführungsdauer noch den Erfolg nach zwischenzeitlichen Änderungen.

## Ausgangslage

- `DiffEngine` ermittelt Operationen aus bisherigem Modell und Zielmodell.
- `PostgreSqlDialect` erzeugt SQL für diese Operationen.
- `JdbcMigrationExecutor` liest und prüft die Schemahistorie, kontrolliert
  Freigaben und Risiken und führt die Migration aus. Teile dieser Planung sind
  derzeit private Methoden innerhalb des Executors.
- Die CLI besitzt ausschließlich ein fest eingebautes `demo` ohne Verbindung
  zu einer Datenbank.
- `initializeHistory` erzeugt Schema und Historientabelle. Diese Methode darf
  eine Vorschau nicht aufrufen.
- `lockAndValidate` verbindet Katalogprüfungen mit Tabellensperren. Zur genauen
  CHECK-Prüfung werden derzeit temporäre Prüftabellen angelegt. Auch dieser Pfad
  ist nicht unverändert als rein lesende Vorschau geeignet.

Ein zusätzlicher Parameter, der am Ende von `migrate` einfach ein Rollback
auslöst, erfüllt den Auftrag nicht. Die Vorschau darf weder DDL noch Backfills
probeweise ausführen.

## Umfang und Verhältnis zum Backfill-Paket

Die erste Ausbaustufe unterstützt alle bereits modellierten und planbaren
Schemaoperationen. Die Vorschau fügt keine neue Migrationsfähigkeit hinzu: Eine
vom Executor nicht unterstützte Typänderung bleibt beispielsweise blockiert.

Das Backfill-Paket liefert anschließend die zusätzlichen Definitionen und
Planschritte für `FillNulls`, `AssertNoNulls`, `SetNotNull` und `DropNotNull`.
Diese werden in denselben Bericht integriert. Es entsteht keine zweite
Backfill-Registry und keine abweichende Implementierung der Ausdruckssemantik.

Die Umsetzung der allgemeinen Vorschau kann vorher beginnen. Die vollständige
Abnahme der Backfill-bezogenen Prüffälle setzt das Backfill-Paket voraus.

## Gemeinsame Planung für Vorschau und Ausführung

Die bestehende Planungslogik wird in einen gemeinsam verwendeten Dienst
ausgelagert. Dieser erhält das Zielmodell, die geprüfte Historie, die wirksamen
Ausführungsoptionen und gegebenenfalls Backfill-Definitionen. Er liefert einen
strukturierten Plan mit Operationen, SQL, Risiken, erforderlichen Freigaben und
Planungsfehlern.

Die Vorschau darf keine parallele Nachimplementierung des Executors werden.
Insbesondere müssen beide Wege dieselben Regeln für folgende Fälle verwenden:

- Unbekannte oder nicht unterstützte Schemaänderungen.
- Wiederverwendung stillgelegter Schema-IDs.
- Rückkehr zu früheren Revisionen oder früheren Namen.
- Explizite Freigaben und `allowedRisks`.
- Übernahme einer Datenbank ohne Historie und Akzeptieren manueller Migrationen.
- Backfill-Identitäten, Definitionen und Anwendungsbedingungen.

Fehlende Freigaben sollen einen ansonsten ermittelbaren Plan nicht unsichtbar
machen. Der Bericht zeigt die betroffenen Schritte und kennzeichnet ihre
Blockade. Bei nicht planbaren Änderungen darf eine Teilansicht dagegen nicht
als vollständiger, ausführbarer Plan erscheinen.

Die reine Planung führt keine Datenbankzugriffe aus. JDBC-Lesen, tatsächliche
Ausführung und Darstellung bleiben getrennte Aufgaben. `schema-core` erhält
keine Abhängigkeit auf Executor-Historienklassen oder Hibernate.

## Rein lesender Datenbankzugriff

Die Vorschau verwendet eine eigene, frische Verbindung ohne fremde Transaktion.
Sie startet vor der ersten Zustandsabfrage eine PostgreSQL-Transaktion mit
`READ ONLY` und `REPEATABLE READ`. Der Lesemodus muss tatsächlich serverseitig
wirksam sein und darf nicht nur als unverbindlicher JDBC-Hinweis gelten.
Die Transaktionseigenschaften werden vor der ersten Abfrage gesetzt; maßgeblich
sind die Regeln von [PostgreSQL 14 zu SET TRANSACTION](https://www.postgresql.org/docs/14/sql-set-transaction.html).

Der Ablauf:

1. Eingaben, Zielmodell, Optionen und verfügbare Definitionen prüfen.
2. Verbindung übernehmen und Lesetransaktion mit begrenzten Wartezeiten starten.
3. Vorhandensein und Format der Historientabellen durch Katalogabfragen prüfen.
   Eine tatsächlich fehlende Historie als fehlend behandeln; fehlende Leserechte
   oder ein falsches Tabellenformat sind eigenständige Fehler.
4. Vorhandene Historien lesen und die vollständigen Ketten beziehungsweise
   Definitionsprüfsummen prüfen.
5. Den relevanten tatsächlichen Schemazustand lesend erfassen und vergleichen.
6. Mit dem gemeinsamen Planer den vorgesehenen Ablauf und seine Blockaden
   ermitteln.
7. Die ausgewählten Datenprüfungen auf dem gelesenen Zustand ausführen.
8. Bericht erzeugen, Transaktion beenden und den ursprünglichen Zustand der
   Verbindung wiederherstellen. Unbrauchbare Verbindungen nicht an den Pool
   zurückgeben.

Es werden keine Historien angelegt, keine temporären Tabellen erzeugt, keine
Sequenzen weitergeschaltet, keine Freigaben gespeichert und keine DDL- oder
datenverändernden DML-Anweisungen ausgeführt. Auch `EXPLAIN ANALYZE` auf
Änderungsanweisungen gehört nicht in diesen Pfad.

Die Vorschau nimmt nicht die exklusive Migrationssperre und keine expliziten
`ACCESS EXCLUSIVE`-Sperren. Normale Lesezugriffe können trotzdem Sperren halten
und mit parallelem DDL kollidieren. Sperr- und Statement-Timeouts begrenzen diese
Zugriffe. Ein konsistenter Lesestand schützt keine spätere Ausführung vor
zwischenzeitlichen Änderungen.

Schlägt eine Abfrage fehl und ist die Lesetransaktion dadurch abgebrochen, wird
sie beendet. Bereits erhobene Befunde bleiben im unvollständigen Bericht erhalten;
nachfolgende Prüfungen werden als nicht ausgeführt markiert. Der Bericht darf
keine Ergebnisse mehr aus einer defekten Transaktion ableiten.

## Schema- und Historienprüfungen

Die lesbaren Katalogprüfungen des Backends werden von sperrenden und schreibenden
Hilfsfunktionen getrennt. Vergleichsregeln sollen zwischen Vorschau und Executor
geteilt werden, soweit ihre Voraussetzungen identisch sind.

Für CHECK-Definitionen ist ein eigener lesender Vergleich erforderlich: Die
unterstützten Allowed-Values- und Range-Ausdrücke müssen strukturell mit den
Katalogdefinitionen verglichen werden, einschließlich relevanter Typen, Operatoren
und Casts. Ein passender Constraint-Name oder ein ungenauer Textvergleich genügt
nicht. Kann eine Form nicht zuverlässig verglichen werden, wird die Prüfung als
nicht entscheidbar markiert. Die Vorschau erzeugt dafür keine Prüftabelle.

| Ausgangslage | Vorschau |
| --- | --- |
| Historie vorhanden, Ziel unterscheidet sich | Historie prüfen, Datenbank gegen das bisherige Modell vergleichen und Änderungen planen. |
| Ziel bereits angewendet | Bestehenden Zustand und bekannte Backfill-Definitionen trotzdem prüfen; keine geplanten Änderungen ausgeben. |
| Keine Historie und keine Zielobjekte vorhanden | Neuanlage planen, ohne Historie oder Tabellen zu erzeugen. |
| Keine Historie, Zielobjekte bereits vorhanden | Erforderliche Übernahmeoption und genaue Übereinstimmung prüfen; Übernahme nur als geplanten Verwaltungsakt zeigen. |
| Manuelle Migration akzeptiert | Fingerprint, Zielzustand und Entfernung alter Objekt-Namen nach den bestehenden Regeln prüfen; geplante Historisierung zeigen. |
| Inkonsistente oder inkompatible Historie | Blockieren; keinen normalen Migrationsplan als ausführbar darstellen. |

Die gewählte Fallunterscheidung muss der des Executors entsprechen. Die Vorschau
erfasst außerdem relevante Namenskollisionen neuer Objekte. Sie verändert keine
Schema-Fingerprints und erweitert nicht das bestehende Historienlayout.

## Vorabprüfungen der Daten

Standardmäßig werden begrenzte Existenzprüfungen verwendet: Es genügt zunächst
festzustellen, ob mindestens eine problematische Zeile existiert. Vollständige
Zählungen werden ausdrücklich aktiviert und ebenfalls durch Zeitlimits begrenzt.
Auch eine Existenzprüfung kann im ungünstigen Fall viele Zeilen lesen.

| Prüfung | Befund |
| --- | --- |
| Spalte wird Pflichtfeld | Existieren NULL-Werte, und ist dafür eine anwendbare Befüllungsregel vorhanden? |
| Backfill-Ausdruck | Bleiben nach Anwendung des Ausdrucks noch NULL-Werte übrig? Sind Quellen und Typen unterstützt? |
| Neue Pflichtspalte ohne Regel | Ist die bestehende Tabelle leer? |
| Neuer Unique-Constraint oder eindeutiger Index | Existieren nach den geplanten Änderungen doppelte Schlüsselkombinationen? |
| Neuer Fremdschlüssel | Existieren nicht auflösbare Referenzen gemäß der modellierten FK- und NULL-Semantik? |
| Neuer oder geänderter CHECK | Verletzen vorhandene beziehungsweise projizierte Werte die unterstützte Bedingung? |
| Optional aktivierte Zählung | Wie viele Zeilen wären betroffen oder verletzen die Bedingung? |

Nach geplanten Backfills müssen Prüfungen den erwarteten Folgezustand betrachten.
Für eine bestehende Zielspalte bedeutet dies sinngemäß:

```sql
CASE
  WHEN "display_name" IS NULL THEN <parametrisierter Backfill-Ausdruck>
  ELSE "display_name"
END
```

Dieser Ausdruck wird ausschließlich in SELECT-Abfragen verwendet. Neue Spalten
werden als Projektion berechnet, ohne sie anzulegen. Quellspalten werden über
stabile IDs auf ihre derzeitigen Namen aufgelöst; zukünftige Renames verändern
die reale Datenbank während der Vorschau nicht.

Eine vorhandene NULL-Zeile ist mit einem geeigneten Backfill allein noch keine
Blockade. Umgekehrt darf ein Ersatzwert, der einen Unique- oder CHECK-Konflikt
erzeugt, nicht als unproblematisch erscheinen. Zusammengesetzte Schlüssel und
die jeweilige NULL-Semantik müssen berücksichtigt werden.

Ein CHECK gilt bei TRUE oder NULL als erfüllt; eine Vorabprüfung darf nur FALSE
als CHECK-Verletzung zählen. Die Prüfung auf fehlende Pflichtwerte erfolgt
gesondert. Ebenso müssen Unique- und Fremdschlüsselprüfungen die jeweils
unterstützte NULL-Behandlung übernehmen, statt einen allgemeinen
Duplikatvergleich auf alle Zeilen anzuwenden.
Siehe [PostgreSQL 14: Constraints](https://www.postgresql.org/docs/14/ddl-constraints.html).

Nicht unterstützte Projektionen oder Abhängigkeiten liefern ein ausdrücklich
offenes Prüfergebnis. Die Vorschau behauptet keine vollständige Simulation
beliebiger SQL- oder Anwendungseffekte.

## Bericht und Prüfstatus

Das Berichtsmodell enthält mindestens:

- Formatversion und Zeitpunkt der Vorschau.
- Ausgangsrevision, bisherigen Schema-Fingerprint und Ziel-Fingerprint.
- Geplanten Modus: Migration, keine Änderung, Übernahme oder manuelle Übernahme.
- Geordnete Schritte mit stabilen IDs, physischen Namen, Operationen und SQL.
- Wirksame Risikoregeln sowie erforderliche, vorhandene und fehlende Freigaben.
- Erwartete Sperrarten und betroffene Tabellen bei der späteren Ausführung.
- Einzelne Befunde mit stabilem Fehlercode, Beschreibung und Bezug zu Schritt
  beziehungsweise Schema-ID.
- Prüfstatus, Umfang, Messwerte, Schätzungen und ausdrücklich offene Prüfungen.

Jede Prüfung verwendet einen klaren Status:

| Status | Bedeutung |
| --- | --- |
| `Passed` | Im gelesenen Zustand wurde die geprüfte Bedingung erfüllt. |
| `Failed` | Eine konkrete Blockade wurde nachgewiesen. |
| `NotApplicable` | Die Prüfung ist für diesen Plan nicht erforderlich. |
| `NotRun` | Nicht ausgewählt oder wegen eines vorherigen Fehlers nicht ausgeführt. |
| `Inconclusive` | Beispielsweise wegen Timeout, fehlender Rechte oder nicht unterstützter Auswertung nicht entscheidbar. |

Für die Zusammenfassung gelten `Ready`, `Blocked` und `Incomplete`:
Eine nachgewiesene Blockade ergibt `Blocked`. Ohne Blockade, aber mit einer offenen
erforderlichen Prüfung, ergibt sich `Incomplete`. `Ready` setzt einen vollständigen
Plan ohne Blockaden und erfolgreich abgeschlossene erforderliche Prüfungen voraus.
Eine ausgelassene optionale Zeilenzählung verhindert `Ready` nicht.

„Keine Änderungen“ ist eine Eigenschaft des Plans und kein Ersatz für Prüfungen.
Auch dieser Fall kann beispielsweise wegen Drift `Blocked` sein. Eine reine
Schemaansicht mit abgewählten erforderlichen Datenprüfungen wird als `Incomplete`
ausgegeben, nicht als vollständige Vorabprüfung.

Exakte Zählungen, Schätzungen und unbekannte Werte sind unterschiedliche
Datentypen im Bericht. Fehlende Angaben sind niemals automatisch null Zeilen.
Es gibt keine garantierte Laufzeitprognose. Sperrhinweise müssen den tatsächlichen
Executor abbilden: Dieser sperrt derzeit bei Änderungen alle modellierten
Bestandstabellen exklusiv, nicht nur die durch ein UPDATE betroffenen Zeilen.

SQL-Ausgaben enthalten Platzhalter und Parametertypen. Der Standardbericht gibt
keine Datensatzinhalte, Zugangsdaten oder Backfill-Literalwerte aus. Text und JSON
werden aus demselben Berichtsobjekt erzeugt und zeigen dieselben Befunde.

## API, CLI und Build-Pipeline

Vorgeschlagene API nach Bereitstellung der gemeinsamen Backfill-Definitionen:

```scala
val report = HibernateSchemaMigration.preview(
  metadata = metadata,
  dataSource = dataSource,
  options = ExecutionOptions(),
  backfills = backfills,
  previewOptions = PreviewOptions(
    dataChecks = DataChecks.Existence,
    includeExactCounts = false
  )
)
```

Ein entsprechender JDBC-Einstiegspunkt nimmt ein `SchemaModel` ohne Hibernate
entgegen. Der explizite Hibernate-Aufruf und die Vorschau verwenden dieselbe
Auflösung der Ausführungsoptionen und Backfill-Definitionen. Der Aufruf findet
nach dem Aufbau der Boot-Metadaten statt und benötigt keine SessionFactory.

Für die CLI wird das Zielmodell aus den Hibernate-Metadaten mit der vorhandenen
Modellserialisierung exportiert. Ein solcher Export-Einstiegspunkt ist Teil
dieses Pakets; das Zielmodell wird nicht manuell nachgebaut. Backfill-Definitionen
benötigen bei Nutzung der CLI einen passenden versionierten Export ihrer
deklarativen Definitionen.

Vorgeschlagener Aufruf nach der Implementierung:

```text
schema-cli preview --target target-schema.json --format text
schema-cli preview --target target-schema.json --backfills backfills.json --format json --data-checks existence
```

Die Verbindung wird beispielsweise über `HIBERNATE_DDL_PREVIEW_JDBC_URL`,
`HIBERNATE_DDL_PREVIEW_USER` und `HIBERNATE_DDL_PREVIEW_PASSWORD` konfiguriert.
Der Befehl zeigt diese Werte nicht an. Zielmodell und optionale Definitionen
werden vor Datenbankzugriffen auf lesbares Format und gültigen Inhalt geprüft.

Vorgeschlagene Exit-Codes:

| Code | Bedeutung |
| --- | --- |
| `0` | Vollständiger Bericht mit Ergebnis `Ready`. |
| `1` | Bericht mit Ergebnis `Blocked`. |
| `2` | Aufruf- oder technischer Fehler, der keinen verwertbaren Bericht erlaubt. |
| `3` | Bericht mit Ergebnis `Incomplete`. |

JSON erscheint allein auf stdout; technische Diagnosen gehören auf stderr.
Build-Pipelines können so einen Bericht archivieren und einen nicht erfolgreichen
Prüflauf eindeutig erkennen. Die Vorschau selbst startet keine Migration und
schreibt keine Freigaben. Bestehende `demo`- und Hilfe-Aufrufe bleiben nutzbar.

Ein Vorschauaufruf im regulären Serverstart darf nicht als erfolgreiche Migration
gewertet werden und den Server mit einem noch nicht hergestellten Schema
freigeben. Dieses Paket führt dafür keinen automatischen Ersatz des Integrators ein.

## Beispielbericht

Illustrative Ausgabe nach Umsetzung beider Arbeitspakete; die Zahlen sind
Beispieldaten und keine Messwerte dieses Projekts:

```text
Ergebnis: READY für den gelesenen Zustand
Modus: Migration
Ausgangsrevision: 7

users.display_name wird zum Pflichtfeld
  1. NULL-Werte mit user-display-name-v1 befüllen
  2. Auf verbleibende NULL-Werte prüfen
  3. NOT NULL setzen

NULL-Werte vorher: 12.450 (exakt gezählt)
Verbleibende NULL-Werte nach Projektion: keine gefunden
Vorhandene Nicht-NULL-Werte bleiben erhalten.
Fehlende Freigaben: keine
Offene erforderliche Prüfungen: keine

Die Migration benötigt exklusive Tabellensperren.
Die Ausführung prüft den Zustand unter ihren Sperren erneut.
```

## Erneute Prüfung bei der Ausführung

Der Executor liest vor der tatsächlichen Migration unter seinen vorhandenen
Sperren die Historie erneut, prüft den realen Zustand und erstellt den Plan neu.
Ein früherer Vorschau-Bericht ersetzt weder diese Prüfung noch notwendige
Freigaben. Er wird nicht als ausführbare SQL-Datei übernommen.

Geänderte Historie, Drift oder neu hinzugekommene problematische Daten müssen
auch nach einer zuvor erfolgreichen Vorschau zum Abbruch führen können.
Eine spätere Bindung von Freigaben an einen konkreten Plan-Fingerprint wäre eine
eigene Erweiterung und gehört nicht zu diesem Arbeitspaket.

## Teilaufgaben

| Paket | Bereich | Ergebnis |
| --- | --- | --- |
| AP 1 | `schema-executor` und Planungsmodell | Gemeinsamen Planer aus dem Executor herauslösen; strukturierte Schritte, Freigaben, Risiken und Befunde statt ausschließlich SQL oder Exception-Text. |
| AP 2 | `schema-postgresql` | Rein lesender Zugriff auf Historie und Katalog; Vergleichslogik von Sperren, Historienanlage und temporären CHECK-Prüftabellen trennen. |
| AP 3 | `schema-executor` | Vorschauablauf, eigene Lesetransaktion, Timeouts, Verbindungswiederherstellung und klare Behandlung unvollständiger Prüfungen. |
| AP 4 | `schema-postgresql` | Existenzprüfungen, optionale Zählungen und Projektion unterstützter Backfill-Ausdrücke für die Prüfung des Folgezustands. |
| AP 5 | `schema-integration` | Öffentliche Preview-API, gemeinsame Options- und Definitionsauflösung sowie Export von Zielmodell und optionalen Backfill-Definitionen. |
| AP 6 | `schema-cli` | Vorschau-Befehl, Text- und JSON-Ausgabe, Eingabevalidierung, Verbindungskonfiguration und Exit-Codes. |
| AP 7 | Tests und Dokumentation | Abnahmekriterien, Beispiele für Deployment-Prüfungen, Grenzen und Kompatibilität der bisherigen Migration. |

Zuerst werden gemeinsamer Plan und lesender Zugriff umgesetzt. Danach folgen
Datenprüfungen, API und CLI. Die Backfill-Projektion baut auf den Definitionen
und der Ausdruckssemantik des Backfill-Arbeitspakets auf. Tests entstehen mit
den jeweiligen Teilaufgaben.

## Abnahmekriterien

- [ ] Gleiche Eingaben und geprüfte Historie ergeben bei Vorschau und Executor
  dieselben Operationen, Reihenfolgen, Risiken und erforderlichen Freigaben.
- [ ] Fehlende Freigaben werden mit den betroffenen Schritten sichtbar. Nicht
  unterstützte Änderungen erzeugen keinen vermeintlich vollständigen Plan.
- [ ] Neuanlage, unverändertes Schema, reguläre Migration, Übernahme und manuelle
  Migration verwenden die bestehenden Regeln und werden eindeutig unterschieden.
- [ ] Eine leere Datenbank bleibt ohne neu angelegtes Historien-Schema oder
  Historientabellen. Bestehende Daten, Modelle und Historieneinträge bleiben nach
  Vorschauen unverändert.
- [ ] Tests des ausgeführten SQL und der Datenbankberechtigungen belegen, dass
  keine DDL-, datenverändernden DML-, Sequenz- oder temporären Tabellenoperationen stattfinden.
  Der Benutzer benötigt für die verfügbaren Prüfungen nur passende Leserechte.
- [ ] Korrekte, manipulierte und nicht vergleichbare CHECK-Definitionen werden
  unterschieden; der Constraint-Name allein kann keine Prüfung bestehen lassen.
- [ ] Historienfehler, Drift, stillgelegte IDs und geänderte bekannte Backfill-
  Definitionen werden auch bei unverändertem Zielmodell erkannt.
- [ ] NOT-NULL-, Unique-, FK- und unterstützte CHECK-Prüfungen berücksichtigen
  NULL-Semantik, zusammengesetzte Schlüssel und gequotete Bezeichner.
- [ ] Backfill-Projektionen erhalten vorhandene Werte, behandeln NULL-Quellen
  korrekt und erkennen sowohl verbleibende NULL-Werte als auch neu entstehende
  Constraint-Verletzungen.
- [ ] Neue Pflichtspalten auf leeren Tabellen sowie Umbenennungen und Quellen,
  die später gelöscht werden, werden ohne echte Schemaänderung geprüft.
- [ ] Timeouts, fehlende Leserechte, nicht unterstützte Projektionen und
  abgebrochene Transaktionen werden als offene oder fehlgeschlagene Prüfungen
  ausgewiesen und niemals als erfolgreiche Datenprüfung behandelt.
- [ ] Exakte Zählungen, Schätzwerte und unbekannte Werte sind im Bericht und im
  JSON eindeutig unterscheidbar. Ausgelassene optionale Zählungen erzeugen keine
  falsche Blockade; ausgelassene erforderliche Prüfungen verhindern `Ready`.
- [ ] Die Verbindung erhält ihren ursprünglichen Zustand zurück. Fehler bei
  Wiederherstellung oder Schließen werden behandelt, ohne Anwendungstransaktionen
  zu committen oder zu übernehmen.
- [ ] Eine zwischen Vorschau und Migration geänderte Datenbank wird bei der
  Ausführung erneut geprüft; der vorherige Bericht umgeht keine Sperre oder Regel.
- [ ] Text und JSON stimmen inhaltlich überein; JSON ist versioniert und enthält
  keine zusätzlichen Konsolentexte, Zugangsdaten oder Datensatzinhalte.
- [ ] API, Modell-Export, optionale Backfill-Exporte und CLI funktionieren
  zusammen; Exit-Codes decken `Ready`, `Blocked`, `Incomplete` und technische
  Aufruffehler ab.
- [ ] Bestehende Executor-, Dialekt-, Integrations- und CLI-Tests bleiben gültig;
  der vollständige Projektcheck `sbt check` besteht.

## Grenzen

Die Vorschau führt keine Migration durch und beweist nicht, dass jedes spätere
DDL-Statement erfolgreich sein wird. Sie garantiert weder freie Sperren beim
Deployment noch verfügbare Ressourcen, Schreibrechte oder die Kompatibilität
parallel laufender alter Serverversionen.

Lesende Datenprüfungen können die Datenbank belasten. Deshalb sind Timeouts,
wahlweise Prüfprofile und klare Angaben zum tatsächlich geprüften Umfang Teil
des Features. Eine schnelle Schemaansicht ersetzt keine ausgelassene Datenprüfung.

Nicht Bestandteil sind automatische Datenreparaturen, beliebige SQL-Simulation,
Ausführung exportierter Berichte, neue Migrationsarten, Hintergrund-Backfills und
ein Web-Dashboard. Der aktuelle Serverstart bleibt für die tatsächliche
Migration verantwortlich.
