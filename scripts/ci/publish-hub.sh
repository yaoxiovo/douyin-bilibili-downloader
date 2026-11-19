#!/usr/bin/env bash
set -euo pipefail

version=${1:?Hub version is required}
tag="yaoxi-hub-v$version"
apk=${2:?APK path is required}
release=$(gh release view "$tag" --json isDraft,targetCommitish,assets 2>/dev/null || true)
if [[ -n "$release" ]] && jq -e '.isDraft == false' <<< "$release" >/dev/null; then
  jq -e --arg asset "${apk##*/}" '.assets | any(.name == $asset)' <<< "$release" >/dev/null
  echo "$tag is already published."
  exit 0
fi

tag_commit=$(git ls-remote --tags origin "refs/tags/$tag" "refs/tags/$tag^{}" | tail -n 1 | cut -f 1)
if [[ -n "$tag_commit" && "$tag_commit" != "$GITHUB_SHA" ]]; then
  echo "::error::Existing tag $tag points to a different commit"
  exit 1
fi

if [[ -n "$release" ]]; then
  if [[ -z "$tag_commit" ]] && ! jq -e --arg sha "$GITHUB_SHA" '.targetCommitish == $sha' <<< "$release" >/dev/null; then
    echo "::error::Existing draft $tag targets a different commit"
    exit 1
  fi
  gh release upload "$tag" "$apk" --clobber
  gh release edit "$tag" --draft=false --prerelease --latest=false
else
  gh release create "$tag" "$apk" --target "$GITHUB_SHA" \
    --title "Yaoxi Hub $version" \
    --notes "Android 正式签名 APK，包名 com.yaoxiovo.hub。若设备已安装旧版调试 APK，请先备份所需数据并卸载旧版。" \
    --prerelease --latest=false
fi
