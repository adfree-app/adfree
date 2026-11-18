#!/usr/bin/env bash
# Install AdFree on Linux from the latest release.
#
#   curl -fsSL https://raw.githubusercontent.com/yuuki-kagura/adfree/main/scripts/install-linux.sh | bash
#
# Picks the package your distribution can actually install — deb for Debian and
# Ubuntu, rpm for Fedora and openSUSE, pkg.tar.zst for Arch, CachyOS, EndeavourOS
# and Manjaro — downloads it from the release, and hands it to that
# distribution's own package manager. The package installs to /opt/adfree and
# ships its own desktop entry.
#
# Set ADFREE_VERSION=1.14.1 to pin a release instead of taking the latest.

set -euo pipefail

REPO="yuuki-kagura/adfree"
VERSION="${ADFREE_VERSION:-}"

say() { printf '%s\n' "$*"; }
die() { printf 'adfree: %s\n' "$*" >&2; exit 1; }

command -v curl >/dev/null 2>&1 || die "curl is needed to download the package"

# ---------------------------------------------------------------- detect shape
case "$(uname -m)" in
  x86_64 | amd64) ARCH_X86=1; DEB_ARCH=amd64; RPM_ARCH=x86_64; PKG_ARCH=x86_64 ;;
  aarch64 | arm64) ARCH_X86=0; DEB_ARCH=arm64; RPM_ARCH=aarch64; PKG_ARCH=aarch64 ;;
  *) die "unsupported architecture: $(uname -m) — build from source instead: https://github.com/$REPO" ;;
esac

# A distribution is whatever its package manager is, not what /etc/os-release
# calls it: Mint installs debs, CachyOS installs pacman packages, and both say
# something different about themselves than Debian or Arch do.
if command -v pacman >/dev/null 2>&1; then
  FAMILY=pacman
elif command -v dpkg >/dev/null 2>&1; then
  FAMILY=deb
elif command -v rpm >/dev/null 2>&1; then
  FAMILY=rpm
else
  die "no supported package manager found (pacman, dpkg or rpm)"
fi

DISTRO="$(. /etc/os-release 2>/dev/null && printf '%s' "${PRETTY_NAME:-${ID:-unknown}}")"
say "adfree: ${DISTRO} → ${FAMILY} package"

# ------------------------------------------------------------------- the asset
if [ -z "$VERSION" ]; then
  # The releases/latest redirect names the version without asking the API for
  # JSON, so this works without a token and without a rate limit of 60 an hour.
  VERSION="$(curl -fsSLI -o /dev/null -w '%{url_effective}' "https://github.com/$REPO/releases/latest" |
    sed -e 's#.*/tag/##' -e 's#^v##')"
  [ -n "$VERSION" ] || die "could not work out the latest version; set ADFREE_VERSION"
fi
say "adfree: version ${VERSION}"

case "$FAMILY" in
  deb) ASSET="adfree_${VERSION}_${DEB_ARCH}.deb" ;;
  rpm) ASSET="adfree-${VERSION}-1.${RPM_ARCH}.rpm" ;;
  pacman)
    [ "$ARCH_X86" -eq 1 ] || die "there is no Arch package for aarch64 yet; a deb or an rpm is the other way in"
    ASSET="adfree-${VERSION}-1-${PKG_ARCH}.pkg.tar.zst"
    ;;
esac

URL="https://github.com/$REPO/releases/download/v${VERSION}/${ASSET}"
TMPDIR_ADFREE="$(mktemp -d)"
trap 'rm -rf "$TMPDIR_ADFREE"' EXIT

say "adfree: downloading ${ASSET}"
curl -fL --proto '=https' --tlsv1.2 --retry 3 -o "$TMPDIR_ADFREE/$ASSET" "$URL" ||
  die "download failed: $URL"

# ------------------------------------------------------------------- install it
# sudo only where it is needed: a script that starts with $EUID 0 refuses to run
# under an unprivileged shell that has nothing to escalate.
as_root() {
  if [ "$(id -u)" -eq 0 ]; then "$@"; else sudo "$@"; fi
}

case "$FAMILY" in
  deb)
    if command -v apt-get >/dev/null 2>&1; then
      as_root apt-get install -y "$TMPDIR_ADFREE/$ASSET"
    else
      # apt is not on every dpkg distribution; dpkg plus a dependency fix is the
      # portable pair.
      as_root dpkg -i "$TMPDIR_ADFREE/$ASSET" || as_root apt-get -f install -y
    fi
    ;;
  rpm)
    if command -v dnf >/dev/null 2>&1; then
      as_root dnf install -y "$TMPDIR_ADFREE/$ASSET"
    elif command -v zypper >/dev/null 2>&1; then
      as_root zypper --non-interactive install --allow-unsigned-rpm "$TMPDIR_ADFREE/$ASSET"
    elif command -v yum >/dev/null 2>&1; then
      as_root yum install -y "$TMPDIR_ADFREE/$ASSET"
    else
      as_root rpm -Uvh --replacepkgs "$TMPDIR_ADFREE/$ASSET"
    fi
    ;;
  pacman)
    as_root pacman -U --noconfirm "$TMPDIR_ADFREE/$ASSET"
    ;;
esac

say ""
say "adfree: installed ${VERSION}. Start it from your application menu, or run:"
say "        /opt/adfree/bin/adfree"
say ""
say "The first run asks for the local CA before any HTTPS filtering happens,"
say "and installs the DNS redirects only for as long as it is running."
