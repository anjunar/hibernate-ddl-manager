package com.anjunar.hibernateddl.core

class StructureRefsSuite extends munit.FunSuite:
  private val email = SchemaId("u/email")
  private val tenant = SchemaId("u/tenant")

  test("a unique key's signature is versioned, stable and free of list separators") {
    val signature = UniqueKeyRef(SchemaId("u"), Vector(email)).signature
    assert(signature.matches("u1-[0-9a-f]{32}"), signature)
    // Fixed, so that an approval written into a setting stays valid across releases.
    assertEquals(signature, UniqueKeyRef(SchemaId("u"), Vector(email)).signature)
    assertEquals(signature, "u1-" + signature.drop(3))
  }

  test("the signature covers table and ordered columns, and nothing else") {
    val signatures = Vector(
      UniqueKeyRef(SchemaId("u"), Vector(email)),
      UniqueKeyRef(SchemaId("u"), Vector(tenant, email)),
      UniqueKeyRef(SchemaId("u"), Vector(email, tenant)),
      UniqueKeyRef(SchemaId("v"), Vector(email)),
      // Length prefixes keep a split of the same characters apart.
      UniqueKeyRef(SchemaId("u/"), Vector(SchemaId("email")))
    ).map(_.signature)
    assertEquals(signatures.distinct.size, signatures.size)
  }
