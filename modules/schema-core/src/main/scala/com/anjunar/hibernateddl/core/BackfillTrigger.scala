package com.anjunar.hibernateddl.core

import java.io.{ByteArrayOutputStream, DataOutputStream}
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
enum BackfillTrigger:
  /** The column is added to an existing table as a required column, or a nullable column
    * becomes required.
    */
  case BecomesRequired
