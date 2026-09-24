package com.anjunar.hibernateddl.core

/** Plans creation, nullable additions and explicit-ID renames; all other changes fail closed. */
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

  private def plan(
      previous: SchemaModel,
      desired: SchemaModel
  ): Either[Vector[String], Vector[SchemaOperation]] =
    val errors = Vector.newBuilder[String]
    val operations = Vector.newBuilder[SchemaOperation]
    val oldTables = previous.tables.map(t => t.id -> t).toMap
    val newTables = desired.tables.map(t => t.id -> t).toMap
    val oldLocations = locations(previous)
    val newLocations = locations(desired)

    (oldLocations.keySet intersect newLocations.keySet).foreach { id =>
      if oldLocations(id) != newLocations(id) then
        errors += s"Stable ID '${id.value}' was reused from ${oldLocations(id)} to ${newLocations(id)}; manual migration required"
    }

    (oldTables.keySet -- newTables.keySet).foreach { id =>
      errors += s"Dropping table '${id.value}' is unsupported; manual migration required"
    }
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
      (oldColumns.keySet -- newColumns.keySet).foreach { columnId =>
        errors += s"Dropping column '${columnId.value}' from table '${id.value}' is unsupported; manual migration required"
      }
      (oldColumns.keySet intersect newColumns.keySet).toVector.sortBy(_.value).foreach { columnId =>
        val oldColumn = oldColumns(columnId)
        val newColumn = newColumns(columnId)
        if oldColumn.dataType != newColumn.dataType then
          errors += s"Changing type of column '${columnId.value}' is unsupported; manual migration required"
        if oldColumn.nullable != newColumn.nullable then
          errors += s"Changing nullability of column '${columnId.value}' is unsupported; manual migration required"
        if oldColumn.name != newColumn.name then
          oldTable.columns.find(c => c.id != columnId && c.name == newColumn.name) match
            case Some(occupant) =>
              errors += s"Renaming column '${columnId.value}' collides with previous column '${occupant.id.value}'; dependent or swap renames require manual migration"
            case None =>
              operations += SchemaOperation.RenameColumn(id, newTable.name, columnId, oldColumn.name, newColumn.name)
      }
      (newColumns.keySet -- oldColumns.keySet).toVector.sortBy(_.value).foreach { columnId =>
        val column = newColumns(columnId)
        if !column.nullable then
          errors += s"Adding non-null column '${columnId.value}' to existing table '${id.value}' requires an explicit backfill"
        else if oldTable.columns.exists(_.name == column.name) then
          errors += s"Adding column '${columnId.value}' uses an occupied previous name in table '${id.value}'; manual migration required"
        else operations += SchemaOperation.AddColumn(id, newTable.name, column)
      }
    }

    (newTables.keySet -- oldTables.keySet).toVector.sortBy(_.value).foreach { id =>
      val table = newTables(id)
      previous.tables.find(_.name == table.name) match
        case Some(occupant) =>
          errors += s"Creating table '${id.value}' uses the previous name of table '${occupant.id.value}'; manual migration required"
        case None => operations += SchemaOperation.CreateTable(table)
    }

    val diagnostics = errors.result().distinct.sorted
    if diagnostics.nonEmpty then Left(diagnostics) else Right(operations.result())

  private def locations(model: SchemaModel): Map[SchemaId, String] =
    model.tables.flatMap { table =>
      (table.id -> "table") +: table.columns.map { column =>
        column.id -> s"column in table '${table.id.value}'"
      }
    }.toMap
