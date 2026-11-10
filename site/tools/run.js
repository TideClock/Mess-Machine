const { chromium } = require('playwright');
const fs = require('fs');
const VIEWS = ['home','shop','emporium','noise','brotherwife','internship','specimens','channel','arcade','ramblings','bio','attic','crawlspace'];
const SIZES = { phone: { width: 390, height: 844, isMobile: true, hasTouch: true }, desktop: { width: 1280, height: 900 } };
const URL = 'file://' + (process.env.NSD_PAGE || '/home/claude/site/live.html') + '';
(async () => {
  const b = await chromium.launch({ args: ['--autoplay-policy=no-user-gesture-required'] });
  const out = {};
  for (const [sz, vp] of Object.entries(SIZES)) {
    const ctx = await b.newContext({ viewport: { width: vp.width, height: vp.height }, isMobile: !!vp.isMobile, hasTouch: !!vp.hasTouch, deviceScaleFactor: 1 });
    for (const v of VIEWS) {
      const p = await ctx.newPage(); const errs = [];
      p.on('pageerror', e => errs.push('pageerror: ' + e.message));
      p.on('console', m => { if (m.type() === 'error') errs.push('console: ' + m.text().slice(0, 200)); });
      p.on('requestfailed', r => { const u = r.url(); if (!/fonts\.g/.test(u)) errs.push('failed: ' + u.replace('file:///home/claude/site/', '')); });
      const t0 = Date.now();
      await p.goto(URL + '#' + v, { waitUntil: 'load' }).catch(e => errs.push('goto ' + e.message));
      await p.waitForTimeout(1800);
      const m = await p.evaluate((v) => {
        const vh = innerHeight, vw = innerWidth;
        const sec = document.getElementById('v-' + v);
        const vis = el => { const cs = getComputedStyle(el); if (cs.display === 'none' || cs.visibility === 'hidden' || +cs.opacity === 0) return false; const r = el.getBoundingClientRect(); return r.width > 0 && r.height > 0; };
        const SEL = 'button, a[href], input:not([type=hidden]), select, textarea, [role=button], [role=tab]';
        const nm = el => (el.getAttribute('aria-label') || el.innerText || el.title || el.value || el.placeholder || '').trim().replace(/\s+/g, ' ').slice(0, 50);
        const all = [...document.querySelectorAll(SEL)].filter(vis);
        const inView = all.filter(el => sec && sec.contains(el));
        const header = all.filter(el => el.closest('header.site'));
        const fixed = all.filter(el => !sec.contains(el) && !el.closest('header.site') && !el.closest('footer'));
        const footer = all.filter(el => el.closest('footer'));
        const small = inView.filter(el => { const r = el.getBoundingClientRect(); return (r.width < 32 || r.height < 32); }).length;
        const hdr = document.querySelector('header.site'); const hr = hdr ? hdr.getBoundingClientRect() : { height: 0 };
        const overflow = document.documentElement.scrollWidth > vw + 1;
        const wide = sec ? [...sec.querySelectorAll('*')].filter(e => { const r = e.getBoundingClientRect(); return r.right > vw + 2 && r.width > 0 && getComputedStyle(e).position !== 'fixed' && !e.closest('[style*=overflow],.mtabs,.seqwrap,.rollwrap'); }).slice(0, 5).map(e => e.tagName + '.' + [...e.classList].join('.')) : [];
        const broken = [...document.images].filter(i => vis(i) && i.complete && i.naturalWidth === 0).map(i => i.getAttribute('src'));
        const helpText = sec ? [...sec.querySelectorAll('p,li')].filter(vis).filter(p => /\b(tap|hold|drag|press|click|double tap|pick)\b/i.test(p.innerText) && p.innerText.length > 40).length : 0;
        const words = sec ? sec.innerText.split(/\s+/).filter(Boolean).length : 0;
        const fonts = [...new Set([...document.querySelectorAll('h1,h2,h3,p,button,.label,.spec')].filter(vis).slice(0, 400).map(e => getComputedStyle(e).fontSize + ' ' + getComputedStyle(e).fontFamily.split(',')[0]))].length;
        const tip = document.getElementById('tourguide'); const tipOn = tip && vis(tip);
        return { pageH: document.documentElement.scrollHeight, screens: +(document.documentElement.scrollHeight / vh).toFixed(1), headerH: Math.round(hr.height), headerShare: +(hr.height / vh).toFixed(2), controls: inView.length, controlNames: [...new Set(inView.map(nm))], header: header.map(nm), floating: [...new Set(fixed.map(nm))].slice(0, 20), footer: footer.length, small, overflow, wide, broken, helpText, words, fontCombos: fonts, tipOn, active: document.querySelector('section.view.on')?.id };
      }, v);
      m.loadMs = Date.now() - t0; m.errors = [...new Set(errs)].slice(0, 12);
      await p.screenshot({ path: `shots/${sz}-${v}.png` });
      await p.screenshot({ path: `shots/${sz}-${v}-full.png`, fullPage: true }).catch(() => {});
      out[sz + ':' + v] = m;
      await p.close();
    }
    await ctx.close();
  }
  fs.writeFileSync('results.json', JSON.stringify(out, null, 1));
  for (const [k, m] of Object.entries(out)) console.log(k.padEnd(22), 'active', m.active, '| ctl', m.controls, 'small', m.small, '| scr', m.screens, '| hdr', m.headerH, m.headerShare, '| help', m.helpText, 'words', m.words, '| ovf', m.overflow, m.wide.join(' '), '| broken', m.broken.length, '| tip', m.tipOn, '| err', m.errors.length);
  await b.close();
})();
