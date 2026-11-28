// sweep for the Supabase dashboard's function editor: functions/sweep/index.ts with _shared/mm.ts pasted in, in one file.
// Made by tools/sql-all.sh; edit the originals, not this.
// What the three Mess Machine Edge Functions share: CORS, the error envelope, the service client, and
// "who is calling", which trusts only auth.getUser (Review A S6). All deciding is done by the svc_
// functions in SQL (server/supabase/migrations/20261124100800_mm_service.sql), where it is tested.
import { createClient, type SupabaseClient } from "jsr:@supabase/supabase-js@2";

const CORS: Record<string, string> = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Headers": "authorization, apikey, content-type, x-client-info, x-mm-sweep",
  "Access-Control-Allow-Methods": "POST, OPTIONS",
  "Access-Control-Max-Age": "86400",
};

function answer(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { ...CORS, "Content-Type": "application/json" },
  });
}

// The contract's envelope: HTTP 400 (401 for auth) and {"error": "<code>"}.
function fail(code: string): Response {
  return answer({ error: code }, code === "auth" ? 401 : 400);
}

function service(): SupabaseClient {
  const url = Deno.env.get("SUPABASE_URL") ?? "";
  // the secret key, under either name the platform gives it
  const key = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY") ?? Deno.env.get("MM_SECRET_KEY") ?? "";
  return createClient(url, key, { auth: { persistSession: false, autoRefreshToken: false } });
}

type Caller = { id: string; amr: unknown } | null;

// The caller, checked by the Auth server. null: no token came. "bad": a token came and it is not good.
async function caller(req: Request, db: SupabaseClient): Promise<Caller | "bad"> {
  const h = req.headers.get("Authorization") ?? "";
  const token = h.toLowerCase().startsWith("bearer ") ? h.slice(7).trim() : "";
  if (!token || token === req.headers.get("apikey")) return null;
  const { data, error } = await db.auth.getUser(token);
  if (error || !data?.user) return "bad";
  // getUser has just proved this exact token good, so reading its amr list from the payload is safe
  let amr: unknown = null;
  try {
    const part = token.split(".")[1].replace(/-/g, "+").replace(/_/g, "/");
    amr = JSON.parse(atob(part + "=".repeat((4 - (part.length % 4)) % 4))).amr ?? null;
  } catch {
    amr = null;
  }
  return { id: data.user.id, amr };
}

// Runs one svc_ function. A P0001 error comes back as its code; anything else as "http".
async function svc(db: SupabaseClient, fn: string, args: Record<string, unknown>) {
  const { data, error } = await db.rpc(fn, args);
  if (error) return { code: error.code === "P0001" ? error.message : "http", data: null };
  return { code: null, data };
}

// Removes objects in groups of 1000 (the Storage API's limit per call). Returns how many it removed.
async function removeAll(db: SupabaseClient, bucket: string, paths: string[]): Promise<number> {
  let n = 0;
  for (let i = 0; i < paths.length; i += 1000) {
    const part = paths.slice(i, i + 1000);
    const { error } = await db.storage.from(bucket).remove(part);
    if (error) throw new Error(`storage remove: ${error.message}`);
    n += part.length;
  }
  return n;
}

// sweep: the daily tidy-up (ACCOUNTS-API.md 1.8). Never called by an app. Needs the header
// x-mm-sweep: <the secret MM_SWEEP_SECRET>. Removes files nobody finished uploading in 24 hours, files
// being deleted, objects no row knows, and finishes account deletions that stopped half way.
// Answer: {removed: int, finished: int}.

function same(a: string, b: string): boolean {
  if (!a || !b || a.length !== b.length) return false;
  let d = 0;
  for (let i = 0; i < a.length; i++) d |= a.charCodeAt(i) ^ b.charCodeAt(i);
  return d === 0;
}

Deno.serve(async (req) => {
  if (req.method === "OPTIONS") return new Response("ok", { headers: CORS });
  if (!same(req.headers.get("x-mm-sweep") ?? "", Deno.env.get("MM_SWEEP_SECRET") ?? "")) return fail("auth");
  const db = service();

  const r = await svc(db, "svc_sweep", {});
  if (r.code) return fail(r.code);
  const plan = r.data as { remove: string[]; deletions: { user: string; paths: string[] }[] };
  let removed = await removeAll(db, "sounds", plan.remove);
  let finished = 0;
  for (const d of plan.deletions) {
    try {
      await svc(db, "svc_delete_account", { p_user: d.user, p_amr: null });   // rows again: safe to repeat
      removed += await removeAll(db, "sounds", d.paths);
      const { error } = await db.auth.admin.deleteUser(d.user);
      if (error && !/not.?found/i.test(error.message)) throw new Error(error.message);
      await svc(db, "svc_deletion_done", { p_user: d.user });
      finished++;
    } catch (_e) {
      // try again tomorrow
    }
  }
  await svc(db, "svc_sweep_done", {});
  return answer({ removed, finished });
});
