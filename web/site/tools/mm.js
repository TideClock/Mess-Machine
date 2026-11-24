// Mess Machine checks (the web machine follows the Android app). Exits 1 on any failed check.
// NSD_PAGE=/path/to/live.html node mm.js
const { chromium } = require('playwright');
const URL = 'file://' + (process.env.NSD_PAGE || '/home/claude/site/live.html');
let fails = 0;
const check = (sz, name, ok, info = '') => { if (!ok) fails++; console.log(`${ok ? 'PASS' : 'FAIL'} ${sz} ${name}${info ? '  ' + info : ''}`); };
(async () => {
  const b = await chromium.launch({ args: ['--autoplay-policy=no-user-gesture-required'] });
  for (const [sz, vp] of [['phone', { width: 390, height: 844 }], ['desktop', { width: 1280, height: 900 }]]) {
    const ctx = await b.newContext({ viewport: vp, hasTouch: sz === 'phone', isMobile: sz === 'phone' });
    const p = await ctx.newPage(); const errs = []; p.setDefaultTimeout(8000);
    try {
    p.on('pageerror', e => errs.push(e.message));
    const vis = sel => p.evaluate(sel => { const el = document.querySelector(sel); if (!el) return false; const cs = getComputedStyle(el), r = el.getBoundingClientRect(); return cs.display !== 'none' && cs.visibility !== 'hidden' && r.width > 0 && r.height > 0 && !el.closest('[hidden]'); }, sel);
    const count = sel => p.evaluate(sel => { const root = document.querySelector(sel); if (!root) return -1; const v = el => { const cs = getComputedStyle(el), r = el.getBoundingClientRect(); return cs.display !== 'none' && cs.visibility !== 'hidden' && r.width > 0 && r.height > 0; }; return [...root.querySelectorAll('button,a[href],input,select,[role=button],[role=tab]')].filter(v).length; }, sel);
    await p.goto(URL + '#specimens'); await p.waitForTimeout(1500);

    // one section row: no pane switch, no machine tab row, no Hush, no Keys in the dock
    check(sz, 'no pane switch', !(await p.$('.paneswitch')));
    check(sz, 'no machine tab row', !(await p.$('.mtabs')));
    check(sz, 'no Hush button', !(await p.$('#hush')));
    check(sz, 'no dock Keys button', !(await p.$('#dockkeys')));
    check(sz, 'five sections in the dock', (await p.$$('.docknav .dn')).length === 5);
    const folded = await p.$eval('#dock', d => d.classList.contains('min'));
    check(sz, sz === 'phone' ? 'dock folded by default' : 'dock open by default', sz === 'phone' ? folded : !folded);
    if (sz === 'phone') { const h = await p.$eval('#dock', d => d.getBoundingClientRect().height); check(sz, 'folded dock is one row', h < 70, `${Math.round(h)} px`); await p.click('#dockmin'); }

    for (const [go, panel] of [['jars', '#pane-jars'], ['play', '#mp-pads'], ['steps', '#mp-steps'], ['roll', '#mp-roll'], ['song', '#mp-song']]) {
      await p.click(`.dn[data-go="${go}"]`); await p.waitForTimeout(350);
      check(sz, `section ${go} shows`, await vis(panel));
      check(sz, `section ${go} marked current`, await p.$eval(`.dn[data-go="${go}"]`, b => b.getAttribute('aria-current') === 'true'));
      if (go !== 'jars') {
        const n = await count('#seq'), grid = go === 'steps' ? await p.$$eval('#seqtable td button, #seqtable .rn', x => x.length) : go === 'roll' ? await p.$$eval('#roll button', x => x.length) : go === 'song' ? await p.$$eval('#mm-songlane button', x => x.length) : go === 'play' ? 16 : 0;
        const outside = n - grid; check(sz, `${go}: controls outside the playing surface <= 16`, outside <= 16, `${outside}`);
        check(sz, `${go}: loop letters ${go === 'play' ? 'hidden' : 'shown'}`, (await vis('#loopbar')) === (go !== 'play'));
      }
    }
    // loop tools only in Steps; 1, 2, 4, 8 bars; clear has an Undo
    await p.click('.dn[data-go="steps"]'); await p.waitForTimeout(200);
    check(sz, 'loop tools in Steps', await vis('#ltbtn'));
    await p.click('#ltbtn'); await p.waitForTimeout(150);
    for (const want of [32, 64, 128, 16]) { await p.click('#looplen'); await p.waitForTimeout(120); const len = await p.$$eval('#seqtable tr:first-child td button', x => x.length); check(sz, `loop length ${want / 16} bars`, len === want, `${len}`); }
    await p.keyboard.press('Escape'); await p.waitForTimeout(100);
    await p.click('#seqtable tr:first-child td:nth-child(2) button'); await p.waitForTimeout(100);
    if (!(await p.$eval('#seqtable tr:first-child td:nth-child(2) button', b => b.getAttribute('aria-pressed') === 'true'))) await p.click('#seqtable tr:first-child td:nth-child(2) button');
    if (!(await vis('#ltpop'))) await p.click('#ltbtn');
    await p.click('#seqclear'); await p.waitForTimeout(150);
    check(sz, 'clear loop offers Undo', await vis('#toast .tundo'));
    await p.click('#toast .tundo'); await p.waitForTimeout(150);
    check(sz, 'Undo brings the loop back', await p.$eval('#seqtable tr:first-child td:nth-child(2) button', b => b.getAttribute('aria-pressed') === 'true'));
    await p.click('.dn[data-go="roll"]'); await p.waitForTimeout(200);
    check(sz, 'loop tools not in Roll', !(await vis('#ltbtn')));

    // Play tab: Pads, Keys, Perform in one switch
    await p.click('.dn[data-go="play"]'); await p.waitForTimeout(200);
    await p.click('#pm-keys'); await p.waitForTimeout(150); check(sz, 'Keys mode', await p.$eval('#padgrid', g => g.classList.contains('keys')));
    await p.click('#pm-fx'); await p.waitForTimeout(150); check(sz, 'Perform shows', await vis('#mp-fx'));
    check(sz, 'Perform keeps the Play tab current', await p.$eval('.dn[data-go="play"]', b => b.getAttribute('aria-current') === 'true'));
    await p.click('#pm-pads'); await p.waitForTimeout(150);

    // hold a loaded pad: the pad menu
    const pad = p.locator('#padgrid .kpad:not(.empty)').first(); const bx = await pad.boundingBox();
    await p.mouse.move(bx.x + bx.width / 2, bx.y + bx.height / 2); await p.mouse.down(); await p.waitForTimeout(650); await p.mouse.up(); await p.waitForTimeout(150);
    check(sz, 'hold a pad opens its menu', await vis('#padmenu .pmacts'));
    await p.click('#padmenu [data-a="mute"]'); await p.waitForTimeout(150);
    await p.click('.dn[data-go="steps"]'); await p.waitForTimeout(200);
    check(sz, 'mute from the pad menu shows in Steps', await p.$$eval('#seqtable .rtag', x => x.length) === 1);
    await p.click('#seqtable .rn', { button: 'right' }); await p.waitForTimeout(150);
    check(sz, 'right-click a Steps name opens the pad menu', await vis('#padmenu .pmacts'));
    await p.keyboard.press('Escape');

    // transport: tempo pop, Stop twice hushes, help behind ?, Messes sheet
    if (sz === 'desktop' || !(await p.$eval('#dock', d => d.classList.contains('min')))) {
      await p.click('#tempobtn'); await p.waitForTimeout(100); check(sz, 'tempo pops up', await vis('#tempopop'));
      const before = await p.$eval('#tempolab', e => e.textContent); await p.click('#bpmup'); const after = await p.$eval('#tempolab', e => e.textContent);
      check(sz, 'tempo + moves the BPM', before !== after, `${before} -> ${after}`);
      await p.keyboard.press('Escape');
      await p.click('#seqplay'); await p.waitForTimeout(900); await p.click('#seqstop'); await p.waitForTimeout(100); await p.click('#seqstop'); await p.waitForTimeout(150);
      check(sz, 'Stop twice hushes', /Hushed/.test(await p.$eval('#toast', t => t.textContent)));
      await p.click('#mmhelpbtn'); await p.waitForTimeout(150);
      check(sz, '? shows this section\'s how-to', await p.$$eval('.mmhelp', ns => ns.filter(n => !n.hidden && n.offsetHeight > 0).length === 1));
      await p.click('#mmhelpbtn');
      await p.click('#mmmenubtn'); await p.waitForTimeout(100); await p.click('#mm-messes'); await p.waitForTimeout(150);
      check(sz, 'Messes sheet opens from the menu', await vis('#messes .messpanel'));
      await p.click('#messesx');
    }
    check(sz, 'no page errors', errs.length === 0, errs.join(' | '));
    } catch (e) { check(sz, 'flow finished', false, e.message.split('\n')[0]); }
    await p.screenshot({ path: `shots/${sz}-mm-end.png` });
    await ctx.close();
  }
  await b.close();
  console.log(fails ? `${fails} FAILED` : 'ALL PASS'); process.exit(fails ? 1 : 0);
})();
