// delete-account: deletes the caller's account and everything in it (ACCOUNTS-API.md 1.8). Body {}.
// Refuses with stale_login unless the sign-in is under 10 minutes old, so deletion always follows a
// fresh code. Order: rows (one transaction, noted in mm_private.deletions), then files, then the sign-in.
// If a later step fails, the daily sweep finishes it.
import { answer, caller, CORS, fail, removeAll, service, svc } from "../_shared/mm.ts";

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
