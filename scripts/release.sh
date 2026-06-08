#!/usr/bin/env bash
#
# Cuts a release of the DBF Reader plugin from the 'main' branch (GitHub Flow:
# main + short-lived feature branches; main always carries a -SNAPSHOT version).
#
# Two commits:
#   1. release       : strip -SNAPSHOT -> patchChangelog -> clean buildPlugin
#                      -> commit "Release X.Y.Z" -> annotated tag vX.Y.Z
#   2. next iteration: bump main to the next X.Y.Z-SNAPSHOT
#                      -> commit "Start X.Y.Z-SNAPSHOT development"
#
# Run from anywhere in the repo, on a clean 'main'. Both version numbers are
# asked up front (with sensible defaults) and the full plan is confirmed before
# anything changes.
#
# Out of scope (manual): merging feature branches into main, and pushing the
# commits/tag. If the build fails, the script aborts leaving the release edits
# uncommitted so you can inspect or `git checkout -- .`.
#
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

PROPS="gradle.properties"

die() { echo "release: $*" >&2; exit 1; }

set_version() {  # $1 = new version; portable in-place edit (BSD/GNU sed differ on -i)
    local tmp; tmp="$(mktemp)"
    sed "s/^version=.*/version=$1/" "$PROPS" > "$tmp" && mv "$tmp" "$PROPS"
}

# --- preflight: on 'main', with a clean working tree ----------------------
branch="$(git rev-parse --abbrev-ref HEAD)"
[ "$branch" = "main" ] || die "must be on 'main' (currently on '$branch')"

if ! git diff --quiet || ! git diff --cached --quiet; then
    die "working tree is not clean; commit or stash first"
fi

# --- current version -> suggested release version -------------------------
current="$(sed -n 's/^version=//p' "$PROPS")"
[ -n "$current" ] || die "could not read 'version=' from $PROPS"

printf 'Current version: %s\n' "$current"
read -r -p "Release version [${current%-SNAPSHOT}]: " release
release="${release:-${current%-SNAPSHOT}}"

[[ "$release" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] || die "'$release' is not a X.Y.Z version"
tag="v$release"
git rev-parse -q --verify "refs/tags/$tag" >/dev/null 2>&1 && die "tag $tag already exists"

# --- next development version (X.Y.Z-SNAPSHOT; default: patch + 1) ---------
IFS='.' read -r relmaj relmin relpatch <<< "$release"
next_default="$relmaj.$relmin.$((relpatch + 1))-SNAPSHOT"

read -r -p "Next dev version [$next_default]: " next
next="${next:-$next_default}"
next_core="${next%-SNAPSHOT}"
[[ "$next_core" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] || die "'$next' is not a X.Y.Z(-SNAPSHOT) version"
[ "$next_core" != "$release" ] || die "next dev version must differ from the release version"
next="$next_core-SNAPSHOT"

# --- confirm the whole plan before touching anything ----------------------
echo
printf 'Plan:\n'
printf '  release %s   -> commit "Release %s" + tag %s\n' "$release" "$release" "$tag"
printf '  then bump main to %s -> commit "Start %s development"\n' "$next" "$next"
read -r -p "Proceed? [y/N] " ok
[[ "$ok" =~ ^[Yy]$ ]] || die "aborted"

# --- release: bump, changelog, build, commit, tag -------------------------
set_version "$release"
./gradlew patchChangelog            # [Unreleased] -> [release], plus a fresh [Unreleased]
./gradlew clean buildPlugin         # aborts here (leaving edits) if the build fails

git add "$PROPS" CHANGELOG.md
git commit -m "Release $release"
git tag -a "$tag" -m "Release $release"

# --- open the next development iteration ----------------------------------
set_version "$next"
git add "$PROPS"
git commit -m "Start $next development"

echo
printf 'Done. Tagged %s on main; artifact under build/distributions/.\n' "$tag"
printf 'main is now on %s.\n' "$next"
printf 'Push when ready:  git push github main && git push github %s\n' "$tag"
