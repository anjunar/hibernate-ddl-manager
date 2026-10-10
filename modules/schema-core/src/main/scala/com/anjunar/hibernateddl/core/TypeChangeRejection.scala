package com.anjunar.hibernateddl.core

/** Why a type change is refused. */
enum TypeChangeRejection:
  /** A shorter VARCHAR, a smaller NUMERIC precision or BIGINT to INTEGER. */
  case Narrowing

  /** NUMERIC with another number of digits after the decimal point. */
  case ScaleChange

  /** Any other pair of types, which would need a conversion rule. */
  case OtherTypes
