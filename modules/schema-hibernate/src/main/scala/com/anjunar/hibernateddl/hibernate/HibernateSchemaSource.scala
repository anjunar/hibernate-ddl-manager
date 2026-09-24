package com.anjunar.hibernateddl.hibernate

import com.anjunar.hibernateddl.core.*
import com.anjunar.hibernateddl.hibernate.annotation.SchemaId as StableId
import org.hibernate.boot.Metadata
import org.hibernate.boot.model.naming.Identifier
import org.hibernate.cfg.MappingSettings
import org.hibernate.engine.config.spi.{ConfigurationService, StandardConverters}
import org.hibernate.annotations.OnDeleteAction
import org.hibernate.mapping.{Collection as CollectionMapping, Column, Component, PersistentClass, Property, Table}

import java.lang.reflect.AnnotatedElement
import java.util.HexFormat
import java.util.Locale
import java.util.concurrent.ThreadLocalRandom
import scala.jdk.CollectionConverters.*

/** Reads the desired physical schema from Hibernate boot metadata.
  *
  * Identities come from `@SchemaId` values of eight lowercase hex digits: a table uses its
  * entity's ID, a column `entity/property` and an embedded column `entity/embedded/property`.
  * An association's join column is a column like any other; its foreign key references the
  * other entity's table and primary key by ID.
  * Physical names are taken after Hibernate's naming strategies and folded the way the
  * configured dialect folds unquoted identifiers. Tables without a schema use
  * `hibernate.default_schema`. Mappings the core model cannot represent are reported
  * instead of dropped, so an incomplete model never reaches the diff.
  */
