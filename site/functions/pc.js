// serpyratoolkit.pages.dev/pc: entrega o SerpyraToolkit para PC (Windows) mais novo.
const SERVIDOR = "https://serpyratoolkit.officialcarloswendrik.workers.dev";
export async function onRequestGet({ request }) {
  const url = new URL(request.url);
  const r = await fetch(SERVIDOR + "/pc" + url.search, { headers: { "user-agent": "serpyratoolkit-site" } });
  const h = new Headers(r.headers);
  h.set("cache-control", "no-store");
  return new Response(r.body, { status: r.status, headers: h });
}
