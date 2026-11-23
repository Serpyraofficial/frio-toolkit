// serpyratoolkit.pages.dev/apk: sempre entrega a versão mais nova publicada, com o número da versão no nome do arquivo.
const SERVIDOR = "https://serpyratoolkit.officialcarloswendrik.workers.dev";
export async function onRequestGet({ request }) {
  const url = new URL(request.url);
  const r = await fetch(SERVIDOR + "/apk" + url.search, { headers: { "user-agent": "serpyratoolkit-site" } });
  const h = new Headers(r.headers);
  h.set("cache-control", "no-store");
  try {
    const v = await (await fetch(SERVIDOR + "/versao", { headers: { "user-agent": "serpyratoolkit-site" } })).json();
    if (r.ok && v.versionName) h.set("content-disposition", 'attachment; filename="SerpyraToolkit-' + String(v.versionName).replace(/[^0-9.]/g, "") + '.apk"');
  } catch (e) { }
  return new Response(r.body, { status: r.status, headers: h });
}
