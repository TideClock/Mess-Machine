// sound-urls: 10 minute download links for the sounds of one mess (ACCOUNTS-API.md 1.4).
// Body is exactly one of {"mess": id} (a member), {"token": share link} (anyone, counted per link per day),
// {"report": id} (an admin, open report), {"submission": id} (an admin, pending Vault submission).
// Answer: {sounds: [{key, sha256, bytes, url}], expires_in: 600}.
import { answer, caller, CORS, fail, service, svc } from "../_shared/mm.ts";

const LIFE = 600;

Deno.serve(async (req) => {
  if (req.method === "OPTIONS") return new Response("ok", { headers: CORS });
  if (req.method !== "POST") return fail("bad_input");
  let body: unknown;
  try {
    body = await req.json();
  } catch {
    return fail("bad_input");
  }
  const db = service();
  const who = await caller(req, db);
  if (who === "bad") return fail("auth");

  const r = await svc(db, "svc_sound_urls", { p_user: who?.id ?? null, p_amr: who?.amr ?? null, p_body: body });
  if (r.code) return fail(r.code);
  const list = (r.data as { sounds: { key: string; sha256: string; bytes: number; bucket: string; path: string }[] }).sounds;

  const out: { key: string; sha256: string; bytes: number; url: string }[] = [];
  for (const bucket of ["sounds", "vault"]) {
    const mine = list.filter((s) => s.bucket === bucket);
    if (mine.length === 0) continue;
    const { data, error } = await db.storage.from(bucket).createSignedUrls(mine.map((s) => s.path), LIFE);
    if (error || !data) return fail("http");
    mine.forEach((s, i) => {
      const u = data[i]?.signedUrl;
      // a file the database lists but Storage does not have is left out; the pad reads Missing
      if (u) out.push({ key: s.key, sha256: s.sha256, bytes: s.bytes, url: u });
    });
  }
  return answer({ sounds: out, expires_in: LIFE });
});
