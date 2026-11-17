#!/usr/bin/env bash
#
# scala-build's build. READ devops/templates/ci/build.sh AND devops/docs/ci.md FIRST: landing this
# file IS the enrolment, `set -euo pipefail` is not style, and the fleet posts the `ci` context.
#
# WHAT A GREEN HERE VOUCHES FOR: all three artifacts compile, `scala-build-sbt` against the oldest
# sbt a consumer runs, `scala-build-app` against the sbt and Play sbt-plugin the applications run,
# and `scala-build-testkit` under the fleet's Scala 3 compiler gate; the test-health rules and both
# AutoPlugin descriptors hold (`sbt/src/test`, `app/src/test`); and every pin suite, the fork-safe
# framework and agent, and the app module's sbt-free rules pass (`testkit/src/test`). This build's own meta-build compiles `sbt/`'s
# sources (project/build.sbt), so the suite below runs through the very test-health plugins it
# publishes.
#
# WHAT IT DOES NOT VOUCH FOR: that any consumer still builds. A consumer resolves a RELEASED
# version, so a change here reaches it only through that consumer's own version-bump pull
# request, which its own `ci` proves.
#
# No database block: nothing here opens a connection.
#
# TEST HEALTH READS THE NEXT TWO LINES (ISS-19887, design DES-18577 "Every repository with CI").
#
# ci-test-reports: */target/test-reports/*.xml
# ci-test-lists: required
set -euo pipefail

echo "building ${CI_REPO:-?} @ ${CI_SHA:-?} (${CI_EVENT:-?}, clean=${CI_CLEAN_BUILD:-?})"

dev ci step format

# The shell half of test health is devops templates/sbt-test-health/ci/test-health.sh, sourced
# from the devops release and never copied. A release that cannot hand it over exits 75: the box,
# not the branch.
ci_th=$(dev ci template sbt-test-health/ci/test-health.sh) || exit 75
source "$ci_th"
ci_test_health_sbt
sbt -Dsbt.log.noformat=true "${ci_sbt_test_commands[@]}"
