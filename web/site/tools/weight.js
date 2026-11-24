const { chromium } = require('playwright'); const fs = require('fs');
(async () => {
  const b = await chromium.launch();
  for (const v of ['home', 'specimens', 'shop', 'brotherwife']) {
    const p = await (await b.newContext({ viewport: { width: 390, height: 844 }, isMobile: true })).newPage();
    const reqs = new Set(); p.on('request', r => { const u = r.url(); if (u.startsWith('file://')) reqs.add(decodeURIComponent(u.replace('file://', ''))); else if (!/fonts\.g/.test(u)) reqs.add(u); });
    await p.goto('file://' + (process.env.NSD_PAGE || '/home/claude/site/live.html') + '#' + v, { waitUntil: 'load' }); await p.waitForTimeout(3000);
    let tot = 0; const big = [];
    for (const f of reqs) { try { const s = fs.statSync(f.split('#')[0]).size; tot += s; if (s > 300000) big.push(f.replace('/home/claude/site/', '') + ' ' + (s / 1e6).toFixed(1) + 'MB'); } catch (e) { if (!f.startsWith('/')) big.push('remote ' + f.slice(0, 80)); } }
    console.log(v, 'requests', reqs.size, 'MB', (tot / 1e6).toFixed(1), big.join(', '));
    await p.close();
  }
  await b.close();
})();
