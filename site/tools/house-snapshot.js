// Renders the house once (day, fresh visitor) and saves its markup, so build.py can ship it ready-made in the page
// (NSD-44): a phone on a slow line sees the house as soon as the HTML arrives, before the script runs.
const { chromium } = require('playwright');
const fs = require('fs'), path = require('path');
const here = path.join(__dirname, '..');
(async () => {
  const b = await chromium.launch();
  const p = await b.newPage({ viewport: { width: 390, height: 844 } });
  await p.addInitScript(() => { try { localStorage.clear(); localStorage.setItem('nsd.lights', '"day"'); } catch (e) {} });
  await p.goto('file://' + path.join(here, 'index.html') + '#home');
  await p.waitForFunction(() => document.querySelector('#house .room'));
  const html = await p.evaluate(() => document.getElementById('house').innerHTML.replace(/\s+\n/g, '\n').trim());
  fs.writeFileSync(path.join(here, 'house.snapshot.html'), html + '\n');
  console.log('house snapshot', html.length, 'bytes');
  await b.close();
})();
