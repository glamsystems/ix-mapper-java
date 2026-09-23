#!/usr/bin/env bash
#
# Materializes the mapping configs the tests map through: a sparse checkout of
# glamsystems/ix-mapper-ts at the commit pinned below, into the untracked glam/ directory.
# The pin is what makes a test run a function of this repository's commit rather than of
# whatever ix-mapper-ts main holds when the build runs: that repository's main now carries
# the TypeScript mapper package, and the mapping-configs-v1 directories exist only in its
# history. ./syncMappings.sh moves the pin.
#
# Idempotent: a glam/ already at the pinned commit is left alone.

set -euo pipefail

readonly MAPPINGS_REPO="https://github.com/glamsystems/ix-mapper-ts.git"
readonly MAPPINGS_REF="e067fb4c01987e25bde5473ec368a62191a758e7"

cd "$(dirname "${BASH_SOURCE[0]}")"

if [[ -d glam/mapping-configs-v1 && "$(git -C glam rev-parse HEAD 2>/dev/null || true)" == "$MAPPINGS_REF" ]]; then
  exit 0
fi

rm -rf glam/
git clone -q -n --depth=1 --filter=tree:0 "$MAPPINGS_REPO" glam
# GitHub serves any reachable commit by full sha, so the pin needs no branch or tag.
git -C glam fetch -q --depth=1 origin "$MAPPINGS_REF"
git -C glam sparse-checkout set --no-cone /mapping-configs-v1 /mapping-configs-v1-staging
git -C glam checkout -q "$MAPPINGS_REF"

if [[ ! -d glam/mapping-configs-v1 || ! -d glam/mapping-configs-v1-staging ]]; then
  echo "downloadMappings: $MAPPINGS_REF carries no mapping-configs-v1 directories" >&2
  exit 1
fi
