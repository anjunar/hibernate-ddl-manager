// The same cause as `exportJars := false` in the root build, one level up: after a change to
// build.sbt or a file in project/, sbt repackages the meta-build JAR and fails on Windows to
// rename it over the still open file. The root's setting does not reach the meta-build, which
// is a build of its own.
exportJars := false
