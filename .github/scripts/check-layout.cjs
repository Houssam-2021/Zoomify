// node check-layout.cjs BASE_URL OUT_DIR : renders every page at phone and desktop sizes in Chromium and fails on
// horizontal overflow (broken mobile layout), HTTP errors or page JavaScript errors. Saves screenshots.
const { chromium } = require('playwright');
(async () => {
const [base, out = 'layout'] = process.argv.slice(2);
const pages = ['/', '/help', '/privacy', '/contact', '/404.html'];
const sizes = { phone: { width: 390, height: 844 }, desktop: { width: 1366, height: 900 } };
const browser = await chromium.launch();
let failed = 0;
for (const [label, viewport] of Object.entries(sizes)) {
  const ctx = await browser.newContext({ viewport, isMobile: label === 'phone', deviceScaleFactor: 2 });
  for (const path of pages) {
    const page = await ctx.newPage();
    const errors = [];
    page.on('pageerror', e => errors.push(e.message));
    const res = await page.goto(base.replace(/\/$/, '') + path, { waitUntil: 'networkidle' });
    await page.waitForTimeout(800);
    const m = await page.evaluate(() => ({ sw: document.documentElement.scrollWidth, iw: window.innerWidth,
      title: document.title, viewport: !!document.querySelector('meta[name=viewport]') }));
    const status = res ? res.status() : 0;
    const expected = path === '/404.html' ? [200, 404] : [200];
    const bad = !expected.includes(status) || m.sw > m.iw + 1 || errors.length || !m.viewport;
    console.log(`${bad ? 'FAIL' : 'ok  '} ${label} ${path} status=${status} width=${m.sw}/${m.iw} "${m.title}"${errors.length ? ' errors=' + errors.join(' | ') : ''}`);
    if (bad) failed++;
    await page.screenshot({ path: `${out}/${label}${path.replace(/\//g, '_') || '_home'}.png`, fullPage: false });
    await page.close();
  }
  await ctx.close();
}
await browser.close();
if (failed) { console.log(`${failed} layout problem(s)`); process.exit(1); }
console.log('LAYOUT CHECK PASSED');
})().catch(e => { console.error(e); process.exit(1); });
