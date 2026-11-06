#!/usr/bin/env bash
# Builds site-dist/ from site/. AdSense code is added only from a real ADSENSE_PUBLISHER_ID (never invented);
# ad units appear only when ADSENSE_ENABLE_ADS=true (after approval).
set -euo pipefail
rm -rf site-dist && cp -r site site-dist
if [ -n "${ADSENSE_PUBLISHER_ID:-}" ]; then
  [[ "$ADSENSE_PUBLISHER_ID" =~ ^pub-[0-9]{16}$ ]] || { echo "::error::ADSENSE_PUBLISHER_ID must look like pub-0000000000000000"; exit 1; }
  python3 - <<'PY'
import glob, os, re
pub = os.environ["ADSENSE_PUBLISHER_ID"]
tag = f'<script async src="https://pagead2.googlesyndication.com/pagead/js/adsbygoogle.js?client=ca-{pub}" crossorigin="anonymous"></script>'
enable = os.environ.get("ADSENSE_ENABLE_ADS") == "true"
slots = {"home-mid": os.environ.get("ADSENSE_SLOT_HOME", ""), "help-mid": os.environ.get("ADSENSE_SLOT_HELP", "")}
for f in glob.glob("site-dist/*.html"):
    t = open(f, encoding="utf-8").read().replace("<!-- ADSENSE_HEAD -->", tag)
    if enable:
        for name, slot in slots.items():
            if not re.fullmatch(r"[0-9]{6,12}", slot or ""): continue
            unit = (f'<div class="ad-slot container" data-ad-slot="{name}" aria-label="Advertisement">'
                    f'<p style="font-size:11px;letter-spacing:.12em;color:#a09fa9;margin:0 0 6px">ADVERTISEMENT</p>'
                    f'<ins class="adsbygoogle" style="display:block" data-ad-client="ca-{pub}" data-ad-slot="{slot}" '
                    f'data-ad-format="auto" data-full-width-responsive="true"></ins>'
                    f'<script>(adsbygoogle=window.adsbygoogle||[]).push({{}});</script></div>')
            t = re.sub(rf'<div class="ad-slot( container)?" data-ad-slot="{name}" hidden aria-label="Advertisement"></div>', unit, t)
    open(f, "w", encoding="utf-8").write(t)
PY
  echo "google.com, $ADSENSE_PUBLISHER_ID, DIRECT, f08c47fec0942fa0" > site-dist/ads.txt
fi
if [ -n "${CONTACT_EMAIL:-}" ]; then
  sed -i "s|<!-- CONTACT_EMAIL -->|<p><b>Email:</b> <a href=\"mailto:$CONTACT_EMAIL\">$CONTACT_EMAIL</a></p>|" site-dist/contact.html
fi
echo "Built site-dist/ ($(ls site-dist | wc -l) entries)"
