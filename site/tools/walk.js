// Walk mode checks: every floor builds, every spawn is free, feel numbers, roll breaks a crate, cans move.
// NSD_PAGE=/path/to/live.html node walk.js   (screens of each floor go to shots/walk-*.png)
const { chromium } = require('playwright');
const URL = 'file://' + (process.env.NSD_PAGE || '/home/claude/site/live.html');
let fails = 0;
const check = (name, ok, info = '') => { if (!ok) fails++; console.log(`${ok ? 'PASS' : 'FAIL'} ${name}${info ? '  ' + info : ''}`); };
(async () => {
  const b = await chromium.launch({ args: ['--autoplay-policy=no-user-gesture-required'] });
  for (const [sz, vp] of [['desktop', { width: 1280, height: 800 }], ['phone', { width: 390, height: 844 }]]) {
    const ctx = await b.newContext({ viewport: vp, hasTouch: sz === 'phone', isMobile: sz === 'phone' });
    const p = await ctx.newPage(); const errs = []; p.on('pageerror', e => errs.push(e.message)); p.setDefaultTimeout(8000);
    await p.goto(URL + '#home'); await p.waitForTimeout(1200);
    await p.click('#walkbtn'); await p.waitForTimeout(900);
    const W = await p.evaluate(() => { const w = window.__walk, {WFLOORS, W_SPAWN, WT, wBuild, wSolidAt, wFreeNear} = w;
      const out = {};
      for (const f of Object.keys(WFLOORS)){ w.floor = f; wBuild(); const wF = w.wF;
        const sp = WFLOORS[f].spawn, free = !wSolidAt((sp[0] + .5) * WT, (sp[1] + .5) * WT, 12);
        /* every room reachable from the spawn */
        const B = wF.block, seen = new Set(), q = [[sp[0], sp[1]]]; seen.add(sp[1] * 999 + sp[0]);
        while (q.length){ const [x, y] = q.shift(); for (const [dx, dy] of [[1,0],[-1,0],[0,1],[0,-1]]){ const nx = x + dx, ny = y + dy; if (!B[ny] || B[ny][nx] !== false || seen.has(ny * 999 + nx)) continue; seen.add(ny * 999 + nx); q.push([nx, ny]); } }
        const unreached = wF.rooms.filter(r => { for (let y = r.y; y < r.y + r.h; y++) for (let x = r.x; x < r.x + r.w; x++) if (seen.has(y * 999 + x)) return false; return true; }).map(r => r.id);
        /* everything you can look at has a free tile next to it */
        const stuckLooks = wF.objs.filter(o => o.look && !wFreeNear(o)).map(o => o.label || o.kind);
        const outside = wF.objs.filter(o => o.x < 0 || o.y < 0 || o.x + o.w > WFLOORS[f].w || o.y + o.h > WFLOORS[f].h).map(o => o.kind);
        out[f] = {free, unreached, stuckLooks, outside, objs:wF.objs.length};
      }
      const spawns = Object.entries(W_SPAWN).filter(([v]) => v !== 'crawlspace' && v !== 'attic').filter(([v, [f, x, y]]) => { w.floor = f; wBuild(); return wSolidAt(x * WT, y * WT, 12); }).map(([v]) => v);
      return {out, spawns};
    });
    if (sz === 'desktop') {
      for (const [f, r] of Object.entries(W.out)) {
        check(`${f}: spawn is free`, r.free);
        check(`${f}: every room reachable`, !r.unreached.length || (f === 'base' && r.unreached.join() === 'crawlspace'), r.unreached.join(', '));
        check(`${f}: everything you can look at can be reached`, !r.stuckLooks.length, r.stuckLooks.join(', '));
        check(`${f}: nothing outside the floor`, !r.outside.length, r.outside.join(', '));
      }
      check('every page spawn is free', !W.spawns.length, W.spawns.join(', '));
    }
    // feel: time to full speed, distance to stop, top speed, roll distance (tiles)
    await p.evaluate(() => { const w = window.__walk, wP = w.wP, WT = w.WT; w.floor = 'main'; w.wBuild(); wP.x = 4 * WT; wP.y = 9.5 * WT; wP.vx = wP.vy = 0; w.cam = null; });
    const feel = await p.evaluate(async () => { const w = window.__walk, wP = w.wP, WT = w.WT, wMove = w.wMove, wRoll = w.wRoll;
      const dt = 1 / 60, res = {}; w.keys.clear();
      let t = 0; while (Math.hypot(wP.vx, wP.vy) < 5.3 * WT * .9 && t < 2){ wMove(1, 0, dt); t += dt; } res.toSpeed = +(t * 1000).toFixed(0);
      for (let i = 0; i < 30; i++) wMove(1, 0, dt); res.top = +(Math.hypot(wP.vx, wP.vy) / WT).toFixed(2);
      const x0 = wP.x; t = 0; while (Math.hypot(wP.vx, wP.vy) > 0 && t < 2){ wMove(0, 0, dt); t += dt; } res.stopMs = +(t * 1000).toFixed(0); res.stopTiles = +((wP.x - x0) / WT).toFixed(2);
      wP.x = 4 * WT; wP.y = 9.5 * WT; wP.fx = 1; wP.fy = 0; wP.rollCD = 0; wRoll(); const r0 = wP.x; for (let i = 0; i < 40; i++) wMove(0, 0, dt); res.rollTiles = +((wP.x - r0) / WT).toFixed(2);
      return res; });
    if (sz === 'desktop') {
      check('reaches walking speed in under 200 ms', feel.toSpeed < 200, `${feel.toSpeed} ms`);
      check('top walking speed 5 to 6 tiles a second', feel.top >= 5 && feel.top <= 6, `${feel.top}`);
      check('stops in under 250 ms', feel.stopMs < 250, `${feel.stopMs} ms, ${feel.stopTiles} tiles`);
      check('roll covers 2 to 3.5 tiles', feel.rollTiles >= 2 && feel.rollTiles <= 3.5, `${feel.rollTiles}`);
      // roll into a crate breaks it
      const broke = await p.evaluate(() => { const w = window.__walk, wP = w.wP, WT = w.WT, wMove = w.wMove, wRoll = w.wRoll, wSolidAt = w.wSolidAt; w.floor = 'main'; w.broken = new Set(); w.wBuild(); const wF = w.wF; const o = wF.objs.find(o => o.brk && o.kind === 'crate'); const n0 = wF.objs.filter(o => o.brk).length;
        wP.x = (o.x - 1.2) * WT; wP.y = (o.y + o.h / 2) * WT; wP.fx = 1; wP.fy = 0; wP.rollCD = 0; wP.vx = wP.vy = 0;
        if (wSolidAt(wP.x, wP.y, 12)) { wP.x = (o.x + o.w / 2) * WT; wP.y = (o.y - .9) * WT; wP.fx = 0; wP.fy = 1; }
        wRoll(); for (let i = 0; i < 30; i++) wMove(0, 0, 1 / 60); return {before:n0, after:w.wF.objs.filter(o => o.brk).length, bits:w.bits.length}; });
      check('rolling into a crate breaks it', broke.after < broke.before && broke.bits > 0, JSON.stringify(broke));
      const can = await p.evaluate(() => { const w = window.__walk, wP = w.wP, WT = w.WT, wMove = w.wMove; w.floor = 'main'; w.wBuild(); const k = w.wF.cans[0]; const x0 = k.x; wP.x = k.x - 30; wP.y = k.y - 6; wP.vx = wP.vy = 0; for (let i = 0; i < 40; i++) wMove(1, 0, 1 / 60); return +((k.x - x0) / WT).toFixed(2); });
      check('walking into a can kicks it', can > .5, `${can} tiles`);
    }
    // screens of every floor, from the middle
    for (const f of ['main', 'up', 'base', 'attic']) {
      await p.evaluate(f => { const w = window.__walk; w.wGoFloor(f, ...w.WFLOORS[f].spawn.map(v => v + .5)); w.fade = 0; }, f); await p.waitForTimeout(500);
      await p.screenshot({ path: `shots/walk-${sz}-${f}.png` });
    }
    // keyboard: hold right for half a second, you move
    await p.evaluate(() => { const w = window.__walk; w.wGoFloor('main', 9.5, 9.5); w.fade = 0; });
    const x0 = await p.evaluate(() => window.__walk.wP.x); await p.keyboard.down('d'); await p.waitForTimeout(500); await p.keyboard.up('d'); await p.waitForTimeout(300);
    const x1 = await p.evaluate(() => window.__walk.wP.x); check(`${sz}: D walks right`, x1 - x0 > 60, `${Math.round(x1 - x0)} px`);
    check(`${sz}: no page errors`, !errs.length, errs.join(' | '));
    await ctx.close();
  }
  await b.close(); console.log(fails ? `${fails} FAILED` : 'ALL PASS'); process.exit(fails ? 1 : 0);
})();
