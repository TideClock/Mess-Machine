// The lab and everything that plays through its sound core (NSD-41): the jar bar, the doctor's table, the card,
// import, walk mode's shelf and console, the jam, the ring, the heap, and the size of #home.
// Needs no assets: it imports a sound it makes itself. Needs http (the machine room).
//   NSD_URL=http://127.0.0.1:8790/index.html node lab.js
const { chromium } = require('playwright');
const fs = require('fs'), os = require('os'), path = require('path');
const U = process.env.NSD_URL || 'http://127.0.0.1:8790/index.html';
const DOM_MAX = +(process.env.NSD_DOM_MAX || 2400);   /* #home was 2,963 nodes with the old machine, 1,933 without */
let fails = 0;
const check = (name, ok, info = '') => { if (!ok) fails++; console.log(`${ok ? 'PASS' : 'FAIL'} ${name}${info ? '  ' + info : ''}`); };
const inbox = p => p.evaluate(() => JSON.parse(localStorage.getItem('mmweb.inbox') || '[]'));
const clearInbox = p => p.evaluate(() => localStorage.removeItem('mmweb.inbox'));
/* a 0.8 s test sound: four plucks, so a chop has something to cut */
function testWav(){
  const sr = 44100, n = Math.round(sr * .8), b = Buffer.alloc(44 + n * 2);
  b.write('RIFF', 0); b.writeUInt32LE(36 + n * 2, 4); b.write('WAVEfmt ', 8); b.writeUInt32LE(16, 16); b.writeUInt16LE(1, 20); b.writeUInt16LE(1, 22);
  b.writeUInt32LE(sr, 24); b.writeUInt32LE(sr * 2, 28); b.writeUInt16LE(2, 32); b.writeUInt16LE(16, 34); b.write('data', 36); b.writeUInt32LE(n * 2, 40);
  for (let i = 0; i < n; i++){ const t = (i % (n / 4)) / sr; b.writeInt16LE(Math.round(Math.sin(2 * Math.PI * 220 * t) * Math.exp(-t * 18) * 26000), 44 + i * 2); }
  const f = path.join(os.tmpdir(), 'nsd-lab-test-pluck.wav'); fs.writeFileSync(f, b); return f;
}
(async () => {
  const b = await chromium.launch({ args: ['--autoplay-policy=no-user-gesture-required'] });
  const ctx = await b.newContext({ viewport: { width: 390, height: 844 }, isMobile: true, hasTouch: true });
  await ctx.addInitScript(() => { try { localStorage.setItem('nsd.lights', '"day"'); localStorage.setItem('nsd.guided', 'true'); } catch {} });
  const p = await ctx.newPage(); const errs = []; p.setDefaultTimeout(10000);
  p.on('pageerror', e => errs.push(e.message));
  try {
    await p.goto(U + '#home'); await p.waitForTimeout(1500);
    const nodes = await p.evaluate(() => document.getElementsByTagName('*').length);
    check('#home stays small', nodes <= DOM_MAX, `${nodes} nodes (ceiling ${DOM_MAX})`);
    check('no old machine in the page', await p.evaluate(() => !document.querySelector('#seq, #dock, .mdock, #padmenu, #messes, .logbook, #jamsave, .bg-pad')));

    // import: the sound lands in the Workbench and on a pad in the machine
    await p.goto(U + '#specimens'); await p.waitForTimeout(1200);
    await clearInbox(p);
    await p.setInputFiles('#impfile', testWav()); await p.waitForTimeout(2000);
    let q = await inbox(p);
    check('import goes in the Workbench and to the machine', q.length === 1 && /^bn:/.test(q[0].k) && !!q[0].own, JSON.stringify(q).slice(0, 90));
    const K = q[0] && q[0].k;
    await p.evaluate(() => document.querySelector('#labroom .bigjar[data-cab="mine"]').click()); await p.waitForTimeout(800);
    await p.evaluate(k => document.querySelector(`.jar2[data-k="${k}"] .j`).click(), K); await p.waitForTimeout(500);
    check('tapping a jar plays it and picks it', await p.evaluate(k => { const j = document.querySelector(`.jar2[data-k="${k}"]`); return j.classList.contains('hit') && j.classList.contains('sel'); }, K));
    const bar = await p.evaluate(() => { const b = document.getElementById('jarbar'), r = b.getBoundingClientRect(); return { on: !b.hidden, fixed: getComputedStyle(b).position === 'fixed', w: Math.round(r.width), gap: Math.round(innerHeight - r.bottom), btns: [...b.querySelectorAll('button')].map(x => x.innerText.trim().toLowerCase()) }; });
    check('the jar bar is a fixed bar at the bottom', bar.on && bar.fixed && bar.w > 100 && bar.gap >= 0 && bar.gap < 40, JSON.stringify(bar));
    check('the jar bar has Hear, Star, Put on a pad, Doctor, Card', ['hear', 'star', 'put on a pad', 'doctor', 'card'].every(w => bar.btns.some(t => t.includes(w))));
    // Hear, Star, Put on a pad
    await p.evaluate(k => document.querySelector(`.jar2[data-k="${k}"]`).classList.remove('hit'), K);
    await p.evaluate(() => document.querySelector('#jarbar [data-jb="play"]').click()); await p.waitForTimeout(400);
    check('Hear plays it', await p.evaluate(k => document.querySelector(`.jar2[data-k="${k}"]`).classList.contains('hit'), K));
    await p.evaluate(() => document.querySelector('#jarbar [data-jb="fav"]').click()); await p.waitForTimeout(300);
    check('Star stars it', await p.evaluate(k => JSON.parse(localStorage.getItem('nsd.favs') || '[]').includes(k), K));
    await clearInbox(p);
    await p.evaluate(() => document.querySelector('#jarbar [data-jb="pad"]').click()); await p.waitForTimeout(1000);
    q = await inbox(p);
    check('Put on a pad sends it to the machine', q.length === 1 && q[0].k === K && !!q[0].own);
    // the doctor's table: put on a pad, fit to its own tempo, chop to the machine, close
    await p.evaluate(() => document.querySelector('#jarbar [data-jb="doc"]').click()); await p.waitForTimeout(1000);
    check('Doctor opens the doctor\'s table', await p.evaluate(() => !document.getElementById('docmodal').hidden));
    await clearInbox(p);
    await p.evaluate(() => document.getElementById('doc-load').click()); await p.waitForTimeout(1000);
    q = await inbox(p);
    check('doctor: Put it on a pad sends it to the machine', q.length === 1 && q[0].k === K);
    await p.evaluate(() => { document.getElementById('doc-fitbars').value = '0.25'; document.getElementById('doc-bpm').value = '90'; document.getElementById('doc-fit').click(); }); await p.waitForTimeout(300);
    check('doctor: Fit it to the tempo uses its own BPM box', await p.evaluate(() => /90 BPM/.test(document.getElementById('toast').innerText)));
    await p.evaluate(() => document.getElementById('doc-reset').click());
    await clearInbox(p);
    await p.evaluate(() => { const n = document.getElementById('chopn'); n.value = '4'; n.dispatchEvent(new Event('input', { bubbles: true })); document.getElementById('doc-chop').click(); }); await p.waitForTimeout(2500);
    q = await inbox(p);
    check('doctor: Chop to pads sends 4 chops to the machine', q.length === 4 && q.every(x => /^chop:/.test(x.k) && x.own), `${q.length}`);
    await p.evaluate(() => document.getElementById('docx').click()); await p.waitForTimeout(400);
    check('the doctor closes', await p.evaluate(() => document.getElementById('docmodal').hidden));
    // the card: one Put on a pad button
    await clearInbox(p);
    await p.evaluate(k => document.querySelector(`.jar2[data-k="${k}"] .j`).click(), K); await p.waitForTimeout(300);
    await p.evaluate(() => document.querySelector('#jarbar [data-jb="card"]').click()); await p.waitForTimeout(1500);
    check('Card opens with one Put on a pad button', await p.evaluate(() => !document.getElementById('cardmodal').hidden && !!document.getElementById('c-pad') && !/Load into machine|Unload/.test(document.getElementById('cardwrap').innerText)));
    await p.evaluate(() => document.getElementById('c-pad').click()); await p.waitForTimeout(1000);
    q = await inbox(p);
    check('card: Put on a pad sends it to the machine', q.length === 1 && q[0].k === K);
    await p.evaluate(() => document.getElementById('cardx').click()); await p.waitForTimeout(300);

    // walk: no logbook lectern, the shelf's +Pad and the lab console reach the machine, the ring opens
    await clearInbox(p);
    await p.evaluate(() => document.getElementById('walkbtn').click()); await p.waitForTimeout(900);
    check('walk: no logbook lectern', await p.evaluate(() => { const w = window.__walk; w.floor = 'base'; w.wBuild(); return !w.wF.objs.some(o => o.kind === 'lectern' || /logbook/i.test(o.label || '')); }));
    await p.evaluate(() => window.__walk.wF.objs.find(o => o.cab === 'thumps').look()); await p.waitForTimeout(500);
    check('walk: a jar shelf has no old pad numbers', await p.evaluate(() => !document.getElementById('wshelf').hidden && !/\bP\d/.test(document.getElementById('wshelf').innerText)));
    await p.evaluate(() => document.querySelector('#wshelf [data-pad]').click()); await p.waitForTimeout(1200);
    q = await inbox(p);
    check('walk: the shelf +Pad sends the jar to the machine', q.length === 1 || /waits on a pad/.test(await p.evaluate(() => document.getElementById('toast').innerText)));
    await p.evaluate(() => { document.getElementById('wshelf').hidden = true; window.__walk.wF.objs.find(o => o.kind === 'ring').look(); }); await p.waitForTimeout(1200);
    check('the ring opens', await p.evaluate(() => !document.getElementById('ring').hidden && document.getElementById('ringbody').children.length > 0));
    await p.evaluate(() => document.getElementById('ringx').click()); await p.waitForTimeout(400);
    check('the ring closes', await p.evaluate(() => document.getElementById('ring').hidden));
    await p.evaluate(() => window.__walk.wF.objs.find(o => o.kind === 'console').look()); await p.waitForTimeout(2500);
    check('walk: the lab console opens the Mess Machine', await p.evaluate(() => !document.getElementById('mmroom').hidden));
    await p.keyboard.press('Escape'); await p.waitForTimeout(500);

    // the jam: opens from the walk amp, its clock runs, it closes
    await p.goto(U + '#home'); await p.waitForTimeout(1200);
    await p.evaluate(() => document.getElementById('walkbtn').click()); await p.waitForTimeout(900);
    await p.evaluate(() => { const w = window.__walk; w.floor = 'main'; w.wBuild(); w.wF.objs.find(o => o.kind === 'amp').look(); }); await p.waitForTimeout(1500);
    check('the jam opens from the walk amp', await p.evaluate(() => !document.getElementById('jam').hidden && window.__nsdMP.state().jam.on && document.querySelectorAll('#jamkeys .jk').length > 5 && !!document.getElementById('jamsnd').value));
    check('jam: its loop clock runs', await p.evaluate(() => document.querySelectorAll('#jamgrid .jg-st.now').length > 0));
    await p.evaluate(() => { document.getElementById('jamhit').dispatchEvent(new PointerEvent('pointerdown', { bubbles: true })); document.querySelector('#jamkeys .jk').dispatchEvent(new PointerEvent('pointerdown', { bubbles: true })); }); await p.waitForTimeout(300);
    await p.evaluate(() => document.getElementById('jamx').click()); await p.waitForTimeout(400);
    check('the jam closes', await p.evaluate(() => document.getElementById('jam').hidden && !window.__nsdMP.state().jam.on));
  } catch (e) { check('ran to the end', false, e.message.split('\n')[0]); }

  // the heap, unlocked the way the page unlocks it
  const ctx2 = await b.newContext({ viewport: { width: 390, height: 844 }, isMobile: true, hasTouch: true });
  await ctx2.addInitScript(() => { try { localStorage.setItem('nsd.guided', 'true'); localStorage.setItem('nsd.me', JSON.stringify({ vault: true })); } catch {} });
  const p2 = await ctx2.newPage(); p2.on('pageerror', e => errs.push('heap: ' + e.message));
  await p2.goto(U + '#crawlspace'); await p2.waitForTimeout(1500);
  const heap = await p2.evaluate(() => ({ piles: document.querySelectorAll('#crawlbody .pile').length, jars: document.querySelectorAll('#crawlbody .jar2').length }));
  check('the heap renders when unlocked', heap.piles > 0 && heap.jars > 0, JSON.stringify(heap));
  await p2.evaluate(() => { document.querySelector('#crawlbody details.pile').open = true; document.querySelector('#crawlbody .jar2 .j').click(); }); await p2.waitForTimeout(500);
  check('heap: a picked jar gets the jar bar', await p2.evaluate(() => !document.getElementById('jarbar').hidden));

  check('no page errors', !errs.length, errs.slice(0, 3).join(' | '));
  await b.close(); console.log(fails ? `${fails} FAILED` : 'ALL PASS'); process.exit(fails ? 1 : 0);
})();
