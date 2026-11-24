// sweep: the daily tidy-up (ACCOUNTS-API.md 1.8). Never called by an app. Needs the header
// x-mm-sweep: <the secret MM_SWEEP_SECRET>. Removes files nobody finished uploading in 24 hours, files
// being deleted, objects no row knows, and finishes account deletions that stopped half way.
// Answer: {removed: int, finished: int}.
import { answer, CORS, fail, removeAll, service, svc } from "../_shared/mm.ts";

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
