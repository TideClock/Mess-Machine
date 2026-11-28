// delete-account for the Supabase dashboard's function editor: functions/delete-account/index.ts with _shared/mm.ts pasted in, in one file.
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

// delete-account: deletes the caller's account and everything in it (ACCOUNTS-API.md 1.8). Body {}.
// Refuses with stale_login unless the sign-in is under 10 minutes old, so deletion always follows a
// fresh code. Order: rows (one transaction, noted in mm_private.deletions), then files, then the sign-in.
// If a later step fails, the daily sweep finishes it.

Deno.serve(async (req) => {
  if (req.method === "OPTIONS") return new Response("ok", { headers: CORS });
  if (req.method !== "POST") return fail("bad_input");
  const db = service();
  const who = await caller(req, db);
  if (who === null || who === "bad") return fail("auth");

  // the rows; this also checks the fresh sign-in, and refuses a password session (banned people may delete)
  const r = await svc(db, "svc_delete_account", { p_user: who.id, p_amr: who.amr ?? [] });
  if (r.code) return fail(r.code);
  const paths = (r.data as { paths: string[] }).paths;
  try {
    await removeAll(db, "sounds", paths);
    const { error } = await db.auth.admin.deleteUser(who.id);
    if (error) throw new Error(`deleteUser: ${error.message}`);
    await svc(db, "svc_deletion_done", { p_user: who.id });
  } catch (_e) {
    // the data is already gone and the deletion is on record; the sweep finishes the rest
    return answer({ ok: true });
  }
  return answer({ ok: true });
});
