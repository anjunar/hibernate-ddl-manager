package com.anjunar.hibernateddl.core

/** How much work a supported type change may cause. Every such change keeps all values; this
  * only describes what the database may rebuild while it holds its locks.
  */
enum TableRewrite:
  /** The stored format stays the same, so the database may skip rewriting the table and its
    * indexes; it is not promised.
    */
  case Possible
  /** The stored format changes, so the table and its indexes are rewritten. */
  case Expected
