package com.anjunar.hibernateddl.postgresql

import com.anjunar.hibernateddl.core.*
import com.anjunar.hibernateddl.executor.*
import java.util.concurrent.{Callable, CountDownLatch, Executors, TimeUnit}
import javax.sql.DataSource
import scala.util.Using

/** Dropping and replacing indexes and unique keys against real PostgreSQL, bound by definition
  * to the objects the catalog shows, whatever PostgreSQL, Hibernate or an operator named them.
  */
class PostgreSqlIndexChangeSuite extends TestPostgres:
  private val executor = new JdbcMigrationExecutor(PostgreSqlMigrationBackend)
  private val public = Some(SqlIdentifier("public"))

  private def column(id: String, name: String, dataType: SqlType = SqlType.Varchar(100), nullable: Boolean = true) =
    ColumnModel(SchemaId(id), SqlIdentifier(name), dataType, nullable)
  private val id = column("P_ID", "id", SqlType.BigInt, nullable = false)
  private val tenant = column("P_TENANT", "tenant_id", SqlType.BigInt, nullable = false)
  private val email = column("P_EMAIL", "email")
  private val last = column("P_LAST", "last_name")
  private val first = column("P_FIRST", "first_name")
  private def people(
      unique: Vector[Vector[ColumnModel]] = Vector.empty,
      indexes: Vector[Vector[(ColumnModel, Boolean)]] = Vector.empty,
      columns: Vector[ColumnModel] = Vector(tenant, email, last, first)
  ) = SchemaModel(Vector(TableModel(SchemaId("P"), QualifiedName(SqlIdentifier("people"), public), id +: columns,
    Vector(id.id), uniqueKeys = unique.map(key => UniqueKeyModel(key.map(_.id))),
    indexes = indexes.map(index => IndexModel(index.map((c, descending) => IndexColumn(c.id, descending)))))))
  private def approve(columns: ColumnModel*): Approval =
    Approval.dropUniqueKey(UniqueKeyRef(SchemaId("P"), columns.toVector.map(_.id)))
  private def approving(approvals: Approval*) =
    new JdbcMigrationExecutor(PostgreSqlMigrationBackend, ExecutionOptions(approvals = approvals.toSet))

  private def revisions(ds: DataSource): String = scalar(ds, "SELECT count(*) FROM __hibernate_ddl.schema_history")
  private def statements(ds: DataSource, revision: Int): String =
    scalar(ds, s"SELECT array_to_string(statements, ' | ') FROM __hibernate_ddl.schema_history WHERE revision = $revision")
  /** Every index of the table by name, with its columns. */
  private def indexes(ds: DataSource, table: String = "people", schema: String = "public"): String =
    scalar(ds, "SELECT string_agg(indexname || ' ' || regexp_replace(indexdef, '^.* USING btree ', ''), '; ' ORDER BY indexname) " +
      s"FROM pg_indexes WHERE schemaname = '$schema' AND tablename = '$table'")

  test("a plain index is dropped by the name PostgreSQL gave it; a replacement is created first and never confused with it") {
    withDatabase { ds =>
      executor.migrate(ds, people(indexes = Vector(Vector(last -> false))))
      execute(ds, "INSERT INTO public.people VALUES (1, 1, 'a@x', 'Lovelace', 'Ada')")
      assertEquals(indexes(ds), "people_last_name_idx (last_name); people_pkey (id)")
      Vector(
        Vector(last -> false, first -> false) -> "people_last_name_first_name_idx (last_name, first_name)",
        Vector(first -> false, last -> false) -> "people_first_name_last_name_idx (first_name, last_name)",
        // The replacement takes the next free name, so the drop cannot hit it.
        Vector(first -> false, last -> true) -> "people_first_name_last_name_idx1 (first_name, last_name DESC)"
      ).zipWithIndex.foreach { case ((index, shown), step) =>
        val target = people(indexes = Vector(index))
        assertEquals(executor.migrate(ds, target).status, MigrationStatus.Applied)
        assertEquals(indexes(ds), s"$shown; people_pkey (id)")
        assertEquals(executor.migrate(ds, target).status, MigrationStatus.AlreadyApplied)
      }
      assert(statements(ds, 4).endsWith(" | DROP INDEX \"public\".\"people_first_name_last_name_idx\";"), statements(ds, 4))
      assertEquals(executor.migrate(ds, people()), MigrationResult(5, MigrationStatus.Applied, 1))
      assertEquals(statements(ds, 5), "DROP INDEX \"public\".\"people_first_name_last_name_idx1\";")
      assertEquals(indexes(ds), "people_pkey (id)")
      assertEquals(scalar(ds, "SELECT concat_ws('|', id, tenant_id, email, last_name, first_name) FROM public.people"),
        "1|1|a@x|Lovelace|Ada")
    }
  }

  test("a unique key is dropped only with its own approval, refused before any DDL otherwise; duplicates fit afterwards") {
    withDatabase { ds =>
      executor.migrate(ds, people(unique = Vector(Vector(email))))
      execute(ds, "INSERT INTO public.people VALUES (1, 1, 'a@x', 'Lovelace', 'Ada')")
      val refused = intercept[MigrationException](executor.migrate(ds, people()))
      assertEquals(refused.state, FailureState.RolledBack)
      assert(refused.getMessage.contains(s"setting entry ${Approval.entry(approve(email))}"), refused.getMessage)
      val other = intercept[MigrationException](approving(approve(last), Approval.Drop(email.id)).migrate(ds, people()))
      assert(other.getMessage.contains("lets its columns hold duplicates"), other.getMessage)
      assertEquals(revisions(ds), "1")
      intercept[java.sql.SQLException](execute(ds, "INSERT INTO public.people VALUES (2, 1, 'a@x', NULL, NULL)"))
      assertEquals(approving(approve(email)).migrate(ds, people()), MigrationResult(2, MigrationStatus.Applied, 1))
      assertEquals(statements(ds, 2), "ALTER TABLE \"public\".\"people\" DROP CONSTRAINT \"people_email_key\";")
      execute(ds, "INSERT INTO public.people VALUES (2, 1, 'a@x', NULL, NULL)")
      assertEquals(scalar(ds, "SELECT count(*) FROM public.people WHERE email = 'a@x'"), "2")
      assertEquals(executor.migrate(ds, people()).status, MigrationStatus.AlreadyApplied)
    }
  }

  test("UNIQUE(email) becomes UNIQUE(tenant_id, email); the reverse fails on the duplicates and keeps the new rule") {
    withDatabase { ds =>
      executor.migrate(ds, people(unique = Vector(Vector(email))))
      execute(ds, "INSERT INTO public.people VALUES (1, 1, 'a@x', NULL, NULL), (2, 1, NULL, NULL, NULL), (3, 2, NULL, NULL, NULL)")
      val perTenant = people(unique = Vector(Vector(tenant, email)))
      assertEquals(approving(approve(email)).migrate(ds, perTenant).status, MigrationStatus.Applied)
      assertEquals(statements(ds, 2), "ALTER TABLE \"public\".\"people\" ADD UNIQUE (\"tenant_id\", \"email\"); | " +
        "ALTER TABLE \"public\".\"people\" DROP CONSTRAINT \"people_email_key\";")
      execute(ds, "INSERT INTO public.people VALUES (4, 2, 'a@x', NULL, NULL), (5, 1, NULL, NULL, NULL)")
      intercept[java.sql.SQLException](execute(ds, "INSERT INTO public.people VALUES (6, 1, 'a@x', NULL, NULL)"))
      val back = approving(Approval.Revert(1), approve(tenant, email))
      val failed = intercept[MigrationException](back.migrate(ds, people(unique = Vector(Vector(email)))))
      assertEquals(failed.state, FailureState.RolledBack)
      assertEquals(revisions(ds), "2")
      intercept[java.sql.SQLException](execute(ds, "INSERT INTO public.people VALUES (7, 2, 'a@x', NULL, NULL)"))
      assertEquals(scalar(ds, "SELECT count(*) FROM public.people"), "5")
    }
  }

  test("switching between a unique key and a plain index never drops a constraint's index as a plain one") {
    withDatabase { ds =>
      executor.migrate(ds, people(unique = Vector(Vector(email))))
      assertEquals(approving(approve(email)).migrate(ds, people(indexes = Vector(Vector(email -> false)))).status,
        MigrationStatus.Applied)
      assertEquals(indexes(ds), "people_email_idx (email); people_pkey (id)")
      // Back to the model of revision 1, which the revert guard lets through only with its approval.
      assertEquals(approving(Approval.Revert(1)).migrate(ds, people(unique = Vector(Vector(email)))).status, MigrationStatus.Applied)
      assertEquals(indexes(ds), "people_email_key (email); people_pkey (id)")
      assertEquals(scalar(ds, "SELECT count(*) FROM pg_constraint WHERE conrelid = 'public.people'::regclass AND contype = 'u'"), "1")
      assertEquals(executor.migrate(ds, people(unique = Vector(Vector(email)))).status, MigrationStatus.AlreadyApplied)
    }
  }

  test("names given by an operator are bound, also after a rename in the same step; the same definition elsewhere stays") {
    val code = column("I_CODE", "code")
    val sku = column("I_SKU", "sku")
    def items(tableId: String, schema: String, name: String, keyed: Boolean) = TableModel(SchemaId(tableId),
      QualifiedName(SqlIdentifier(name), Some(SqlIdentifier(schema))),
      Vector(id.copy(id = SchemaId(s"$tableId-id")), code.copy(id = SchemaId(s"$tableId-code")), sku.copy(id = SchemaId(s"$tableId-sku"))),
      Vector(SchemaId(s"$tableId-id")),
      uniqueKeys = if keyed then Vector(UniqueKeyModel(Vector(SchemaId(s"$tableId-sku")))) else Vector.empty,
      indexes = if keyed then Vector(IndexModel(Vector(IndexColumn(SchemaId(s"$tableId-code"))))) else Vector.empty)
    val before = SchemaModel(Vector(items("A", "public", "items", keyed = true), items("B", "Other Schema", "items", keyed = true)))
    val after = SchemaModel(Vector(items("A", "public", "Item \"List\"", keyed = false), before.tables(1)))
    withDatabase { ds =>
      Vector("public", "\"Other Schema\"").foreach { schema =>
        if schema != "public" then execute(ds, s"CREATE SCHEMA $schema")
        execute(ds, s"CREATE TABLE $schema.items (id bigint PRIMARY KEY, code varchar(100), sku varchar(100)); " +
          s"CREATE INDEX \"IDX Code\" ON $schema.items (code); " +
          s"ALTER TABLE $schema.items ADD CONSTRAINT \"UK \"\"Sku\"\"\" UNIQUE (sku)")
      }
      val adopting = new JdbcMigrationExecutor(PostgreSqlMigrationBackend, ExecutionOptions(adoptExistingSchema = true))
      assertEquals(adopting.migrate(ds, before).status, MigrationStatus.Adopted)
      val approval = Approval.dropUniqueKey(UniqueKeyRef(SchemaId("A"), Vector(SchemaId("A-sku"))))
      assertEquals(approving(approval).migrate(ds, after), MigrationResult(2, MigrationStatus.Applied, 3))
      assertEquals(statements(ds, 2), "ALTER TABLE \"public\".\"items\" RENAME TO \"Item \"\"List\"\"\"; | " +
        "ALTER TABLE \"public\".\"Item \"\"List\"\"\" DROP CONSTRAINT \"UK \"\"Sku\"\"\"; | DROP INDEX \"public\".\"IDX Code\";")
      assertEquals(indexes(ds, "Item \"List\""), "items_pkey (id)")
      assertEquals(indexes(ds, "items", "Other Schema"),
        "IDX Code (code); UK \"Sku\" (sku); items_pkey (id)")
      assertEquals(executor.migrate(ds, after).status, MigrationStatus.AlreadyApplied)
    }
  }

  test("binding refuses missing and ambiguous matches, key and constraint indexes, and drops a foreign key depends on") {
    withDatabase { ds =>
      execute(ds, "CREATE TABLE public.parent (id bigint PRIMARY KEY, code varchar(10) CONSTRAINT parent_code_key UNIQUE, name text, note text); " +
        "CREATE TABLE public.child (id bigint PRIMARY KEY, parent_code varchar(10) REFERENCES public.parent (code)); " +
        "CREATE INDEX twice_a ON public.parent (name); CREATE INDEX twice_b ON public.parent (name); " +
        "CREATE INDEX partial_note ON public.parent (note) WHERE note IS NOT NULL")
      val parent = QualifiedName(SqlIdentifier("parent"), public)
      def index(column: String, descending: Boolean = false, table: QualifiedName = parent) =
        SchemaOperation.DropIndex(IndexRef(SchemaId("T"), Vector(IndexColumn(SchemaId(column), descending))), table,
          Vector(SchemaOperation.IndexedColumn(SqlIdentifier(column), descending)))
      Using.resource(ds.getConnection) { connection =>
        def bind(operation: SchemaOperation, column: String, table: QualifiedName = parent) =
          PostgreSqlMigrationBackend.bindDrop(connection, operation, table, Vector(SqlIdentifier(column)))
            .swap.getOrElse(fail(s"$operation was bound")).mkString
        val unique = SchemaOperation.DropUniqueKey(UniqueKeyRef(SchemaId("T"), Vector(SchemaId("code"))), parent,
          Vector(SqlIdentifier("code")))
        assert(bind(unique, "code").contains("cannot be dropped while foreign key \"child_parent_code_fkey\" of " +
          "\"public\".\"child\" depends on it; the migration never uses CASCADE"))
        assert(bind(index("name"), "name").contains("matches 2 objects (\"twice_a\", \"twice_b\"); refusing to choose one"))
        assert(bind(index("name", descending = true), "name").contains("which the target drops, does not exist"))
        assert(bind(index("id"), "id").contains("does not exist"))
        assert(bind(index("code"), "code").contains("does not exist"))
        assert(bind(index("note"), "note").contains("Index \"partial_note\" of \"public\".\"parent\" has unsupported " +
          "partial predicate; it is not dropped"))
        val missing = QualifiedName(SqlIdentifier("missing"), public)
        assert(bind(index("name", table = missing), "name", missing).contains("table \"public\".\"missing\" does not exist"))
      }
      assertEquals(indexes(ds, "parent"),
        "parent_code_key (code); parent_pkey (id); partial_note (note) WHERE (note IS NOT NULL); twice_a (name); twice_b (name)")
    }
  }

  test("a failure after the drops restores the dropped unique key and index, the data and the history") {
    withDatabase { ds =>
      executor.migrate(ds, people(unique = Vector(Vector(email)), indexes = Vector(Vector(last -> false))))
      execute(ds, "INSERT INTO public.people VALUES (1, 1, 'a@x', 'Lovelace', NULL)")
      val required = people(columns = Vector(tenant, email, last, first.copy(nullable = false)))
      val failed = intercept[MigrationException](approving(approve(email)).migrate(ds, required))
      assertEquals(failed.state, FailureState.RolledBack)
      assert(failed.getMessage.contains("becomes required"), failed.getMessage)
      assertEquals(indexes(ds), "people_email_key (email); people_last_name_idx (last_name); people_pkey (id)")
      assertEquals(revisions(ds), "1")
      assertEquals(executor.migrate(ds, people(unique = Vector(Vector(email)), indexes = Vector(Vector(last -> false)))).status,
        MigrationStatus.AlreadyApplied)
    }
  }

  test("two concurrent server starts replace an index once") {
    withDatabase { ds =>
      executor.migrate(ds, people(indexes = Vector(Vector(last -> false))))
      val target = people(indexes = Vector(Vector(last -> false, first -> false)))
      val pool = Executors.newFixedThreadPool(2)
      val start = new CountDownLatch(1)
      try
        val runs = Vector.fill(2)(pool.submit(new Callable[MigrationResult]:
          def call(): MigrationResult =
            start.await()
            executor.migrate(ds, target)
        ))
        start.countDown()
        assertEquals(runs.map(_.get(30, TimeUnit.SECONDS).status).toSet, Set(MigrationStatus.Applied, MigrationStatus.AlreadyApplied))
        assertEquals(indexes(ds), "people_last_name_first_name_idx (last_name, first_name); people_pkey (id)")
      finally
        pool.shutdownNow()
        pool.awaitTermination(5, TimeUnit.SECONDS)
    }
  }

  test("widening, a new unique key over a filled column, dropping the old key and an index run in one transaction") {
    val region = column("P_REGION", "region", SqlType.Varchar(10))
    withDatabase { ds =>
      executor.migrate(ds, people(unique = Vector(Vector(email)), indexes = Vector(Vector(last -> false)),
        columns = Vector(tenant, email, last, first, region)))
      execute(ds, "INSERT INTO public.people VALUES (1, 1, 'a@x', 'Lovelace', 'Ada', NULL), (2, 1, 'b@x', 'Hopper', 'Grace', 'us')")
      val target = people(unique = Vector(Vector(region, email)),
        columns = Vector(tenant, email.copy(dataType = SqlType.Varchar(200)), last.copy(dataType = SqlType.Varchar(150)), first,
          region.copy(nullable = false)))
      val backfill = Backfill.fillNulls("region-v1", region.id, BackfillTrigger.BecomesRequired, BackfillValue.literal("eu"))
      val result = approving(approve(email)).migrate(ds, target, Vector(backfill))
      assertEquals(result.status, MigrationStatus.Applied)
      assertEquals(indexes(ds), "people_pkey (id); people_region_email_key (region, email)")
      assertEquals(scalar(ds, "SELECT string_agg(region || ':' || email, ',' ORDER BY id) FROM public.people"), "eu:a@x,us:b@x")
      assertEquals(executor.migrate(ds, target, Vector(backfill)).status, MigrationStatus.AlreadyApplied)
    }
  }

  test("a Hibernate mapping that renames, redirects and drops uniqueness binds the names Hibernate's own DDL gave") {
    import com.anjunar.hibernateddl.hibernate.*
    def model(entity: Class[?]) = TestMetadata.read(entity).fold(errors => fail(errors.mkString("\n")), identity)
    withDatabase { ds =>
      execute(ds, TestMetadata.createScript(classOf[CatalogItemRenamed]))
      val adopting = new JdbcMigrationExecutor(PostgreSqlMigrationBackend, ExecutionOptions(adoptExistingSchema = true))
      assertEquals(adopting.migrate(ds, model(classOf[CatalogItem])).status, MigrationStatus.Adopted)
      val sku = UniqueKeyRef(SchemaId("b8c9d0e1"), Vector(SchemaId("b8c9d0e1/2c3d4e5f")))
      val switched = model(classOf[CatalogItemSwitched])
      assertEquals(approving(Approval.dropUniqueKey(sku)).migrate(ds, switched).status, MigrationStatus.Applied)
      assertEquals(statements(ds, 2), "ALTER TABLE \"public\".\"catalog_item\" ADD UNIQUE (\"code\"); | " +
        "ALTER TABLE \"public\".\"catalog_item\" DROP CONSTRAINT \"uk_sku_renamed\"; | DROP INDEX \"public\".\"idx_code_renamed\";")
      execute(ds, "INSERT INTO public.catalog_item (id, code, sku) VALUES (1, 'a', 's'), (2, 'b', 's')")
      intercept[java.sql.SQLException](execute(ds, "INSERT INTO public.catalog_item (id, code, sku) VALUES (3, 'a', 't')"))
    }
  }
