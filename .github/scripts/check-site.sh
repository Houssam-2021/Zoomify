#!/usr/bin/env bash
# check-site.sh BASE_URL : fails on any broken page, link, redirect, HTTPS or download problem.
set -uo pipefail
BASE=${1%/}; ok=1; REPO=Houssam-2021/Zoomify
LATEST=$(curl -sS -o /dev/null -w '%{redirect_url}' "https://github.com/$REPO/releases/latest" | sed 's#.*/tag/##')
echo "Latest verified release: $LATEST"
check() { code=$(curl -sS -o /dev/null -w '%{http_code}' "$BASE$1"); echo "$code $1"; [ "$code" = "$2" ] || { echo "  expected $2"; ok=0; }; }
# follows PATH through at most 5 redirects; it must end with HTTP 200 on this same site (no loops, no off-site hop).
follow() {
  r=$(curl -sS -L --max-redirs 5 -o /dev/null -w '%{http_code} %{num_redirects} %{url_effective}' "$BASE$1")
  read -r fc fn fu <<<"$r"; echo "$1 -> $fn redirects -> $fc $fu"
  [ "$fc" = 200 ] && [[ "$fu" == "$BASE"/* || "$fu" == "$BASE" ]] || { echo "  expected a final 200 on $BASE"; ok=0; }
}
check / 200; check /help 200; check /privacy 200; check /contact 200; check /assets/pages.css 200
check /robots.txt 200; check /sitemap.xml 200; check /this-page-does-not-exist 404
# Old .html addresses must still work (Cloudflare 308-redirects them to the pretty URL).
follow /help.html; follow /privacy.html; follow /contact.html
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
# Fetch bodies into variables first: piping curl into "grep -q" can kill curl with SIGPIPE (curl error 23).
HOME_HTML=$(curl -sS -L "$BASE/") || { echo "could not fetch home page"; ok=0; }
HELP_HTML=$(curl -sS -L "$BASE/help") || { echo "could not fetch help page"; ok=0; }
grep -F 'href="/download"' >/dev/null <<<"$HOME_HTML" || { echo "home page has no /download link"; ok=0; }
grep -F 'android-latest' >/dev/null <<<"$HOME_HTML$HELP_HTML" && { echo "site still mentions android-latest"; ok=0; }
# Internal links: follow redirects (301/302/307/308) and require a successful final response.
for page in / /help /privacy /contact /404.html; do
  body=$(curl -sS -L "$BASE$page")
  for href in $(grep -oE 'href="/[^"#]*' <<<"$body" | sed 's/href="//' | sort -u); do
    r=$(curl -sS -L --max-redirs 8 -r 0-1023 -o /dev/null -w '%{http_code} %{num_redirects}' "$BASE$href")
    read -r c n <<<"$r"; [[ "$c" =~ ^(200|206)$ ]] || { echo "BROKEN $page -> $href (final $c after $n redirects)"; ok=0; }
  done
done
for href in $(curl -sS -L "$BASE/" "$BASE/help" "$BASE/privacy" "$BASE/contact" | grep -oE 'href="https://[^"]+' | sed 's/href="//' | sort -u); do
  c=$(curl -sS -L -o /dev/null -w '%{http_code}' -A "Mozilla/5.0" "$href"); echo "$c $href"
  [[ "$c" =~ ^(200|403|429)$ ]] || { echo "BROKEN external $href ($c)"; ok=0; }
done
[ $ok = 1 ] && echo "SITE CHECK PASSED: $BASE" || { echo "SITE CHECK FAILED: $BASE"; exit 1; }
