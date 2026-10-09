package com.anjunar.hibernateddl.hibernate

import com.anjunar.hibernateddl.core.SchemaId
import org.hibernate.boot.Metadata
import org.hibernate.mapping.PersistentClass

/** Stable identities for a Hibernate entity mapped without a Java class.
  * `columnIds` is keyed by the final physical Hibernate column name, including case for quoted
  * identifiers. Providers are discovered with Hibernate's ClassLoaderService. Exactly one
  * provider must claim each classless entity; an unclaimed entity fails schema reading.
  */
final case class ClasslessEntitySchemaIds(tableId: SchemaId, columnIds: Map[String, SchemaId])

