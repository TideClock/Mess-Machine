const { chromium } = require('playwright');
const URL = 'file://' + (process.env.NSD_PAGE || '/home/claude/site/live.html') + '';
(async () => {
  const b = await chromium.launch({ args: ['--autoplay-policy=no-user-gesture-required'] });
  for (const [sz, vp] of [['phone', { width: 390, height: 844 }], ['desktop', { width: 1280, height: 900 }]]) {
    const ctx = await b.newContext({ viewport: vp, hasTouch: sz === 'phone', isMobile: sz === 'phone' });
    const p = await ctx.newPage(); const errs = [];
    p.on('pageerror', e => errs.push('pageerror: ' + e.message)); p.on('console', m => { if (m.type() === 'error' && !/ERR_FILE_NOT_FOUND|TUNNEL/.test(m.text())) errs.push(m.text().slice(0, 160)); });
    const count = (sel) => p.evaluate((sel) => { const root = document.querySelector(sel); if (!root) return -1; const vis = el => { const cs = getComputedStyle(el); const r = el.getBoundingClientRect(); return cs.display !== 'none' && cs.visibility !== 'hidden' && r.width > 0 && r.height > 0; }; return [...root.querySelectorAll('button,a[href],input,select,[role=button],[role=tab]')].filter(vis).length; }, sel);
    const log = (...a) => console.log(sz, ...a);
    await p.goto(URL + '#specimens'); await p.waitForTimeout(1500);
    log('lab section controls', await count('#v-specimens'), 'seq', await count('#seq'), 'dock', await count('.dock, #dock'));
    await p.screenshot({ path: `shots/${sz}-lab-full.png`, fullPage: true });
    // machine tabs
    for (const t of ['pads', 'steps', 'roll', 'song', 'fx']) {
      const tab = p.locator(`#mt-${t}`); if (await tab.isVisible().catch(() => false)) { await tab.click(); await p.waitForTimeout(400); log('tab', t, 'panel controls', await count('#mp-' + t), 'whole machine', await count('#seq')); await p.locator('#seq').screenshot({ path: `shots/${sz}-mm-${t}.png` }).catch(e => log('shot fail', e.message)); }
      else log('tab', t, 'not visible');
    }
    // empty pad double tap
    await p.locator('#mt-pads').click().catch(() => {});
    const empty = p.locator('#padgrid button').nth(8);
    if (await empty.isVisible().catch(() => false)) { await empty.dblclick(); await p.waitForTimeout(500); log('padmenu open after dblclick', await p.locator('#padmenu').isVisible()); await p.screenshot({ path: `shots/${sz}-padmenu.png` }); await p.keyboard.press('Escape'); }
    // open a jar
    const jar = p.locator('.bigjar').first(); if (await jar.isVisible().catch(() => false)) { await jar.click(); await p.waitForTimeout(900); log('jar opened, lab controls', await count('#v-specimens')); await p.screenshot({ path: `shots/${sz}-jar-open.png` }); }
    // hwat
    const hw = p.locator('#v-specimens .hwat:visible').first(); if (await hw.count()) { await hw.click(); await p.waitForTimeout(400); log('hwat modal', await p.locator('#hwatmodal').isVisible()); await p.screenshot({ path: `shots/${sz}-hwat.png` }); await p.keyboard.press('Escape'); }
    log('hwat buttons per view:', JSON.stringify(await p.evaluate(() => [...document.querySelectorAll('section.view')].map(s => s.id.slice(2) + ':' + s.querySelectorAll('.hwat').length).join(' '))));
    // header menus
    await p.goto(URL + '#home'); await p.waitForTimeout(1200);
    await p.locator('#housebtn').click(); await p.waitForTimeout(300); log('house menu items', await count('#housemenu')); await p.screenshot({ path: `shots/${sz}-housemenu.png` }); await p.keyboard.press('Escape'); await p.locator('#housebtn').click().catch(() => {});
    await p.locator('#pocketbtn').click(); await p.waitForTimeout(400); log('drawer controls', await count('#drawer')); await p.screenshot({ path: `shots/${sz}-drawer.png` }); await p.locator('#drawerx').click().catch(() => {});
    await p.locator('#walkbtn').click(); await p.waitForTimeout(1500); log('walk on; visible controls', await p.evaluate(() => [...document.querySelectorAll('button')].filter(b => b.getBoundingClientRect().width > 0 && getComputedStyle(b).visibility !== 'hidden').length)); await p.screenshot({ path: `shots/${sz}-walk.png` }); await p.locator('#walkbtn').click().catch(() => {});
    // footer
    log('footer controls', await count('footer.site'));
    log('errors', JSON.stringify(errs));
    await ctx.close();
  }
  await b.close();
})();
