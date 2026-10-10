package com.anjunar.hibernateddl.executor

import com.anjunar.hibernateddl.core.*
import java.sql.Connection

/** An explicit permission for one change that is refused by default. An approval only permits:
  * a change that is not planned leaves it unused.
  */
enum Approval:
  /** Drop the table, column or sequence with this stable ID, and its data. */
  case Drop(id: SchemaId)

  /** Rename the table, column or sequence with this stable ID back to a name it had in an
    * earlier revision.
    */
  case RenameBack(id: SchemaId)

  /** Migrate to a target equal to the model of this earlier revision. */
  case Revert(revision: Long)

  /** Drop the unique key with this [[com.anjunar.hibernateddl.core.UniqueKeyRef.signature]] while its columns remain, so
    * that they may hold duplicates. Neither a drop nor a revert approval permits this.
    */
  case DropUniqueKey(signature: String)

object Approval:
  def dropUniqueKey(key: UniqueKeyRef): Approval = DropUniqueKey(key.signature)

  /** The entry for this approval in a comma-separated list, as settings and the CLI take it. */
  def entry(approval: Approval): String = approval match
    case Drop(id)                 => s"drop:${id.value}"
    case RenameBack(id)           => s"rename-back:${id.value}"
    case Revert(revision)         => s"revert:$revision"
    case DropUniqueKey(signature) => s"drop-unique:$signature"

  /** Reads one entry as [[entry]] writes it. */
  def parse(entry: String): Either[String, Approval] = entry.trim.split(":", 2) match
    case Array("drop", id) if id.trim.nonEmpty                                 => Right(Drop(SchemaId(id.trim)))
    case Array("rename-back", id) if id.trim.nonEmpty                          => Right(RenameBack(SchemaId(id.trim)))
    case Array("revert", revision) if revision.trim.toLongOption.exists(_ > 0) => Right(Revert(revision.trim.toLong))
    case Array("drop-unique", signature) if signature.trim.matches("u[0-9]+-[0-9a-f]+") =>
      Right(DropUniqueKey(signature.trim))
    case _ => Left(s"'$entry' is no approval; expected drop:<id>, rename-back:<id>, revert:<revision> or " +
        "drop-unique:<signature>")
