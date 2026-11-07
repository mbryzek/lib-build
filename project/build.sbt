// lib-build's own build runs on the sources it publishes: the meta-build compiles `sbt/`'s main
// sources directly, so `BryzekPins`, `BryzekLibSettings` and the test-health plugins this build
// uses are the ones on this branch, never a released version of themselves.
Compile / unmanagedSourceDirectories += baseDirectory.value.getParentFile / "sbt" / "src" / "main" / "scala"
