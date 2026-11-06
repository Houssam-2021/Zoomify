#!/usr/bin/env bash
# Makes the old, widely shared URL
#   https://github.com/<repo>/releases/download/android-latest/Zoomify.apk
# serve the newest verified APK. GitHub can't redirect release-asset URLs, but it serves them by file name, and a
# renamed asset keeps its id and download count. So: upload the verified APK under a temporary name, rename the
# previous file (keeping its history), then give the new upload the public name. Nothing is deleted.
# Usage: refresh-legacy-link.sh <verified-apk> <version-tag>     (needs GH_TOKEN, GH_REPO, BT=build-tools dir)
set -euo pipefail
APK=$1; TAG=$2; LEGACY_TAG=android-latest
NEW_CERT=7296cdbf1dd613c75317e45a288fab9bc043d82de5339d44fbec70bf91a7cccf
WANT_SHA=$(sha256sum "$APK" | cut -d' ' -f1)

verify() {  # verify <file>: must be byte-identical to the verified APK and signed with the new key
  [ "$(sha256sum "$1" | cut -d' ' -f1)" = "$WANT_SHA" ] || { echo "::error::$1 checksum mismatch"; return 1; }
  "$BT/apksigner" verify --print-certs "$1" | grep -q "certificate SHA-256 digest: $NEW_CERT" \
    || { echo "::error::$1 is not signed with the new key"; return 1; }
}
verify "$APK"

assets() { gh api "repos/$GH_REPO/releases/tags/$LEGACY_TAG" --jq '.assets[] | "\(.id) \(.name) \(.size) \(.download_count)"'; }
echo "Before:"; assets
CURRENT=$(gh api "repos/$GH_REPO/releases/tags/$LEGACY_TAG" --jq '.assets[] | select(.name=="Zoomify.apk") | .id')

if [ -n "$CURRENT" ]; then
  curl -sSfL -o current.apk "https://github.com/$GH_REPO/releases/download/$LEGACY_TAG/Zoomify.apk"
  if [ "$(sha256sum current.apk | cut -d' ' -f1)" = "$WANT_SHA" ]; then
    echo "The old URL already serves this verified APK. Nothing to do."; exit 0
  fi
  # Name the outgoing file after the version it contains (read from the APK itself).
  OLD_VER=$("$BT/aapt2" dump badging current.apk | grep -oP "versionName='\K[^']+")
  OLD_CERT_IS_NEW=$("$BT/apksigner" verify --print-certs current.apk | grep -c "$NEW_CERT" || true)
  SUFFIX=$([ "$OLD_CERT_IS_NEW" -gt 0 ] && echo "superseded" || echo "old-key-superseded")
  OLD_NAME="Zoomify-$OLD_VER-$SUFFIX.apk"
fi

TMP_NAME="Zoomify-$TAG-uploading.apk"
cp "$APK" "$TMP_NAME"
gh release upload "$LEGACY_TAG" "$TMP_NAME" --clobber
NEW_ID=$(gh api "repos/$GH_REPO/releases/tags/$LEGACY_TAG" --jq ".assets[] | select(.name==\"$TMP_NAME\") | .id")

if [ -n "$CURRENT" ]; then
  gh api -X PATCH "repos/$GH_REPO/releases/assets/$CURRENT" -f name="$OLD_NAME" -f label="$OLD_NAME (kept for history)" >/dev/null
  echo "Renamed previous Zoomify.apk (asset $CURRENT) to $OLD_NAME; its download count is kept."
fi
gh api -X PATCH "repos/$GH_REPO/releases/assets/$NEW_ID" -f name="Zoomify.apk" -f label="Zoomify $TAG (verified)" >/dev/null
echo "Published $TAG as Zoomify.apk (asset $NEW_ID)."
echo "After:"; assets

# Prove the exact old public URL now serves the verified bytes and certificate (retry for CDN propagation).
for i in 1 2 3 4 5 6; do
  curl -sSfL -o served.apk "https://github.com/$GH_REPO/releases/download/$LEGACY_TAG/Zoomify.apk" && verify served.apk && break
  echo "Not yet serving the new file, retrying in 20 s"; sleep 20
done
verify served.apk
echo "VERIFIED: https://github.com/$GH_REPO/releases/download/$LEGACY_TAG/Zoomify.apk serves $TAG (sha256 $WANT_SHA)"

gh release edit "$LEGACY_TAG" --title "Zoomify (old link): now serves $TAG" --notes "**The \`Zoomify.apk\` file in this release is now the verified Zoomify $TAG build**, identical to the one in the [$TAG release](https://github.com/$GH_REPO/releases/tag/$TAG). The old link \`releases/download/android-latest/Zoomify.apk\` keeps working and delivers that file. It's the same file served at the same address, not a redirect.

- SHA-256: \`$WANT_SHA\`
- Signing certificate SHA-256: \`72:96:CD:BF:1D:D6:13:C7:53:17:E4:5A:28:8F:AB:9B:C0:43:D8:2D:E5:33:9D:44:FB:EC:70:BF:91:A7:CC:CF\`

Install it over your current Zoomify: it updates in place and keeps your photos. The original 1.0 build was signed with a key that later became public, so it has been removed. Earlier new-key builds may be kept here under their own names for history only; please install \`Zoomify.apk\`.

Official download page: https://zoomify.houseli.site"
