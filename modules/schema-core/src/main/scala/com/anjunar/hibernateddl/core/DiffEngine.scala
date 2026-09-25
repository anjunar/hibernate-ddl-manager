package com.anjunar.hibernateddl.core

/** Plans creation, added columns, new unique keys, indexes and foreign keys, changed column
  * checks, nullability changes, explicit-ID renames and drops of columns, tables and
  * sequences; all other changes fail closed. A new required column in an existing table is
  * added nullable and made required later. Foreign keys are added after every table exists,
  * then columns become required, and drops come last, so that nothing planned before them
  * still needs what they delete. Whether a drop may run, and how NULLs of a column that
  * becomes required are filled, is the caller's decision.
  */
object DiffEngine:
  def diff(
      previous: SchemaModel,
      desired: SchemaModel
  ): Either[Vector[String], Vector[SchemaOperation]] =
    val validationErrors =
      SchemaValidation.validate(previous).map("Previous schema: " + _) ++
        SchemaValidation.validate(desired).map("Desired schema: " + _)
    if validationErrors.nonEmpty then Left(validationErrors.sorted)
    else plan(previous, desired)

  private given Ordering[Vector[String]] = Ordering.Implicits.seqOrdering[Vector, String]

  private def plan(
      previous: SchemaModel,
      desired: SchemaModel
  ): Either[Vector[String], Vector[SchemaOperation]] =
    val errors = Vector.newBuilder[String]
    val operations = Vector.newBuilder[SchemaOperation]
    val addedKeys = Vector.newBuilder[(TableModel, ForeignKeyModel)]
    val droppedColumns = Vector.newBuilder[SchemaOperation]
    val required = Vector.newBuilder[SchemaOperation]
    val droppedSequences = planSequences(previous, desired, errors, operations)
    val oldTables = previous.tables.map(t => t.id -> t).toMap
    val newTables = desired.tables.map(t => t.id -> t).toMap
    val oldLocations = locations(previous)
    val newLocations = locations(desired)

    (oldLocations.keySet intersect newLocations.keySet).foreach { id =>
      if oldLocations(id) != newLocations(id) then
        errors += s"Stable ID '${id.value}' was reused from ${oldLocations(id)} to ${newLocations(id)}; manual migration required"
    }

    val droppedTables = (oldTables.keySet -- newTables.keySet).toVector.sortBy(_.value)
    (oldTables.keySet intersect newTables.keySet).toVector.sortBy(_.value).foreach { id =>
      val oldTable = oldTables(id)
      val newTable = newTables(id)
      if oldTable.name.schema != newTable.name.schema || oldTable.name.catalog != newTable.name.catalog then
        errors += s"Moving table '${id.value}' between schemas or catalogs is unsupported; manual migration required"
      else if oldTable.name != newTable.name then
        previous.tables.find(t => t.id != id && t.name == newTable.name) match
          case Some(occupant) =>
            errors += s"Renaming table '${id.value}' collides with previous table '${occupant.id.value}'; dependent or swap renames require manual migration"
          case None =>
            operations += SchemaOperation.RenameTable(id, oldTable.name, newTable.name)
      if oldTable.primaryKey != newTable.primaryKey then
        errors += s"Changing primary key of table '${id.value}' is unsupported; manual migration required"

      val oldColumns = oldTable.columns.map(c => c.id -> c).toMap
      val newColumns = newTable.columns.map(c => c.id -> c).toMap
      val dropped = (oldColumns.keySet -- newColumns.keySet).toVector.sortBy(_.value)
      dropped.foreach { columnId =>
        droppedColumns += SchemaOperation.DropColumn(id, newTable.name, columnId, oldColumns(columnId).name)
      }
      // Keys and indexes over a dropped column disappear with it.
      def remaining(columns: Vector[SchemaId]) = !columns.exists(dropped.contains)
      (oldColumns.keySet intersect newColumns.keySet).toVector.sortBy(_.value).foreach { columnId =>
        val oldColumn = oldColumns(columnId)
        val newColumn = newColumns(columnId)
        if oldColumn.dataType != newColumn.dataType then
          errors += s"Changing type of column '${columnId.value}' is unsupported; manual migration required"
        if oldColumn.identity != newColumn.identity then
          errors += s"Changing identity generation of column '${columnId.value}' is unsupported; manual migration required"
        if oldColumn.name != newColumn.name then
          oldTable.columns.find(c => c.id != columnId && c.name == newColumn.name) match
            case Some(occupant) =>
              errors += s"Renaming column '${columnId.value}' collides with previous column '${occupant.id.value}'; dependent or swap renames require manual migration"
            case None =>
              operations += SchemaOperation.RenameColumn(id, newTable.name, columnId, oldColumn.name, newColumn.name)
        // After the rename, whose new name it uses. Primary key and identity columns cannot
        // become nullable in a valid model.
        if oldColumn.nullable && !newColumn.nullable then
          required += SchemaOperation.SetNotNull(id, newTable.name, columnId, newColumn.name)
        else if !oldColumn.nullable && newColumn.nullable then
          operations += SchemaOperation.DropNotNull(id, newTable.name, columnId, newColumn.name)
        if oldColumn.check != newColumn.check && oldColumn.dataType == newColumn.dataType then
          operations += SchemaOperation.ChangeCheck(id, newTable.name, columnId, newColumn.name, oldColumn.check, newColumn.check)
      }
      (newColumns.keySet -- oldColumns.keySet).toVector.sortBy(_.value).foreach { columnId =>
        val column = newColumns(columnId)
        if column.identity then
          errors += s"Adding identity column '${columnId.value}' to existing table '${id.value}' is unsupported; manual migration required"
        else if oldTable.columns.exists(_.name == column.name) then
          errors += s"Adding column '${columnId.value}' uses an occupied previous name in table '${id.value}'; manual migration required"
        else
          operations += SchemaOperation.AddColumn(id, newTable.name, column.copy(nullable = true))
          if !column.nullable then required += SchemaOperation.SetNotNull(id, newTable.name, columnId, column.name)
      }

      val oldUniqueKeys = oldTable.uniqueKeys.toSet
      newTable.uniqueKeys.filterNot(oldUniqueKeys.contains).sortBy(_.columns.map(_.value)).foreach { key =>
        operations += SchemaOperation.AddUniqueKey(id, newTable.name,
          key.columns.map(columnId => newTable.columns.find(_.id == columnId).get.name))
      }
      (oldUniqueKeys -- newTable.uniqueKeys).filter(key => remaining(key.columns)).foreach { key =>
        errors += s"Dropping unique key ${key.display} from table '${id.value}' is unsupported; manual migration required"
      }
      val oldIndexes = oldTable.indexes.toSet
      operations ++= createIndexes(newTable, newTable.indexes.filterNot(oldIndexes.contains))
      (oldIndexes -- newTable.indexes).filter(index => remaining(index.columns.map(_.column))).foreach { index =>
        errors += s"Dropping index ${index.display} from table '${id.value}' is unsupported; manual migration required"
      }

      val oldKeys = oldTable.foreignKeys.map(key => key.columns -> key).toMap
      newTable.foreignKeys.foreach { key =>
        oldKeys.get(key.columns) match
          case None => addedKeys += newTable -> key
          case Some(old) if old != key =>
            errors += s"Changing foreign key ${key.display} of table '${id.value}' is unsupported; manual migration required"
          case Some(_) => ()
      }
      (oldKeys.keySet -- newTable.foreignKeys.map(_.columns)).filter(remaining).foreach { columns =>
        errors += s"Dropping foreign key ${oldKeys(columns).display} from table '${id.value}' is unsupported; manual migration required"
      }
    }

    (newTables.keySet -- oldTables.keySet).toVector.sortBy(_.value).foreach { id =>
      val table = newTables(id)
      previousRelation(previous, table.name, id) match
        case Some(occupant) =>
          errors += s"Creating table '${id.value}' uses the previous name of '${occupant.value}'; manual migration required"
        case None =>
          operations += SchemaOperation.CreateTable(table.copy(foreignKeys = Vector.empty, indexes = Vector.empty))
          operations ++= createIndexes(table, table.indexes)
          table.foreignKeys.foreach(key => addedKeys += table -> key)
    }

    addedKeys.result().sortBy((table, key) => (table.id.value, key.columns.map(_.value))).foreach { (table, key) =>
      val referenced = newTables(key.referencedTable)
      def names(owner: TableModel, ids: Vector[SchemaId]) = ids.map(id => owner.columns.find(_.id == id).get.name)
      operations += SchemaOperation.AddForeignKey(table.id, table.name, names(table, key.columns),
        referenced.name, names(referenced, key.referencedColumns))
    }

    operations ++= required.result()
    operations ++= droppedColumns.result()
    if droppedTables.nonEmpty then
      operations += SchemaOperation.DropTables(droppedTables.map(id => SchemaOperation.DroppedTable(id, oldTables(id).name)))
    operations ++= droppedSequences

    val diagnostics = errors.result().distinct.sorted
    if diagnostics.nonEmpty then Left(diagnostics) else Right(operations.result())

  private def createIndexes(table: TableModel, indexes: Vector[IndexModel]): Vector[SchemaOperation] =
    val byColumnsAndDirection = Ordering.Implicits.seqOrdering[Vector, (String, Boolean)]
    indexes.sortBy(_.columns.map(c => c.column.value -> c.descending))(using byColumnsAndDirection).map { index =>
      SchemaOperation.CreateIndex(table.id, table.name, index.columns.map { column =>
        SchemaOperation.IndexedColumn(table.columns.find(_.id == column.column).get.name, column.descending)
      })
    }

  /** Sequences are created or renamed before any table and dropped after everything else,
    * which the returned operations do; changing one is refused.
    */
  private def planSequences(
      previous: SchemaModel,
      desired: SchemaModel,
      errors: collection.mutable.Growable[String],
      operations: collection.mutable.Growable[SchemaOperation]
  ): Vector[SchemaOperation] =
    val oldSequences = previous.sequences.map(s => s.id -> s).toMap
    val drops = (oldSequences.keySet -- desired.sequences.map(_.id)).toVector.sortBy(_.value).map { id =>
      SchemaOperation.DropSequence(id, oldSequences(id).name)
    }
    desired.sequences.sortBy(_.id.value).foreach { sequence =>
      oldSequences.get(sequence.id) match
        case None =>
          previousRelation(previous, sequence.name, sequence.id) match
            case Some(occupant) =>
              errors += s"Creating sequence '${sequence.id.value}' uses the previous name of '${occupant.value}'; manual migration required"
            case None => operations += SchemaOperation.CreateSequence(sequence)
        case Some(old) =>
          if old.start != sequence.start || old.increment != sequence.increment then
            errors += s"Changing start or increment of sequence '${sequence.id.value}' is unsupported; manual migration required"
          if old.name.schema != sequence.name.schema || old.name.catalog != sequence.name.catalog then
            errors += s"Moving sequence '${sequence.id.value}' between schemas or catalogs is unsupported; manual migration required"
          else if old.name != sequence.name then
            previousRelation(previous, sequence.name, sequence.id) match
              case Some(occupant) =>
                errors += s"Renaming sequence '${sequence.id.value}' collides with previous '${occupant.value}'; manual migration required"
              case None => operations += SchemaOperation.RenameSequence(sequence.id, old.name, sequence.name)
    }
    drops

  /** The previous table or sequence other than `self` that had this name. */
  private def previousRelation(previous: SchemaModel, name: QualifiedName, self: SchemaId): Option[SchemaId] =
    (previous.tables.map(t => t.id -> t.name) ++ previous.sequences.map(s => s.id -> s.name))
      .collectFirst { case (id, relation) if id != self && relation == name => id }

  private def locations(model: SchemaModel): Map[SchemaId, String] =
    (model.tables.flatMap { table =>
      (table.id -> "table") +: table.columns.map { column =>
        column.id -> s"column in table '${table.id.value}'"
      }
    } ++ model.sequences.map(_.id -> "sequence")).toMap
