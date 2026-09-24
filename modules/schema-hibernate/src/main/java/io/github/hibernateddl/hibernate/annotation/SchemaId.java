package io.github.hibernateddl.hibernate.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Stable identity of a mapped entity or property, kept when the entity, property, table or
 * column is renamed.
 *
 * <p>The value consists of eight lowercase hex digits, for example {@code @SchemaId("f34e45b6")}.
 * Generate it once at random, never derive it from a name, and never change or reuse it.
 * Entity IDs must be unique across entities, property IDs within their entity.</p>
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.FIELD, ElementType.METHOD})
public @interface SchemaId {
    String value();
}
