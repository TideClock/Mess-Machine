// The server tests. Makes a scratch database, loads the Supabase stand-in (stub.sql), loads the real
// migrations as a role that is not a superuser, runs every *.test.mjs here, and drops the database.
//
//   npm test                      every test file
//   npm test -- slice-a deny      only the files whose name holds one of these words
//   DATABASE_URL=postgres://user:password@host:port/postgres npm test     another Postgres (a superuser login)
//
// One line per check, PASS or FAIL, then a total. The exit code is 1 when anything failed.
import pg from 'pg';
import { readFileSync, readdirSync } from 'node:fs';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { dirname, join } from 'node:path';
import { Harness } from './lib.mjs';

const here = dirname(fileURLToPath(import.meta.url));
const migrationsDir = join(here, '..', 'supabase', 'migrations');
const adminUrl = process.env.DATABASE_URL || 'postgres://postgres:postgres@127.0.0.1:5432/postgres';
const dbName = `mm_test_${process.pid}_${Date.now().toString(36)}`;
const only = process.argv.slice(2);
const keep = process.env.MM_KEEP_DB === '1';

function urlFor(name) { const u = new URL(adminUrl); u.pathname = '/' + name; return u.toString(); }

async function main() {
  const admin = new pg.Client({ connectionString: adminUrl });
  await admin.connect();
  await admin.query(`create database ${dbName} template template0`);   // template0: never touched, also on Supabase's own Postgres
  let T;
  let crashed = null;
  try {
    // set up as Supabase would be: the stand-in first (superuser), then the migrations (not a superuser)
    const setup = new pg.Client({ connectionString: urlFor(dbName) });
    await setup.connect();
    await setup.query(readFileSync(join(here, 'stub.sql'), 'utf8'));
    const files = readdirSync(migrationsDir).filter((f) => f.endsWith('.sql')).sort();
    const superuser = (await setup.query(`select rolsuper from pg_roles where rolname = 'mm_stub_migrator'`)).rows[0].rolsuper;
    await setup.query('set role mm_stub_migrator');
    const loadErrors = [];
    for (const round of [1, 2]) {            // twice: every migration must be safe to run again
      for (const f of files) {
        try { await setup.query(readFileSync(join(migrationsDir, f), 'utf8')); }
        catch (e) { loadErrors.push(`round ${round} ${f}: ${e.message}${e.where ? ' @ ' + e.where.split('\n')[0] : ''}`); }
      }
    }
    await setup.query('reset role');
    await setup.end();

    T = new Harness(urlFor(dbName));
    T.files = files;
    T.section('set-up');
    T.check('the migrations ran as a role that is not a superuser', superuser === false);
    T.check(`all ${files.length} migrations load, twice over`, loadErrors.length === 0, loadErrors.join(' | '));
    if (loadErrors.length === 0) {
      const tests = readdirSync(here).filter((f) => f.endsWith('.test.mjs')).sort()
        .filter((f) => only.length === 0 || only.some((w) => f.includes(w)));
      for (const f of tests) {
        const mod = await import(pathToFileURL(join(here, f)).href);
        try { await mod.default(T); }
        catch (e) { T.section(f); T.check('the test file ran to its end', false, e.stack || String(e)); }
      }
    }
  } catch (e) {
    crashed = e;
  } finally {
    if (T) await T.end();
    if (!keep) await admin.query(`drop database if exists ${dbName} with (force)`).catch((e) => console.log('could not drop the scratch database: ' + e.message));
    await admin.end();
  }
  if (crashed) { console.log('\nThe test run crashed: ' + (crashed.stack || crashed)); process.exit(1); }
  console.log(`\nTOTAL ${T.pass + T.fail} checks: ${T.pass} PASS, ${T.fail} FAIL`);
  if (T.fail) { console.log('Failed:\n' + T.failed.map((x) => '  ' + x).join('\n')); process.exit(1); }
}

main();
