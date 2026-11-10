const { chromium } = require('playwright');
const URL = 'file://' + (process.env.NSD_PAGE || '/home/claude/site/live.html') + '';
(async () => {
  const b = await chromium.launch();
  for (const [sz, vp] of [['phone', { width: 390, height: 844 }], ['desktop', { width: 1280, height: 900 }]]) {
    const ctx = await b.newContext({ viewport: vp, hasTouch: sz === 'phone', isMobile: sz === 'phone' });
    const p = await ctx.newPage(); const errs = [];
    p.on('pageerror', e => errs.push('pageerror: ' + e.message));
    const names = (sel) => p.evaluate((sel) => { const root = document.querySelector(sel); if (!root) return []; const vis = el => { const cs = getComputedStyle(el); const r = el.getBoundingClientRect(); return cs.display !== 'none' && cs.visibility !== 'hidden' && r.width > 0 && r.height > 0; }; return [...root.querySelectorAll('button,a[href],input,select,[role=button],[role=tab]')].filter(vis).map(el => (el.getAttribute('aria-label') || el.innerText || el.title || el.type || '').trim().replace(/\s+/g, ' ').slice(0, 28)); }, sel);
    const log = (...a) => console.log(sz, ...a);
    await p.goto(URL + '#specimens'); await p.waitForTimeout(1500);
    await p.locator('[data-pane=machine]').click(); await p.waitForTimeout(800);
    const dock = await names('.dock, #dock, .mdock'); log('dock', dock.length, dock.join(' | '));
    for (const t of ['pads', 'steps', 'roll', 'song', 'fx']) {
      await p.locator(`#mt-${t}`).click(); await p.waitForTimeout(500);
      const all = await names('#seq'); const panel = await names('#mp-' + t);
      const nonGrid = panel.filter(n => !/^(Pad \d|Step|step|\w+ at step|Bar \d)/.test(n));
      log(`\n[${t}] machine ${all.length}, panel ${panel.length}:`, [...new Set(nonGrid)].slice(0, 45).join(' | '));
      await p.screenshot({ path: `shots/${sz}-mm-${t}.png` });
      await p.screenshot({ path: `shots/${sz}-mm-${t}-full.png`, fullPage: true });
    }
    log('\nshared machine head:', (await names('#seq .seqhead')).join(' | '), '||', (await names('#seq .loopbar')).join(' | '));
    await p.locator('#mt-pads').click(); await p.waitForTimeout(300);
    const empty = await p.evaluate(() => { const bs = [...document.querySelectorAll('#padgrid button')]; const i = bs.findIndex(b => /empty/i.test(b.getAttribute('aria-label') || '')); return i; });
    log('first empty pad index', empty);
    if (empty >= 0) { const el = p.locator('#padgrid button').nth(empty); if (sz === 'phone') { await el.tap(); await p.waitForTimeout(120); await el.tap(); } else await el.dblclick(); await p.waitForTimeout(600); log('padmenu visible', await p.locator('#padmenu').isVisible()); await p.screenshot({ path: `shots/${sz}-padmenu.png` }); }
    log('errors', JSON.stringify(errs));
    await ctx.close();
  }
  await b.close();
})();
