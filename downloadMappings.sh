#!/usr/bin/env bash

set -e

rm -rf glam/
git clone -n --depth=1 --filter=tree:0 https://github.com/glamsystems/ix-mapper-ts.git glam
cd glam
git sparse-checkout set --no-cone /mapping-configs-v0 /mapping-configs-v1
git checkout

cd ..

exit 0
