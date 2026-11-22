// serpyratoolkit.pages.dev repassa este endereço para o servidor de vendas (Cloudflare Worker).
const SERVIDOR = "https://serpyratoolkit.officialcarloswendrik.workers.dev";
export async function onRequestGet({ request }) {
  const url = new URL(request.url);
  const r = await fetch(SERVIDOR + "/apk" + url.search, { headers: { "user-agent": "serpyratoolkit-site" } });
  return new Response(r.body, { status: r.status, headers: r.headers });
}
