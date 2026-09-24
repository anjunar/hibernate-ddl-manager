package io.github.hibernateddl.hibernate

import io.github.hibernateddl.core.*
import io.github.hibernateddl.hibernate.annotation.SchemaId as StableId
import org.hibernate.boot.Metadata
import org.hibernate.boot.model.naming.Identifier
import org.hibernate.cfg.MappingSettings
import org.hibernate.engine.config.spi.{ConfigurationService, StandardConverters}
import org.hibernate.mapping.{Collection as CollectionMapping, Column, Component, PersistentClass, Property, Table, ToOne}

import java.lang.reflect.AnnotatedElement
import java.util.HexFormat
import java.util.Locale
import java.util.concurrent.ThreadLocalRandom
import scala.jdk.CollectionConverters.*

/** Reads the desired physical schema from Hibernate boot metadata.
  *
  * Identities come from `@SchemaId` values of eight lowercase hex digits: a table uses its
  * entity's ID, a column `entity/property` and an embedded column `entity/embedded/property`.
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

  override def read(metadata: Metadata): Either[Vector[String], SchemaModel] =
    val reader = new Reader(metadata)
    val model = reader.read()
    val errors = reader.errors.result().distinct.sorted
    if errors.nonEmpty then Left(errors) else Right(model)

  /** A fresh random identity for a newly mapped entity or property. */
  def newId(): String = HexFormat.of().toHexDigits(ThreadLocalRandom.current().nextInt())

  private final case class Owned(column: Column, path: Vector[String], label: String)

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
      val tables = entities.flatMap(readEntity)
      tables.groupBy(_._2.id).foreach { (id, owners) =>
        if owners.size > 1 then
          errors += s"Entities ${owners.map(_._1).sorted.mkString(", ")} share @SchemaId(\"${id.value}\")"
      }
      SchemaModel(tables.map(_._2))

    private def readEntity(entity: PersistentClass): Option[(String, TableModel)] =
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
          Option.when(!table.getUniqueKeys.isEmpty)("unique constraints"),
          Option.when(!table.getIndexes.isEmpty)("indexes"),
          Option.when(!table.getForeignKeyCollection.isEmpty)("foreign keys"),
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
        entityId.map(id => label -> TableModel(SchemaId(id), qualifiedName(table), columnModels, primaryKey))

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
          if value.isInstanceOf[ToOne] then errors += s"$label is an association; foreign keys are unsupported"
          if selected.size > 1 then errors += s"$label maps to ${selected.size} columns; multi-column values are unsupported"
          val id = stableId(members(owner, property.getName), label).getOrElse(Unknown)
          selected.map(Owned(_, path :+ id, label))

    private def columnModel(column: Column, id: SchemaId, label: String): Option[ColumnModel] =
      Vector(
        Option.when(column.getDefaultValue != null)("a default value"),
        Option.when(column.getGeneratedAs != null)("a generation expression"),
        Option.when(column.isIdentity)("identity generation"),
        Option.when(column.getCollation != null)("a custom collation"),
        Option.when(column.hasCheckConstraint)("a check constraint"),
        Option.when(column.isUnique)("a unique constraint")
      ).flatten.foreach(feature => errors += s"$label has $feature; unsupported")
      val sqlType = column.getSqlType(metadata)
      val dataType = sqlType.trim.toLowerCase(Locale.ROOT) match
        case "integer" | "int" | "int4" => Some(SqlType.Integer)
        case "bigint" | "int8" => Some(SqlType.BigInt)
        case "boolean" | "bool" => Some(SqlType.Boolean)
        case "text" => Some(SqlType.Text)
        case "uuid" => Some(SqlType.Uuid)
        case VarcharType(length) => length.toIntOption.map(SqlType.Varchar(_))
        case TimestampType(precision) => precision.toIntOption.map(SqlType.Timestamp(_))
        case TimestampWithTimeZoneType(long, short) =>
          Option(long).orElse(Option(short)).flatMap(_.toIntOption).map(SqlType.TimestampWithTimeZone(_))
        case _ => None
      if dataType.isEmpty then errors += s"$label has SQL type '$sqlType'; unsupported"
      dataType.map(ColumnModel(id, physical(Identifier.toIdentifier(column.getName, column.isQuoted)), _, column.isNullable))

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
