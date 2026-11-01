#!/bin/sh
set -eu
script_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
work_dir=$(mktemp -d)
trap 'rm -rf "$work_dir"' EXIT HUP INT TERM
c++ -std=c++17 -I"$script_dir/mocks" "$script_dir/host.cpp" -o "$work_dir/firmware-test"
"$work_dir/firmware-test"
