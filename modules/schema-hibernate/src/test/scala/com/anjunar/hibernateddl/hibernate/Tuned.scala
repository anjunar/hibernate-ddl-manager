package com.anjunar.hibernateddl.hibernate

import com.anjunar.hibernateddl.hibernate.annotation.{SchemaId, SecondaryTableId}
import jakarta.persistence.*
import org.hibernate.annotations.{JdbcTypeCode, NaturalId, OnDelete, OnDeleteAction}
import org.hibernate.`type`.SqlTypes

import scala.compiletime.uninitialized
import java.lang.{Long as JavaLong}

/** DDL options, which Hibernate appends verbatim, on a column, the table, a unique key, a
  * foreign key and a sequence.
  */
@Entity
@SchemaId("9a0b1c2d")
@Table(
  options = "WITH (fillfactor = 70)",
  uniqueConstraints = Array(new UniqueConstraint(columnNames = Array("code"), options = "DEFERRABLE"))
)
class Tuned:
  @Id @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "tuned_gen")
  @SequenceGenerator(name = "tuned_gen", sequenceName = "tuned_seq", options = "CACHE 20")
  @SchemaId("0a1b2c3d") var id: JavaLong = uninitialized
  @SchemaId("1b2c3d4e") @Column(options = "CHECK (score > 0)") var score: Integer = uninitialized
  @SchemaId("2c3d4e5f") var code: String = uninitialized
  @SchemaId("3d4e5f60") @ManyToOne @JoinColumn(foreignKey = new ForeignKey(options = "DEFERRABLE"))
  var parent: Tuned = uninitialized
