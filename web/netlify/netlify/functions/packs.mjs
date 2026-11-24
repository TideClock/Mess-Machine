// Sound packs live on the GitHub release "packs" of hubbub0/Mess-Machine. A browser cannot fetch release files
// across origins, so this passes them through (following GitHub's redirect to its file storage).
export default async (req) => {
  const file = new URL(req.url).searchParams.get('f') || 'packs.json';
  if (!/^[\w.-]+$/.test(file)) return new Response('bad name', { status: 400 });
  const r = await fetch(`https://github.com/hubbub0/Mess-Machine/releases/download/packs/${file}`, { redirect: 'follow', headers: { 'User-Agent': 'MessMachine-Web' } });
  return new Response(r.body, { status: r.status, headers: { 'content-type': r.headers.get('content-type') || 'application/octet-stream', 'cache-control': 'public, max-age=300' } });
};
export const config = { path: '/packs' };
