package com.anjunar.hibernateddl.core

/** Time and timestamp precisions count fractional-second digits. Numeric precision counts
  * all digits and scale those after the decimal point. A large object is a reference to data
  * stored outside the row, as Hibernate maps `@Lob`. Json is a JSON document in the database's
  * binary JSON type, as Hibernate maps `@JdbcTypeCode(SqlTypes.JSON)`.
  */
enum SqlType:
  case Varchar(length: Int)
  case Char(length: Int)
  case Numeric(precision: Int, scale: Int)
  case Timestamp(precision: Int)
  case TimestampWithTimeZone(precision: Int)
  case Time(precision: Int)
  case Integer, BigInt, Boolean, Text, Uuid, SmallInt, Real, DoublePrecision, Date, Binary, LargeObject, Json
