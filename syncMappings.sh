#!/usr/bin/env bash
#
# Moves the mapping-config pin in downloadMappings.sh to the commit given as the first
# argument and re-materializes glam/. The argument is required: ix-mapper-ts's main no
# longer carries the mapping-configs-v1 directories, so there is no default to move to.
# The pin change is the reviewable diff.

set -euo pipefail

cd "$(dirname "${BASH_SOURCE[0]}")"

current="$(sed -n 's/^readonly MAPPINGS_REF="\(.*\)"$/\1/p' downloadMappings.sh)"
readonly current
if [[ -z "$current" ]]; then
  echo "syncMappings: could not read MAPPINGS_REF from downloadMappings.sh" >&2
  exit 1
fi

if [[ $# -ne 1 || ! "$1" =~ ^[0-9a-f]{40}$ ]]; then
  echo "usage: ./syncMappings.sh <full commit sha of glamsystems/ix-mapper-ts>" >&2
  exit 1
fi
readonly target="$1"

if [[ "$target" == "$current" ]]; then
  echo "syncMappings: already pinned to $current"
else
  sed -i.bak "s/^readonly MAPPINGS_REF=\"$current\"$/readonly MAPPINGS_REF=\"$target\"/" downloadMappings.sh
  rm -f downloadMappings.sh.bak
  echo "syncMappings: pin moved $current -> $target"
fi
rm -rf glam/
./downloadMappings.sh
