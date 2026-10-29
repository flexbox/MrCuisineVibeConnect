#!/usr/bin/env bash
# Publishes a Toque release on GitHub from the Mac (the same thing .github/workflows/release.yml does
# when GitHub Actions is available): tag vX.Y.Z, release notes from the commits, the APK attached.
# Robots then find it in Settings > Updates, and toque-setup.sh downloads it. macOS port of
# publish-release.ps1, through the GitHub CLI (brew install gh).
#
#   tools/publish-release.sh --apk Toque-0.5.1.apk [--repo owner/name] [--repo-info] [--dry-run]
#
# Token: $GH_TOKEN, else $GITHUB_TOKEN, else gh's own login (gh auth login), else the github.com
# credential already stored for git (macOS keychain). It is never printed.
# --dry-run only reads from GitHub and prints what would be published.
set -u
REPO_SLUG="Wuki60/MrCuisineVibeConnect"
APK=""
REPO_INFO=0
DRY_RUN=0
while [ $# -gt 0 ]; do
  case "$1" in
    --apk) APK=${2:?--apk needs a file}; shift 2 ;;
    --repo) REPO_SLUG=${2:?--repo needs owner/name}; shift 2 ;;
    --repo-info) REPO_INFO=1; shift ;;
    --dry-run) DRY_RUN=1; shift ;;
    *) echo "Usage: tools/publish-release.sh --apk FILE [--repo owner/name] [--repo-info] [--dry-run]"; exit 1 ;;
  esac
done
die() { echo "$*" >&2; exit 1; }
[ -n "$APK" ] || die "Usage: tools/publish-release.sh --apk FILE [--repo owner/name] [--repo-info] [--dry-run]"
[ -f "$APK" ] || die "APK not found: $APK"
APK="$(cd "$(dirname "$APK")" && pwd -P)/$(basename "$APK")"
cd "$(dirname "$0")/.." || exit 1
command -v gh > /dev/null 2>&1 || die "GitHub CLI missing: brew install gh"

version=$(sed -n 's/^[[:space:]]*versionName[[:space:]]*=[[:space:]]*"\([^"]*\)".*/\1/p' app/build.gradle.kts | head -n 1)
[ -n "$version" ] || die "versionName not found in app/build.gradle.kts"
tag="v$version"

if [ -z "${GH_TOKEN:-}" ]; then
  if [ -n "${GITHUB_TOKEN:-}" ]; then
    export GH_TOKEN=$GITHUB_TOKEN
  elif ! gh auth token > /dev/null 2>&1; then
    token=$(printf 'protocol=https\nhost=github.com\n\n' | GIT_TERMINAL_PROMPT=0 git credential fill 2> /dev/null | sed -n 's/^password=//p' | head -n 1)
    [ -n "$token" ] || die "No GitHub token found (GH_TOKEN, gh auth login, or git credentials)."
    export GH_TOKEN=$token
  fi
fi
run() { if [ "$DRY_RUN" = 1 ]; then echo "(dry run) $*" >&2; else "$@"; fi; }
[ "$DRY_RUN" = 1 ] && echo "Dry run: nothing below is changed on GitHub."

if gh release view "$tag" --repo "$REPO_SLUG" > /dev/null 2>&1; then
  echo "Release $tag already exists."
else
  prev=$(gh release view --repo "$REPO_SLUG" --json tagName --jq .tagName 2> /dev/null)
  git fetch --tags --quiet 2> /dev/null
  range="HEAD~10..HEAD"
  if [ -n "$prev" ] && git rev-parse -q --verify "refs/tags/$prev" > /dev/null; then range="$prev..HEAD"; fi
  notes=$(git log --no-merges --pretty='- %s' "$range")
  sha=$(git rev-parse HEAD)
  run gh release create "$tag" --repo "$REPO_SLUG" --target "$sha" --title "Toque $version" --notes "$notes" > /dev/null ||
    die "Could not create release $tag."
  echo "Release $tag created."
fi

name="Toque-$version.apk"
if gh release view "$tag" --repo "$REPO_SLUG" --json assets --jq '.assets[].name' 2> /dev/null | grep -qx "$name"; then
  echo "$name already attached."
else
  tmp=$(mktemp -d)
  cp "$APK" "$tmp/$name"
  run gh release upload "$tag" "$tmp/$name" --repo "$REPO_SLUG" || { rm -rf "$tmp"; die "Could not attach $name."; }
  rm -rf "$tmp"
  echo "$name attached."
fi

if [ "$REPO_INFO" = 1 ]; then
  run gh repo edit "$REPO_SLUG" \
    --description "Toque: a modern replacement interface for the Lidl Monsieur Cuisine Connect (SKMC 1200), with the official recipes, guided cooking and an AI-agent-friendly install guide." \
    --homepage "https://github.com/$REPO_SLUG#readme" > /dev/null || die "Could not update the description."
  run gh api -X PUT "repos/$REPO_SLUG/topics" -f 'names[]=monsieur-cuisine' -f 'names[]=monsieur-cuisine-connect' \
    -f 'names[]=lidl' -f 'names[]=silvercrest' -f 'names[]=android' -f 'names[]=kotlin' -f 'names[]=magisk' \
    -f 'names[]=root' -f 'names[]=recipes' -f 'names[]=ai-agent' > /dev/null || die "Could not update the topics."
  echo "Repository description and topics updated."
fi
echo "https://github.com/$REPO_SLUG/releases/tag/$tag"
