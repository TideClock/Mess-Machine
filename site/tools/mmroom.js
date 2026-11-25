// The Mess Machine room (the app's own machine in mm/, NSD-41): open it from the lab, a jar put on a pad lands,
// Escape and Back close it. The machine is a module page, so this needs http, not file://.
//   NSD_URL=http://127.0.0.1:8790/index.html node mmroom.js     (bw.html for the band site)
const { chromium } = require('playwright');
const URL = process.env.NSD_URL || 'http://127.0.0.1:8790/index.html';
let fails = 0;
const check = (sz, name, ok, info = '') => { if (!ok) fails++; console.log(`${ok ? 'PASS' : 'FAIL'} ${sz} ${name}${info ? '  ' + info : ''}`); };
const KEY = 'samples/wet-clap.wav', TITLE = /wet clap/i;
(async () => {
  if (!/^https?:/.test(URL)) { console.log('FAIL mmroom.js needs NSD_URL over http (the machine will not load from file://)'); process.exit(1); }
  const b = await chromium.launch({ args: ['--autoplay-policy=no-user-gesture-required'] });
  for (const [sz, vp] of [['phone', { width: 390, height: 844 }], ['desktop', { width: 1280, height: 900 }]]) {
    const ctx = await b.newContext({ viewport: vp, hasTouch: sz === 'phone', isMobile: sz === 'phone' });
    await ctx.addInitScript(() => { try { localStorage.setItem('nsd.guided', 'true'); } catch {} });
    const p = await ctx.newPage(); const errs = []; p.setDefaultTimeout(10000);
    p.on('pageerror', e => errs.push(e.message));
    try {
      await p.goto(URL + '#specimens'); await p.waitForTimeout(1500);
      check(sz, 'the lab is open', await p.evaluate(() => document.getElementById('v-specimens').classList.contains('on')));
      check(sz, 'no old machine in the page', await p.evaluate(() => !document.querySelector('#seq, #dock, .mdock, #padmenu, #messes, .logbook')));
      // pick a jar and put it on a pad
      await p.evaluate(() => document.querySelector('#labroom .bigjar[data-cab="thumps"]').click()); await p.waitForTimeout(800);
      await p.evaluate(k => document.querySelector(`.jar2[data-k="${k}"] .j`).click(), KEY); await p.waitForTimeout(400);
      check(sz, 'the jar bar shows', await p.evaluate(() => { const b = document.getElementById('jarbar'); return !b.hidden && b.getBoundingClientRect().width > 100; }));
      await p.evaluate(() => document.querySelector('#jarbar [data-jb="pad"]').click()); await p.waitForTimeout(1200);
      const q = await p.evaluate(() => JSON.parse(localStorage.getItem('mmweb.inbox') || '[]'));
      check(sz, 'Put on a pad queues the jar', q.length === 1 && q[0].k === KEY, JSON.stringify(q).slice(0, 100));
      // open the room: the jar lands, the inbox drains, the lab behind is inert
      await p.click('#mmopen'); await p.waitForTimeout(4000);
      const fr = p.frames().find(f => /mm\/index\.html/.test(f.url()));
      check(sz, 'the machine loads in its room', !!fr && await p.evaluate(() => !document.getElementById('mmroom').hidden));
      check(sz, 'the lab behind the room is inert', await p.evaluate(() => document.querySelector('main').inert));
      check(sz, 'the jar is on a pad in the machine', !!fr && await fr.evaluate(t => new RegExp(t, 'i').test(document.body.innerText), TITLE.source));
      check(sz, 'the inbox is drained', await p.evaluate(() => { const v = localStorage.getItem('mmweb.inbox'); return !v || v === '[]'; }));
      // a real tap on Start, through the page (an inert room would swallow it: the Nov 24 bug Tyler hit)
      let started = false; try { await p.frameLocator('#mmframe').locator('#start .go').click({ timeout: 4000 }); await p.waitForTimeout(800); started = !!fr && await fr.evaluate(() => !document.getElementById('start')); } catch (e) {}
      check(sz, 'Start takes a real tap', started);
      let padOk = false; try { await p.frameLocator('#mmframe').locator('.pad').first().click({ timeout: 3000 }); padOk = true; } catch (e) {}
      check(sz, 'a pad takes a real tap', padOk);
      // Escape closes it (pressed inside the machine)
      await fr.evaluate(() => document.body.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true }))); await p.waitForTimeout(600);
      check(sz, 'Escape closes the room', await p.evaluate(() => document.getElementById('mmroom').hidden && !document.querySelector('main').inert));
      check(sz, 'focus goes back to the Open button', await p.evaluate(() => document.activeElement && document.activeElement.id === 'mmopen'));
      // Back closes it and stays in the lab
      await p.click('#mmopen'); await p.waitForTimeout(1500);
      check(sz, 'the room opens again', await p.evaluate(() => !document.getElementById('mmroom').hidden));
      await p.goBack(); await p.waitForTimeout(800);
      check(sz, 'Back closes the room and stays in the lab', await p.evaluate(() => document.getElementById('mmroom').hidden && location.hash === '#specimens' && document.getElementById('v-specimens').classList.contains('on')));
    } catch (e) { check(sz, 'ran to the end', false, e.message.split('\n')[0]); }
    check(sz, 'no page errors', !errs.length, errs.slice(0, 3).join(' | '));
    await ctx.close();
  }
  await b.close(); console.log(fails ? `${fails} FAILED` : 'ALL PASS'); process.exit(fails ? 1 : 0);
})();
