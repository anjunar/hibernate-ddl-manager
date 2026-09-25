package com.anjunar.hibernateddl.executor

import com.anjunar.hibernateddl.core.*

/** A column's value as the migration will leave it, over the table as it is now. */
enum Projection:
  /** An existing column the migration keeps as it is, by its current name. */
  case Current(column: SqlIdentifier)
  /** A new column without a backfill, NULL in every row. */
  case Absent(dataType: SqlType)
  /** A column whose NULLs a backfill fills; `current` is None for a new column. */
  case Filled(current: Option[SqlIdentifier], value: FillValue, dataType: SqlType)

/** A read-only question about the data, asked of the table as it is now. */
enum DataQuery:
  /** Rows in which the value is NULL. */
  case Nulls(table: QualifiedName, value: Projection)
  /** Rows sharing a key whose parts are all not NULL. */
  case Duplicates(table: QualifiedName, key: Vector[Projection])
  /** Rows whose key, with every part not NULL, finds no referenced row; `referenced` is None
    * when the referenced table is new and empty.
    */
  case Unresolved(table: QualifiedName, key: Vector[Projection], referenced: Option[(QualifiedName, Vector[SqlIdentifier])])
  /** Rows for which the check on a column of this type is FALSE; NULL satisfies a check. */
  case Violations(table: QualifiedName, value: Projection, dataType: SqlType, check: ColumnCheck)

final case class PlannedDataCheck(
    code: String,
    description: String,
    step: Option[Int],
    subject: Option[String],
    query: DataQuery,
    failure: String,
    required: Boolean = true
)

/** The data checks a migration plan needs, expressed over the current tables: columns that
  * become required, new unique keys, foreign keys and checks, and existing ones over a column
  * a backfill fills. Renames and later drops do not happen in the database, so every column is
  * read under its current name, and new columns are projected.
  */
object PreviewDataChecks:
  def planned(plan: MigrationPlan): Vector[PlannedDataCheck] =
    val previous = plan.previous.tables.map(table => table.id -> table).toMap
    val target = plan.target.tables.map(table => table.id -> table).toMap
    val numbered = plan.steps.zipWithIndex.map((step, index) => step -> (index + 1))
    val fills = plan.steps.collect { case fill: PlanStep.Fill => fill.column -> fill.backfill }.toMap
    def stepOf(matches: PlanStep => Boolean) = numbered.collectFirst { case (step, number) if matches(step) => number }

    /** The projection of every target column of an existing table, or the problem resolving a fill. */
    def projections(tableId: SchemaId): Map[SchemaId, Projection] =
      val before = previous(tableId)
      val after = target(tableId)
      val current = before.columns.map(column => column.id -> column).toMap
      val absent = after.columns.map(_.id).filterNot(current.contains).toSet
      after.columns.map { column =>
        val projection = fills.get(column.id) match
          case Some(backfill) =>
            // Fills run after type changes: sources keep their current names but have their target types.
            val retyped = before.columns.map(c => after.columns.find(_.id == c.id).fold(c)(t => c.copy(dataType = t.dataType)))
            val readable = retyped ++ after.columns.filter(c => absent.contains(c.id))
            BackfillValidation.resolve(backfill, before.name, column, readable, fills.keySet - column.id, absent) match
              case Right(fill) => Projection.Filled(current.get(column.id).map(_.name), fill.value, column.dataType)
              case Left(_) => Projection.Absent(column.dataType)
          case None => current.get(column.id).fold(Projection.Absent(column.dataType))(c => Projection.Current(c.name))
        column.id -> projection
      }.toMap

    val existing = plan.operations.flatMap {
      case SchemaOperation.SetNotNull(tableId, _, _, _) => Some(tableId)
      case SchemaOperation.AddUniqueKey(tableId, _, _) => Some(tableId)
      case SchemaOperation.AddForeignKey(tableId, _, _, _, _) => Some(tableId)
      case SchemaOperation.ChangeCheck(tableId, _, _, _, _, Some(_)) => Some(tableId)
      case _ => None
    }.distinct.filter(previous.contains)
    existing.flatMap { tableId =>
      val before = previous(tableId)
      val after = target(tableId)
      val table = before.name
      val projected = projections(tableId)
      def name(id: SchemaId) = after.columns.find(_.id == id).get.name.value
      def keyNames(ids: Vector[SchemaId]) = ids.map(name).mkString("(", ", ", ")")
      val filled = after.columns.map(_.id).filter(fills.contains).toSet
      val required = plan.operations.collect {
        case SchemaOperation.SetNotNull(`tableId`, _, columnId, column) =>
          PlannedDataCheck(PreviewCheck.NoNulls, s"No row of ${table.display} leaves ${column.value} NULL when it becomes required",
            stepOf { case PlanStep.Statement(SchemaOperation.SetNotNull(_, _, `columnId`, _), _, _, _) => true; case _ => false },
            Some(columnId.value), DataQuery.Nulls(table, projected(columnId)),
            s"Rows of ${table.display} would keep ${column.value} NULL; register a backfill or fill them first")
      }
      val newUnique = after.uniqueKeys.filterNot(before.uniqueKeys.contains)
      val unique = (newUnique ++ after.uniqueKeys.filter(_.columns.exists(filled.contains))).distinct.map { key =>
        PlannedDataCheck(PreviewCheck.NoDuplicates, s"Unique key ${keyNames(key.columns)} of ${table.display} has no duplicates",
          stepOf { case PlanStep.Statement(SchemaOperation.AddUniqueKey(`tableId`, _, _), _, _, _) => true; case _ => false }
            .filter(_ => newUnique.contains(key)), Some(tableId.value), DataQuery.Duplicates(table, key.columns.map(projected)),
          s"Rows of ${table.display} would share a value of unique key ${keyNames(key.columns)}")
      }
      val newKeys = after.foreignKeys.filterNot(before.foreignKeys.contains)
      val references = (newKeys ++ after.foreignKeys.filter(_.columns.exists(filled.contains))).distinct.map { key =>
        val referenced = previous.get(key.referencedTable).map { table =>
          table.name -> key.referencedColumns.map(id => table.columns.find(_.id == id).get.name)
        }
        PlannedDataCheck(PreviewCheck.ReferencesResolve,
          s"Foreign key ${keyNames(key.columns)} of ${table.display} finds every referenced row",
          stepOf { case PlanStep.Statement(SchemaOperation.AddForeignKey(`tableId`, _, _, _, _), _, _, _) => true; case _ => false }
            .filter(_ => newKeys.contains(key)), Some(tableId.value),
          DataQuery.Unresolved(table, key.columns.map(projected), referenced),
          s"Rows of ${table.display} would reference missing rows through ${keyNames(key.columns)}")
      }
      val checks = after.columns.filter(_.check.nonEmpty).filter { column =>
        before.columns.find(_.id == column.id).forall(_.check != column.check) || filled.contains(column.id)
      }.map { column =>
        PlannedDataCheck(PreviewCheck.CheckHolds, s"Every row of ${table.display} satisfies the check of ${column.name.value}",
          // The step that sets the check; a type change removes it in an earlier one.
          stepOf { case PlanStep.Statement(SchemaOperation.ChangeCheck(_, _, column.id, _, _, Some(_)), _, _, _) => true; case _ => false },
          Some(column.id.value), DataQuery.Violations(table, projected(column.id), column.dataType, column.check.get),
          s"Rows of ${table.display} would violate the check of ${column.name.value}")
      }
      required ++ unique ++ references ++ checks
    }
