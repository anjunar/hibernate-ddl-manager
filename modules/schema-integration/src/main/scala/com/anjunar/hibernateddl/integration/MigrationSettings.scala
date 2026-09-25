package com.anjunar.hibernateddl.integration

import com.anjunar.hibernateddl.core.SchemaId
import com.anjunar.hibernateddl.executor.{Approval, ExecutionOptions}

/** Hibernate settings for the migration at server startup. Nothing happens unless `enabled` is
  * true. `approvals` lists entries such as `drop:7f3a9c21/f34e45b6`,
  * `rename-back:7f3a9c21` or `revert:3`, separated by commas.
  */
object MigrationSettings:
  val Enabled = "hibernate.ddl_manager.enabled"
  val AdoptExistingSchema = "hibernate.ddl_manager.adopt_existing_schema"
  val Approvals = "hibernate.ddl_manager.approvals"
  val AcceptManualMigration = "hibernate.ddl_manager.accept_manual_migration"
  val LockTimeoutMillis = "hibernate.ddl_manager.lock_timeout_millis"
  val StatementTimeoutMillis = "hibernate.ddl_manager.statement_timeout_millis"

  private val Known = Set(Enabled, AdoptExistingSchema, Approvals, AcceptManualMigration, LockTimeoutMillis,
    StatementTimeoutMillis)

  def enabled(settings: collection.Map[String, Any]): Either[Vector[String], Boolean] =
    val errors = Vector.newBuilder[String]
    val value = boolean(settings, Enabled, errors)
    val messages = errors.result()
    if messages.nonEmpty then Left(messages) else Right(value)

  /** The executor options the settings describe, starting from `defaults`; unknown
    * `hibernate.ddl_manager.*` settings are errors, so a typo cannot disable a safeguard.
    */
  def options(
      settings: collection.Map[String, Any],
      defaults: ExecutionOptions = ExecutionOptions()
  ): Either[Vector[String], ExecutionOptions] =
    val errors = Vector.newBuilder[String]
    settings.keys.filter(key => key.startsWith("hibernate.ddl_manager.") && !Known.contains(key)).toVector.sorted
      .foreach(key => errors += s"Unknown setting $key")
    val options = ExecutionOptions(
      lockTimeoutMillis = int(settings, LockTimeoutMillis, errors).getOrElse(defaults.lockTimeoutMillis),
      statementTimeoutMillis = int(settings, StatementTimeoutMillis, errors).getOrElse(defaults.statementTimeoutMillis),
      allowedRisks = defaults.allowedRisks,
      adoptExistingSchema =
        if settings.contains(AdoptExistingSchema) then boolean(settings, AdoptExistingSchema, errors)
        else defaults.adoptExistingSchema,
      approvals = defaults.approvals ++ approvals(settings, errors),
      acceptManualMigration = text(settings, AcceptManualMigration).orElse(defaults.acceptManualMigration)
    )
    val messages = errors.result()
    if messages.nonEmpty then Left(messages) else Right(options)

  private def text(settings: collection.Map[String, Any], key: String): Option[String] =
    settings.get(key).map(value => String.valueOf(value).trim).filter(_.nonEmpty)

  private def boolean(settings: collection.Map[String, Any], key: String, errors: collection.mutable.Growable[String]): Boolean =
    text(settings, key).map(_.toLowerCase(java.util.Locale.ROOT)) match
      case None | Some("false") => false
      case Some("true") => true
      case Some(other) =>
        errors += s"Setting $key must be true or false, not '$other'"
        false

  private def int(settings: collection.Map[String, Any], key: String, errors: collection.mutable.Growable[String]): Option[Int] =
    text(settings, key).flatMap { value =>
      val parsed = value.toIntOption
      if parsed.isEmpty then errors += s"Setting $key must be a whole number of milliseconds, not '$value'"
      parsed
    }

  private def approvals(settings: collection.Map[String, Any], errors: collection.mutable.Growable[String]): Set[Approval] =
    text(settings, Approvals).toVector.flatMap(_.split(',').toVector.map(_.trim).filter(_.nonEmpty)).flatMap { entry =>
      entry.split(":", 2) match
        case Array("drop", id) if id.trim.nonEmpty => Some(Approval.Drop(SchemaId(id.trim)))
        case Array("rename-back", id) if id.trim.nonEmpty => Some(Approval.RenameBack(SchemaId(id.trim)))
        case Array("revert", revision) if revision.trim.toLongOption.exists(_ > 0) =>
          Some(Approval.Revert(revision.trim.toLong))
        case _ =>
          errors += s"Setting $Approvals has the entry '$entry'; expected drop:<id>, rename-back:<id> or revert:<revision>"
          None
    }.toSet
