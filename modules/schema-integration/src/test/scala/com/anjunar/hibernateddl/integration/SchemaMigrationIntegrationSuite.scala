package com.anjunar.hibernateddl.integration

import com.anjunar.hibernateddl.core.SchemaId
import com.anjunar.hibernateddl.executor.*
import com.anjunar.hibernateddl.hibernate.*
import com.anjunar.hibernateddl.postgresql.TestPostgres
import org.hibernate.SessionFactory
import org.hibernate.boot.{Metadata, MetadataSources}
import org.hibernate.boot.registry.StandardServiceRegistryBuilder
import java.sql.Connection
import javax.sql.DataSource

class SchemaMigrationIntegrationSuite extends TestPostgres:
  private def withMetadata[A](ds: DataSource, classes: Seq[Class[?]], settings: (String, String)*)(body: Metadata => A): A =
    val builder = new StandardServiceRegistryBuilder()
      .applySetting("hibernate.connection.datasource", ds)
      .applySetting("hibernate.default_schema", "public")
      .applySetting("hibernate.implicit_naming_strategy", "component-path")
    settings.foreach((key, value) => builder.applySetting(key, value))
    val registry = builder.build()
    try
      val sources = new MetadataSources(registry)
      classes.foreach(sources.addAnnotatedClass)
      body(sources.buildMetadata())
    finally StandardServiceRegistryBuilder.destroy(registry)

  private def withSessionFactory[A](ds: DataSource, classes: Seq[Class[?]], settings: (String, String)*)(
      body: SessionFactory => A
  ): A =
    withMetadata(ds, classes, settings*) { metadata =>
      val factory = metadata.buildSessionFactory()
      try body(factory)
      finally factory.close()
    }

  private def revisions(ds: DataSource): String =
    scalar(ds, "SELECT coalesce(string_agg(revision::text, ',' ORDER BY revision), '') FROM __hibernate_ddl.schema_history")

  /** A pool that hands out connections without auto-commit, as Hibernate's own pool does by default. */
  private def withoutAutoCommit(ds: DataSource): DataSource =
    java.lang.reflect.Proxy.newProxyInstance(classOf[DataSource].getClassLoader, Array(classOf[DataSource]),
      (_, method, args) =>
        val result = method.invoke(ds, Option(args).getOrElse(Array.empty[AnyRef])*)
        result match
          case connection: Connection =>
            connection.setAutoCommit(false)
            connection
          case other => other
    ).asInstanceOf[DataSource]

  private val enabled = MigrationSettings.Enabled -> "true"
  private val validate = "hibernate.hbm2ddl.auto" -> "validate"
  private val entities = Seq(classOf[Purchase], classOf[Generated], classOf[Letter])

  test("the integrator does nothing unless enabled") {
    withDatabase { ds =>
      withSessionFactory(ds, entities)(_ => ())
      assertEquals(scalar(ds, "SELECT to_regnamespace('__hibernate_ddl') IS NULL AND to_regclass('public.purchase') IS NULL"), "t")
    }
  }

  test("when enabled, the integrator migrates before Hibernate validates the schema, and the entities work") {
    withDatabase { ds =>
      withSessionFactory(withoutAutoCommit(ds), entities, enabled, validate) { factory =>
        factory.inTransaction { session =>
          val letter = new Letter
          letter.id = 1L
          letter.status = Status.Sent
          session.persist(letter)
          session.persist(new Generated)
        }
      }
      assertEquals(revisions(ds), "1")
      assertEquals(scalar(ds, "SELECT status FROM public.letter"), "Sent")
      withSessionFactory(ds, entities, enabled, validate)(_ => ())
      assertEquals(revisions(ds), "1")
    }
  }

  test("settings carry approvals: removing an entity drops its table and sequence only when approved") {
    withDatabase { ds =>
      withSessionFactory(ds, entities, enabled)(_ => ())
      val remaining = Seq(classOf[Purchase], classOf[Letter])
      val refused = intercept[MigrationException](withSessionFactory(ds, remaining, enabled)(_ => ()))
      assertEquals(refused.state, FailureState.RolledBack)
      assert(refused.getMessage.contains("Approval.Drop(\"cccccccc\")"), refused.getMessage)
      assert(refused.getMessage.contains("Approval.Drop(\"cccccccc/0a1b2c3d/sequence\")"), refused.getMessage)
      withSessionFactory(ds, remaining, enabled, validate,
        MigrationSettings.Approvals -> "drop:cccccccc, drop:cccccccc/0a1b2c3d/sequence")(_ => ())
      assertEquals(revisions(ds), "1,2")
      assertEquals(scalar(ds, "SELECT to_regclass('public.generated') IS NULL AND to_regclass('public.generated_seq') IS NULL"), "t")
    }
  }

  test("schema changes by Hibernate itself, invalid settings, other dialects and invalid mappings stop the startup") {
    withDatabase { ds =>
      def failure(classes: Seq[Class[?]], settings: (String, String)*): String =
        val error = intercept[MigrationException](withSessionFactory(ds, classes, (enabled +: settings)*)(_ => ()))
        assertEquals(error.state, FailureState.NotStarted)
        error.getMessage
      assert(failure(entities, "hibernate.hbm2ddl.auto" -> "update").contains("hibernate.hbm2ddl.auto is update"))
      assert(failure(entities, "jakarta.persistence.schema-generation.database.action" -> "create")
        .contains("jakarta.persistence.schema-generation.database.action is create"))
      assert(failure(entities, MigrationSettings.Approvals -> "remove:cccccccc").contains("the entry 'remove:cccccccc'"))
      assert(failure(entities, "hibernate.ddl_manager.adopt" -> "true").contains("Unknown setting hibernate.ddl_manager.adopt"))
      assert(failure(entities, "hibernate.dialect" -> "org.hibernate.dialect.H2Dialect",
        "hibernate.boot.allow_jdbc_metadata_access" -> "false").contains("PostgreSQL only"))
      assert(failure(Seq(classOf[Unidentified])).contains("Entity mapping: "))
      assertEquals(scalar(ds, "SELECT to_regclass('public.purchase') IS NULL"), "t")
    }
  }

  test("the explicit API migrates from the boot metadata before the SessionFactory exists") {
    // Without enums: Hibernate names their checks differently, which blocks adoption.
    val entities = Seq(classOf[Purchase], classOf[Generated])
    withDatabase { ds =>
      execute(ds, TestMetadata.createScript(entities*))
      withMetadata(ds, entities, validate) { metadata =>
        val refused = intercept[MigrationException](HibernateSchemaMigration.migrate(metadata, ds))
        assert(refused.getMessage.contains("enable adoptExistingSchema"), refused.getMessage)
      }
      val result = withMetadata(ds, entities, validate) { metadata =>
        val adopted = HibernateSchemaMigration.migrate(metadata, ds, ExecutionOptions(adoptExistingSchema = true))
        assertEquals(adopted, MigrationResult(1, MigrationStatus.Adopted, 0))
        metadata.buildSessionFactory().close()
        adopted
      }
      assertEquals(result.revision, 1L)
    }
  }

  test("settings parse into executor options") {
    assertEquals(MigrationSettings.options(Map(
      MigrationSettings.LockTimeoutMillis -> "100", MigrationSettings.StatementTimeoutMillis -> 200,
      MigrationSettings.AdoptExistingSchema -> "TRUE", MigrationSettings.AcceptManualMigration -> " abc ",
      MigrationSettings.Approvals -> "drop:a/b,rename-back:c, revert:3,"
    )), Right(ExecutionOptions(100, 200, adoptExistingSchema = true, acceptManualMigration = Some("abc"),
      approvals = Set(Approval.Drop(SchemaId("a/b")), Approval.RenameBack(SchemaId("c")), Approval.Revert(3)))))
    assertEquals(MigrationSettings.options(Map(MigrationSettings.LockTimeoutMillis -> "soon")).left.map(_.size), Left(1))
    assertEquals(MigrationSettings.options(Map(MigrationSettings.Approvals -> "revert:0")).left.map(_.size), Left(1))
    assertEquals(MigrationSettings.enabled(Map(MigrationSettings.Enabled -> "yes")).left.map(_.size), Left(1))
  }
