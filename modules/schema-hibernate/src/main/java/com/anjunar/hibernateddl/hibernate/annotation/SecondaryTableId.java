package com.anjunar.hibernateddl.hibernate.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Stable identity of one of an entity's secondary tables, kept when the table is renamed.
 *
 * <p>{@code table} names the table as {@code @SecondaryTable(name)} does; {@code value} follows
 * the rules of {@link SchemaId}: eight lowercase hex digits, generated once at random and never
 * changed or reused. When renaming the secondary table, change {@code table} along with
 * {@code @SecondaryTable(name)} and keep {@code value}.</p>
 *
 * <pre>{@code
 * @Entity
 * @SchemaId("7f3a9c21")
 * @SecondaryTable(name = "customer_details")
 * @SecondaryTableId(table = "customer_details", value = "5d6e7f80")
 * class Customer { ... }
 * }</pre>
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
@Repeatable(SecondaryTableIds.class)
public @interface SecondaryTableId {
    String table();

    String value();
}
