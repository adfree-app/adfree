#!/usr/bin/env bash
# Starts the desktop app from a snapshot of its classpath.
#
# The dev systemd unit runs the JVM against `desktop/build/classes`,
# `desktop/build/resources` and `shared/build/libs/shared.jar` — directories
# Gradle rewrites in place. A build or a test run while the app is up therefore
# replaces class files under a live JVM, and the next screen that loads one of
# the generated classes dies on the AWT thread:
#
#   Exception in thread "AWT-EventQueue-0" java.lang.NoClassDefFoundError:
#       git/shin/adfree/ui/screens/ProtectionScreenKt$ProtectionScreen$4$7$1
#
# The window then freezes with no dialog and nothing in the UI to click, while
# the tray keeps answering — it is a D-Bus thread of its own, and the process is
# otherwise healthy. Nothing is logged as a crash because nothing crashed: the
# Compose tree simply cannot be redrawn.
#
# This copies the three mutable entries into a per-login directory before the
# JVM starts, so a build can only affect the next start, and prints what it
# snapshotted, then execs the JVM it was given:
#
# Usage (the unit passes the real command):
#   run-desktop-dev.sh <java> <jvm args…> -cp <snapshot paths…> <main class>
set -euo pipefail

root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
run="${XDG_RUNTIME_DIR:-/tmp}/adfree-run"

if [ "$#" -lt 1 ]; then
    echo "run-desktop-dev.sh: expected the java command to run" >&2
    exit 2
fi

# One directory per start, replaced wholesale: a half-copied class is worse than
# no copy, and the classes are small (a few MB) next to the 1 GB tmpfs.
rm -rf "$run"
mkdir -p "$run"

copy() {
    local src=$1 dst=$2
    if [ ! -e "$src" ]; then
        echo "run-desktop-dev.sh: $src is missing — build first" >&2
        exit 1
    fi
    cp -a "$src" "$dst"
}

copy "$root/desktop/build/classes" "$run/classes"
copy "$root/desktop/build/resources" "$run/resources"
copy "$root/shared/build/libs/shared.jar" "$run/shared.jar"

echo "run-desktop-dev.sh: classpath snapshot in $run ($(du -sh "$run" | cut -f1))" >&2

# `exec` so the service's main PID is the JVM: systemd then signals the JVM, not
# a wrapper shell that would die first and take the JVM down uncleanly.
exec "$@"
