#!/usr/bin/env bash
# Replace a moving release (latest, test-screens, phone-screens) and make sure the world can see it.
# Usage: tools/release.sh TAG TITLE NOTES FILE...   (needs GH_TOKEN, GITHUB_REPOSITORY, GITHUB_SHA)
# Nov 23: "latest" came out of build 168 as a draft. Deleting the old release cleans its tag up a moment later, and a
# release whose tag goes away after it is made turns into a draft, which the app's update check cannot see (404).
# So: wait until the old tag is really gone, pin the new tag to this commit, then check it from outside and un-draft it.
set -u
tag=$1; title=$2; notes=$3; shift 3
gh release delete "$tag" --yes --cleanup-tag -R "$GITHUB_REPOSITORY" || true
for i in $(seq 1 20); do
  gh api "repos/$GITHUB_REPOSITORY/git/refs/tags/$tag" >/dev/null 2>&1 || break
  sleep 3
done
gh release create "$tag" "$@" -R "$GITHUB_REPOSITORY" --target "$GITHUB_SHA" --title "$title" --notes "$notes" --prerelease || exit 1
for i in $(seq 1 10); do
  sleep 6
  code=$(curl -s -o /dev/null -w '%{http_code}' "https://api.github.com/repos/$GITHUB_REPOSITORY/releases/tags/$tag")
  if [ "$code" = 200 ]; then echo "$tag is public"; exit 0; fi
  echo "$tag answers $code from outside; publishing it again"
  gh release edit "$tag" --draft=false -R "$GITHUB_REPOSITORY" || true
done
echo "$tag is still not public"
exit 1
