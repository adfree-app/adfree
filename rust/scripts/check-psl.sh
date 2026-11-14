#!/usr/bin/env bash
# Tell whether the vendored Public Suffix List is older than the published one.
#
# The list is data, not code: it changes most days, and the copy in
# rust/assets/public_suffix_list.dat only moves when someone replaces it. This
# script does not run in any build — it is a question you ask when a host is
# treated as the wrong site:
#
#   ./rust/scripts/check-psl.sh            # same version, stale version, or no answer
#   ./rust/scripts/check-psl.sh --update   # take the published file when it moved
#
# Exit: 0 the copy is current, 1 it is stale, 2 the list could not be read.
# PSL_URL and PSL_FILE override where the two copies are read from, which is
# how the script's own branches are tested.
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
root="$(cd "$here/../.." && pwd)"
VENDORED="${PSL_FILE:-$root/rust/assets/public_suffix_list.dat}"
REMOTE="${PSL_URL:-https://publicsuffix.org/list/public_suffix_list.dat}"

if [[ ! -f "$VENDORED" ]]; then
    echo "no vendored list at $VENDORED" >&2
    exit 2
fi

fetched="$(mktemp)"
trap 'rm -f "$fetched"' EXIT

if ! curl -fsS --max-time 60 -o "$fetched" "$REMOTE"; then
    echo "could not read $REMOTE" >&2
    exit 2
fi

version_of() {
    # The list states its own version in a comment near the top.
    sed -n 's|^// VERSION: \(.*\)$|\1|p' "$1" | head -1
}

have="$(version_of "$VENDORED")"
want="$(version_of "$fetched")"

if [[ -z "$have" || -z "$want" ]]; then
    echo "a version line is missing: vendored='$have' published='$want'" >&2
    exit 2
fi

if [[ "$have" == "$want" ]]; then
    echo "current: $have"
    exit 0
fi

echo "stale: vendored $have, published $want"
if [[ "${1:-}" == "--update" ]]; then
    cp "$fetched" "$VENDORED"
    echo "updated $VENDORED to $want — rebuild to pick it up"
    exit 0
fi
echo "run again with --update to take it"
exit 1