object HibernateSchemaSource extends DesiredSchemaSource[Metadata]:
  private val IdFormat = "[0-9a-f]{8}"
  // Stands in for a missing or invalid ID after it was reported, so each problem is reported once.
  private val Unknown = "?"
  private val VarcharType = """(?:varchar|character varying)\((\d+)\)""".r
  private val TimestampType = """timestamp\((\d+)\)(?: without time zone)?""".r
  private val TimestampWithTimeZoneType = """(?:timestamp\((\d+)\) with time zone|timestamptz\((\d+)\))""".r
  private val TimeType = """time\((\d+)\)(?: without time zone)?""".r
  private val CharType = """(?:char|character)\((\d+)\)""".r
  private val NumericType = """(?:numeric|decimal)\((\d+)(?:,\s*(\d+))?\)""".r
  // PostgreSQL maps float(1) to float(24) to real and float(25) to float(53) to double precision.
  private val FloatType = """float\((\d+)\)""".r
  // The two CHECK forms Hibernate generates for enums. Hibernate does not escape quotes in the
  // values, so a value containing one does not match and is reported instead of misread.
  private val ColumnReference = """("(?:[^"]|"")+"|[A-Za-z_][A-Za-z0-9_$]*)"""
  private val AllowedValuesCheck = s"""(?is)\\s*$ColumnReference\\s+in\\s*\\(\\s*('[^']*'(?:\\s*,\\s*'[^']*')*)\\s*\\)\\s*""".r
  private val RangeCheck = s"""(?is)\\s*$ColumnReference\\s+between\\s+(-?\\d+)\\s+and\\s+(-?\\d+)\\s*""".r
  private val Literal = """'([^']*)'""".r

  override def read(metadata: Metadata): Either[Vector[String], SchemaModel] =
    val reader = new Reader(metadata)
    val model = reader.read()
    val errors = reader.errors.result().distinct.sorted
    if errors.nonEmpty then Left(errors) else Right(model)

  /** A fresh random identity for a newly mapped entity or property. */
  def newId(): String = HexFormat.of().toHexDigits(ThreadLocalRandom.current().nextInt())

  private final case class Owned(column: Column, path: Vector[String], label: String)

  private final case class Mapped(label: String, table: Table, model: TableModel, columnIds: Map[Column, SchemaId])

  private final class Reader(metadata: Metadata):
    val errors = Vector.newBuilder[String]
    private val database = metadata.getDatabase
    private val names = database.getJdbcEnvironment.getIdentifierHelper
    // Resolved like Hibernate's SQL generation: explicit setting first, then orm.xml defaults.
    private val defaultSchema = defaultQualifier(
      MappingSettings.DEFAULT_SCHEMA, database.getPhysicalImplicitNamespaceName.schema(),
      database.getJdbcEnvironment.getNameQualifierSupport.supportsSchemas)
    private val defaultCatalog = defaultQualifier(
      MappingSettings.DEFAULT_CATALOG, database.getPhysicalImplicitNamespaceName.catalog(),
      database.getJdbcEnvironment.getNameQualifierSupport.supportsCatalogs)

    private def defaultQualifier(setting: String, implicitName: Identifier, supported: Boolean): Option[Identifier] =
      val configured = database.getServiceRegistry.requireService(classOf[ConfigurationService])
        .getSetting(setting, StandardConverters.STRING)
      Option.when(supported)(Option(names.toIdentifier(configured)).orElse(Option(implicitName))).flatten

    def read(): SchemaModel =
      val entities = metadata.getEntityBindings.asScala.toVector.sortBy(_.getEntityName)
      val entityTables = entities.map(_.getTable).toSet
      metadata.collectTableMappings.asScala.filterNot(entityTables.contains).foreach { table =>
        errors += s"Table ${table.getName} does not belong to an entity; collection and join tables are unsupported"
      }
      database.getNamespaces.asScala.flatMap(_.getSequences.asScala).foreach { sequence =>
        errors += s"Sequence ${sequence.getExportIdentifier} is unsupported; use assigned identifiers"
      }
      database.getAuxiliaryDatabaseObjects.asScala.foreach { auxiliary =>
        errors += s"Auxiliary database object ${auxiliary.getExportIdentifier} is unsupported"
      }
      val mapped = entities.flatMap(readEntity)
      mapped.groupBy(_.model.id).foreach { (id, owners) =>
        if owners.size > 1 then
          errors += s"Entities ${owners.map(_.label).sorted.mkString(", ")} share @SchemaId(\"${id.value}\")"
      }
      val byTable = mapped.map(entity => entity.table -> entity).toMap
      SchemaModel(mapped.map(entity => entity.model.copy(foreignKeys = foreignKeys(entity, byTable, entityTables))))

    /** Foreign keys of associations, referencing the primary key of another mapped entity. */
    private def foreignKeys(entity: Mapped, byTable: Map[Table, Mapped], entityTables: Set[Table]): Vector[ForeignKeyModel] =
      entity.table.getForeignKeyCollection.asScala.toVector.filter(_.isCreationEnabled).flatMap { key =>
        val columns = key.getColumns.asScala.toVector
        val label = s"Foreign key ${columns.map(_.getName).mkString("(", ", ", ")")} of entity ${entity.label}"
        val referenced = byTable.get(key.getReferencedTable)
        val problems = Vector(
          Option.when(!key.isReferenceToPrimaryKey)("references columns other than the primary key"),
          Option(key.getOnDeleteAction).filter(_ != OnDeleteAction.NO_ACTION).map(action => s"has ON DELETE $action"),
          Option.when(referenced.isEmpty && !entityTables.contains(key.getReferencedTable))(
            s"references table ${key.getReferencedTable.getName}, which belongs to no entity")
        ).flatten
        problems.foreach(problem => errors += s"$label $problem; unsupported")
        // An unreadable referenced entity or a column without an ID origin is reported on its own.
        val ids = columns.flatMap(entity.columnIds.get)
        referenced.filter(_ => problems.isEmpty && ids.size == columns.size).map { target =>
          ForeignKeyModel(ids, target.model.id, target.model.primaryKey)
        }
      }

    private def readEntity(entity: PersistentClass): Option[Mapped] =
      val label = Option(entity.getJpaEntityName).getOrElse(entity.getEntityName)
      val table = entity.getTable
      if entity.getSuperclass != null || entity.hasSubclasses then
        errors += s"Entity $label uses inheritance; unsupported"
        None
      else if !entity.getJoins.isEmpty then
        errors += s"Entity $label uses secondary tables; unsupported"
        None
      else if !table.isPhysicalTable then
        errors += s"Entity $label is not mapped to a physical table; unsupported"
        None
      else
        // Properties are checked even without a valid entity ID so that all problems surface at once.
        val entityId = stableId(Vector(entity.getMappedClass), s"Entity $label")
        val properties =
          (Option(entity.getIdentifierProperty) ++ Option(entity.getVersion) ++ entity.getProperties.asScala).toVector.distinct
        val owned = properties.flatMap(columns(_, entity.getMappedClass, Vector.empty, label))
        owned.groupBy(_.path).foreach { (path, sharing) =>
          val labels = sharing.map(_.label).distinct.sorted
          if labels.size > 1 && !path.contains(Unknown) then
            errors += s"${labels.mkString(" and ")} share @SchemaId(\"${path.last}\")"
        }
        owned.groupBy(_.column).foreach { (column, mapping) =>
          if mapping.size > 1 then
            errors += s"${mapping.map(_.label).sorted.mkString(" and ")} map the same column ${column.getName}; unsupported"
        }
        val byColumn = owned.map(o => o.column -> o).toMap
        def columnId(o: Owned) = SchemaId((entityId.getOrElse(Unknown) +: o.path).mkString("/"))

        Vector(
          Option.when(!table.getChecks.isEmpty)("check constraints")
        ).flatten.foreach(feature => errors += s"Entity $label has $feature; unsupported")

        val columnModels = table.getColumns.asScala.toVector.flatMap { column =>
          byColumn.get(column) match
            case Some(o) => columnModel(column, columnId(o), o.label)
            case None =>
              errors += s"Column ${column.getName} of entity $label has no @SchemaId origin (e.g. a discriminator or join column); unsupported"
              None
        }
        val primaryKey = Option(table.getPrimaryKey).toVector
          .flatMap(_.getColumns.asScala).flatMap(byColumn.get).map(columnId)
        // @UniqueConstraint and @NaturalId become unique keys; @Column(unique) and @OneToOne only mark the column.
        val uniqueKeys = table.getUniqueKeys.asScala.values.toVector.flatMap { key =>
          val ordered = key.getColumnOrderMap.asScala.values.forall(order => order == null || order.isBlank ||
            order.trim.equalsIgnoreCase("asc"))
          if !ordered then errors += s"Unique key ${key.getName} of entity $label orders its columns; unsupported"
          Option.when(ordered)(key.getColumns.asScala.toVector)
        } ++ table.getColumns.asScala.toVector.filter(_.isUnique).map(Vector(_))
        val uniqueKeyModels = uniqueKeys.distinct.flatMap { columns =>
          val ids = columns.flatMap(byColumn.get).map(columnId)
          Option.when(ids.size == columns.size)(UniqueKeyModel(ids))
        }
        entityId.map { id =>
          Mapped(label, table, TableModel(SchemaId(id), qualifiedName(table), columnModels, primaryKey,
            uniqueKeys = uniqueKeyModels, indexes = indexes(table, label, byColumn.view.mapValues(columnId).toMap)),
            byColumn.view.mapValues(columnId).toMap)
        }

    /** @Index becomes a plain index; @Index(unique = true) is a unique key in Hibernate's model. */
    private def indexes(table: Table, entity: String, columnIds: Map[Column, SchemaId]): Vector[IndexModel] =
      table.getIndexes.asScala.values.toVector.flatMap { index =>
        val label = s"Index ${index.getName} of entity $entity"
        val selectables = index.getSelectables.asScala.toVector
        val columns = selectables.collect { case column: Column => column }
        val orders = index.getSelectableOrderMap.asScala
        val directions = columns.map(column => Option(orders.getOrElse(column, null)).fold("")(_.trim.toLowerCase(Locale.ROOT)))
        val problems = Vector(
          Option(index.getOptions).filterNot(_.isBlank).map(options => s"has options '$options'"),
          Option.when(columns.size != selectables.size)("indexes an expression"),
          Option.when(index.isUnique)("is a unique index"),
          directions.find(direction => !Set("", "asc", "desc").contains(direction)).map(order => s"orders a column '$order'")
        ).flatten
        problems.foreach(problem => errors += s"$label $problem; unsupported")
        val ids = columns.flatMap(columnIds.get)
        Option.when(problems.isEmpty && ids.size == columns.size) {
          IndexModel(ids.zip(directions).map((id, direction) => IndexColumn(id, direction == "desc")))
        }
      }

    private def columns(property: Property, owner: Class[?], path: Vector[String], ownerLabel: String): Vector[Owned] =
      val label = s"$ownerLabel.${property.getName}"
      val selected = property.getValue.getSelectables.asScala.toVector.collect { case column: Column => column }
      property.getValue match
        case _ if property.isSynthetic =>
          errors += s"$label is a synthetic Hibernate property (e.g. a unidirectional one-to-many join column); unsupported"
          selected.map(Owned(_, path :+ Unknown, label))
        case _: CollectionMapping => Vector.empty // A collection table is reported separately.
        case _ if selected.isEmpty => Vector.empty // Formulas and inverse sides own no column.
        case component: Component =>
          val id = stableId(members(owner, property.getName), label).getOrElse(Unknown)
          component.getProperties.asScala.toVector.flatMap(columns(_, component.getComponentClass, path :+ id, label))
        case value =>
          if selected.size > 1 then errors += s"$label maps to ${selected.size} columns; multi-column values are unsupported"
          val id = stableId(members(owner, property.getName), label).getOrElse(Unknown)
          selected.map(Owned(_, path :+ id, label))

    private def columnModel(column: Column, id: SchemaId, label: String): Option[ColumnModel] =
      Vector(
        Option.when(column.getDefaultValue != null)("a default value"),
        Option.when(column.getGeneratedAs != null)("a generation expression"),
        Option.when(column.isIdentity)("identity generation"),
        Option.when(column.getCollation != null)("a custom collation")
      ).flatten.foreach(feature => errors += s"$label has $feature; unsupported")
      val sqlType = column.getSqlType(metadata)
      val dataType = sqlType.trim.toLowerCase(Locale.ROOT) match
        case "integer" | "int" | "int4" => Some(SqlType.Integer)
        case "bigint" | "int8" => Some(SqlType.BigInt)
        case "boolean" | "bool" => Some(SqlType.Boolean)
        case "text" => Some(SqlType.Text)
        case "uuid" => Some(SqlType.Uuid)
        case "smallint" | "int2" => Some(SqlType.SmallInt)
        case "real" | "float4" => Some(SqlType.Real)
        case "double precision" | "float8" | "float" => Some(SqlType.DoublePrecision)
        case "date" => Some(SqlType.Date)
        case "bytea" => Some(SqlType.Binary)
        case FloatType(digits) => digits.toIntOption.collect {
          case bits if bits >= 1 && bits <= 24 => SqlType.Real
          case bits if bits >= 25 && bits <= 53 => SqlType.DoublePrecision
        }
        case CharType(length) => length.toIntOption.map(SqlType.Char(_))
        case NumericType(precision, scale) =>
          for p <- precision.toIntOption; s <- Option(scale).getOrElse("0").toIntOption yield SqlType.Numeric(p, s)
        case TimeType(precision) => precision.toIntOption.map(SqlType.Time(_))
        case VarcharType(length) => length.toIntOption.map(SqlType.Varchar(_))
        case TimestampType(precision) => precision.toIntOption.map(SqlType.Timestamp(_))
        case TimestampWithTimeZoneType(long, short) =>
          Option(long).orElse(Option(short)).flatMap(_.toIntOption).map(SqlType.TimestampWithTimeZone(_))
        case _ => None
      if dataType.isEmpty then errors += s"$label has SQL type '$sqlType'; unsupported"
      val check = columnCheck(column, label)
      dataType.map(ColumnModel(id, physical(Identifier.toIdentifier(column.getName, column.isQuoted)), _, column.isNullable, check))

    private def columnCheck(column: Column, label: String): Option[ColumnCheck] =
      column.getCheckConstraints.asScala.toVector.map(_.getConstraint) match
        case Vector() => None
        case Vector(text) =>
          def refersToColumn(reference: String) =
            if reference.startsWith("\"") then reference.drop(1).dropRight(1).replace("\"\"", "\"") == column.getName
            else !column.isQuoted && reference.equalsIgnoreCase(column.getName)
          val check = text match
            case AllowedValuesCheck(reference, values) if refersToColumn(reference) =>
              Some(ColumnCheck.AllowedValues(Literal.findAllMatchIn(values).map(_.group(1)).toVector))
            case RangeCheck(reference, min, max) if refersToColumn(reference) =>
              for low <- min.toLongOption; high <- max.toLongOption yield ColumnCheck.Range(low, high)
            case _ => None
          if check.isEmpty then errors += s"$label has the check constraint '$text'; only enum checks are supported"
          check
        case texts =>
          errors += s"$label has ${texts.size} check constraints; unsupported"
          None

    private def qualifiedName(table: Table): QualifiedName =
      QualifiedName(
        physical(table.getNameIdentifier),
        Option(table.getSchemaIdentifier).orElse(defaultSchema).map(physical),
        Option(table.getCatalogIdentifier).orElse(defaultCatalog).map(physical)
      )

    private def physical(identifier: Identifier): SqlIdentifier = SqlIdentifier(names.toMetaDataObjectName(identifier))

    private def stableId(elements: Vector[AnnotatedElement], label: String): Option[String] =
      elements.flatMap(element => Option(element.getAnnotation(classOf[StableId]))).map(_.value).distinct match
        case Vector(id) if id.matches(IdFormat) => Some(id)
        case Vector(id) =>
          errors += s"$label has @SchemaId(\"$id\"); expected eight lowercase hex digits such as \"${newId()}\""
          None
        case Vector() =>
          errors += s"$label has no @SchemaId; add e.g. @SchemaId(\"${newId()}\")"
          None
        case ids =>
          errors += s"$label has conflicting @SchemaId values ${ids.mkString(", ")}"
          None

  /** The field and getters Hibernate may read for a property, including inherited ones. */
  private def members(owner: Class[?], name: String): Vector[AnnotatedElement] =
    val getters = Set(name, s"get${name.capitalize}", s"is${name.capitalize}")
    Iterator.iterate[Class[?]](owner)(_.getSuperclass).takeWhile(_ != null).toVector.flatMap { declaring =>
      declaring.getDeclaredFields.toVector.filter(_.getName == name) ++
        declaring.getDeclaredMethods.toVector.filter(m => m.getParameterCount == 0 && getters.contains(m.getName))
    }
