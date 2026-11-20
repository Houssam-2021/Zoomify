# Zoomify Cam roadmap

## Planned: AdMob ads (when the app becomes popular)

The owner plans to add AdMob in a later version. Until then the app has **no ads**, and the Play Console declares
"No ads" and "no data collected". Before releasing the version with ads, do all of this, in a test build first:

**App**
- [ ] Add AdMob (Google Mobile Ads SDK). This adds the INTERNET and AD_ID permissions: update the CI permission
      allow-list in `.github/workflows/android.yml`, and the website and privacy wording that today says
      "no internet permission".
- [ ] Consent: Google's User Messaging Platform (UMP) consent form for the EEA, UK and Switzerland, shown before
      any ad.
- [ ] Unobtrusive placement only: never over the shutter, viewfinder or zoom controls, and no accidental taps.
      Consider a banner in settings only, and an optional "remove ads" purchase.
- [ ] Show the privacy policy and a "privacy options" (consent) entry in the app's settings.

**Accounts and legal**
- [ ] AdMob account: the owner must be 18+, otherwise a parent or guardian owns the account and receives payments.
- [ ] `app-ads.txt` on the developer website (the domain listed on Google Play).
- [ ] Re-check the EU "trader" status in Play Console once the app earns money.

**Play Console, before publishing the ads version**
- [ ] Ads declaration: **Yes** (Play shows "Contains ads").
- [ ] Data safety: declare what the Google Mobile Ads SDK collects (Google publishes the exact list).
- [ ] Re-check the content rating and target audience answers.
- [ ] Rewrite `PRIVACY.md` (advertising ID, IP-derived approximate location, app interactions and diagnostics
      sent to Google for ads).

## Also pending
- [ ] Privacy policy link inside the app's settings (opens the browser, so no internet permission is needed).
- [ ] 60 fps on the Galaxy A36: collect the in-app frame-rate diagnostics.
- [ ] Horizon lock and the experimental gyro stabiliser: real-device testing.
- [ ] Closed test with at least 12 testers for 14 days in a row, then request production access.
- [ ] Website: tell website users how to switch to Google Play (uninstall, then install from Play; photos stay).
