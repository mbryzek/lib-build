#!/usr/bin/env bash
#
# lib-build's build. READ devops/templates/ci/build.sh AND devops/docs/ci.md FIRST: landing this
# file IS the enrolment, `set -euo pipefail` is not style, and the fleet posts the `ci` context.
#
# WHAT A GREEN HERE VOUCHES FOR: both artifacts compile, `lib-build-sbt` against the oldest sbt a
# consumer runs and `lib-build-testkit` under the fleet's Scala 3 compiler gate; the test-health
# rules and the AutoPlugin descriptor hold (`sbt/src/test`); and every pin suite passes against the
# pins this build resolves (`testkit/src/test`). This build's own meta-build compiles `sbt/`'s
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

# ci/test-health.sh is the shell half of test health, a copy of devops
# templates/sbt-test-health/ci/test-health.sh held to it here, as in every sbt repository, until
# it runs through `dev ci step` (ISS-53964). A read that fails is not a drift: retried once, and
# one that fails twice exits 75.
template_bytes=$(mktemp "${TMPDIR:-/tmp}/ci-template.XXXXXX")
read_rc=0
dev repo cat devops templates/sbt-test-health/ci/test-health.sh >"$template_bytes" ||
  dev repo cat devops templates/sbt-test-health/ci/test-health.sh >"$template_bytes" || read_rc=$?
if [ "$read_rc" -ne 0 ]; then
  echo "ci/build.fast.sh: could not read devops templates/sbt-test-health/ci/test-health.sh (dev repo cat exit $read_rc, twice) -- a read failure, not a drift; nothing was measured" >&2
  rm -f "$template_bytes"
  exit 75
fi
if ! diff -u "$template_bytes" ci/test-health.sh; then
  echo "ci/build.fast.sh: sync it -- dev repo cat devops templates/sbt-test-health/ci/test-health.sh > ci/test-health.sh" >&2
  rm -f "$template_bytes"
  exit 1
fi
rm -f "$template_bytes"

dev ci step format

source ci/test-health.sh
ci_test_health_sbt
sbt -Dsbt.log.noformat=true "${ci_sbt_test_commands[@]}"
