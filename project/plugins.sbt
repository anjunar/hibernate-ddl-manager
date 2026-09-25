// Signs the artifacts for Maven Central with the local gpg key (publishSigned). sbt-pgp is on
// Maven Central as an sbt 2 build (`_sbt2_3`); sbt resolves that variant from its own version.
addSbtPlugin("com.github.sbt" % "sbt-pgp" % "2.3.2")
