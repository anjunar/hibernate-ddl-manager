package com.anjunar.hibernateddl.hibernate

import com.anjunar.hibernateddl.core.SchemaId
import org.hibernate.boot.Metadata
import org.hibernate.mapping.PersistentClass
trait ClasslessEntitySchemaIdProvider:
  def identify(entity: PersistentClass, metadata: Metadata): Option[ClasslessEntitySchemaIds]
