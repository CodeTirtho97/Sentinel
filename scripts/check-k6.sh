#!/usr/bin/env bash
set -euo pipefail

# Parses every k6 script with k6 itself and fails if any of them will not compile.
#
# This exists because `node --check` is not a valid substitute. k6 0.52 runs on goja and compiles
# through Babel, which supports noticeably less than modern Node: ES2020 nullish coalescing (`??`)
# and optional chaining (`?.`) are both syntax errors there and both parse cleanly under Node. A
# script that passes `node --check` can still fail at the moment k6 loads it — twenty minutes into
# a run, after the stack has been built and seeded.
#
# Run this after touching anything in loadtest/k6/.
#
#   ./scripts/check-k6.sh

# Git Bash rewrites the container-side path in -v unless this is set; the mount silently ends up
# empty and k6 reports a missing script rather than a bad path.
export MSYS_NO_PATHCONV=1

K6_IMAGE="${K6_IMAGE:-grafana/k6:0.52.0}"
SCRIPT_DIR="$(cd "$(dirname "$0")/.." && pwd)/loadtest/k6"

pass=0
fail=0

echo "Parsing k6 scripts with ${K6_IMAGE}..."
for path in "${SCRIPT_DIR}"/*.js; do
  name="$(basename "${path}")"
  printf '  %-30s' "${name}"
  if output=$(docker run --rm -v "${SCRIPT_DIR}:/scripts:ro" "${K6_IMAGE}" \
      inspect "/scripts/${name}" 2>&1); then
    echo "ok"
    pass=$((pass + 1))
  else
    echo "FAIL"
    # The stack trace is all Babel internals; the first line carries the actual position.
    echo "${output}" | sed 's/\\n/\n/g' | grep -m1 -E 'SyntaxError|error' | sed 's/^/      /'
    fail=$((fail + 1))
  fi
done

echo ""
echo "  ${pass} passed, ${fail} failed"
[[ "${fail}" -eq 0 ]]
