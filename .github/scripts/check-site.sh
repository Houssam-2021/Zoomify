#!/usr/bin/env bash
# check-site.sh BASE_URL : fails on any broken page, link, redirect, HTTPS or download problem.
set -uo pipefail
BASE=${1%/}; ok=1; REPO=Houssam-2021/Zoomify
LATEST=$(curl -sS -o /dev/null -w '%{redirect_url}' "https://github.com/$REPO/releases/latest" | sed 's#.*/tag/##')
echo "Latest verified release: $LATEST"
check() { code=$(curl -sS -o /dev/null -w '%{http_code}' "$BASE$1"); echo "$code $1"; [ "$code" = "$2" ] || { echo "  expected $2"; ok=0; }; }
check / 200; check /help.html 200; check /privacy.html 200; check /contact.html 200; check /assets/pages.css 200
check /robots.txt 200; check /sitemap.xml 200; check /this-page-does-not-exist 404
for path in /download /Zoomify.apk; do
  final=$(curl -sS -L -o /tmp/site.apk -w '%{http_code} %{url_effective}' "$BASE$path")
  hops=$(curl -sS -L -o /dev/null -w '%{num_redirects}' "$BASE$path")
  echo "$path -> $hops redirects -> HTTP ${final%% *}, $(stat -c %s /tmp/site.apk) bytes"
  [ "${final%% *}" = 200 ] && [ "$(stat -c %s /tmp/site.apk)" -gt 1000000 ] || ok=0
  want=$(curl -sS -L "https://github.com/$REPO/releases/download/$LATEST/Zoomify.apk.sha256" | cut -d' ' -f1)
  got=$(sha256sum /tmp/site.apk | cut -d' ' -f1)
  [ -n "$want" ] && [ "$got" = "$want" ] && echo "  sha256 matches $LATEST: $got" || { echo "  sha256 $got != $LATEST $want"; ok=0; }
done
loc=$(curl -sS -o /dev/null -w '%{redirect_url}' "$BASE/release-notes"); echo "/release-notes -> $loc"
[[ "$loc" == "https://github.com/$REPO/releases/latest" ]] || ok=0
case "$BASE" in https://*)
  http="http://${BASE#https://}"
  code=$(curl -sS -o /dev/null -w '%{http_code} %{redirect_url}' "$http/"); echo "HTTP -> $code"
  [[ "$code" == 30[178]\ https://* ]] || { echo "  plain HTTP is not redirected to HTTPS"; ok=0; } ;;
esac
curl -sS "$BASE/" | grep -q 'href="/download"' || { echo "home page has no /download link"; ok=0; }
curl -sS "$BASE/" "$BASE/help.html" | grep -q 'android-latest' && { echo "site still mentions android-latest"; ok=0; }
for page in / /help.html /privacy.html /contact.html /404.html; do
  for href in $(curl -sS "$BASE$page" | grep -oE 'href="/[^"#]*' | sed 's/href="//' | sort -u); do
    c=$(curl -sS -o /dev/null -w '%{http_code}' "$BASE$href"); [[ "$c" =~ ^(200|301|302)$ ]] || { echo "BROKEN $page -> $href ($c)"; ok=0; }
  done
done
for href in $(curl -sS "$BASE/" "$BASE/help.html" "$BASE/privacy.html" "$BASE/contact.html" | grep -oE 'href="https://[^"]+' | sed 's/href="//' | sort -u); do
  c=$(curl -sS -L -o /dev/null -w '%{http_code}' -A "Mozilla/5.0" "$href"); echo "$c $href"
  [[ "$c" =~ ^(200|403|429)$ ]] || { echo "BROKEN external $href ($c)"; ok=0; }
done
[ $ok = 1 ] && echo "SITE CHECK PASSED: $BASE" || { echo "SITE CHECK FAILED: $BASE"; exit 1; }
