/**
 * SerpyraToolkit Pro — servidor de vendas (Cloudflare Worker)
 *
 * O que faz:
 *  1. POST /pedido   -> cria a cobrança Pix de R$ 10 na PurinCash para um celular + e-mail
 *  2. POST /webhook  -> a PurinCash avisa que o Pix foi pago (assinatura HMAC conferida)
 *  3. GET  /status   -> o app pergunta se já pagou; se sim, recebe a chave
 *  Ao confirmar o pagamento: gera a chave de 30 dias (somando à validade atual), guarda,
 *  e manda o e-mail com o pedido, a confirmação e a chave (Brevo).
 *
 * Configuração no painel da Cloudflare (Settings > Variables and Secrets):
 *  PURIN_KEY             (secreto)  chave da API da PurinCash: ps_live_... (ou ps_test_... para testar)
 *  PURIN_WEBHOOK_SECRET  (secreto)  segredo do webhook da PurinCash (se a sua conta tiver)
 *  LICENCA_PRIV          (secreto)  conteúdo do arquivo chave-vendedor-PC-Toolkit.txt
 *  BREVO_KEY             (secreto)  chave da API do Brevo (xkeysib-...)
 *  REMETENTE_EMAIL       (texto)    e-mail verificado no Brevo que envia as mensagens
 *  REMETENTE_NOME        (texto)    ex.: SerpyraToolkit
 *  PRECO_CENTAVOS        (texto)    1000 = R$ 10,00
 * Binding KV com o nome PCT (Settings > Bindings > KV namespace).
 */

const API = "https://api.purin.cash/v1";
const DIA = 86400000;
const B32 = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";

const json = (o, status = 200) => new Response(JSON.stringify(o), {
  status, headers: { "content-type": "application/json; charset=utf-8", "access-control-allow-origin": "*" }
});

function preco(env) { const p = parseInt(env.PRECO_CENTAVOS || "1000", 10); return isNaN(p) ? 1000 : p; }
function reais(c) { return "R$ " + (c / 100).toFixed(2).replace(".", ","); }
function hoje() { return Math.floor(Date.now() / DIA); }
function dataBr(dia) { const d = new Date(dia * DIA); return String(d.getUTCDate()).padStart(2, "0") + "/" + String(d.getUTCMonth() + 1).padStart(2, "0") + "/" + d.getUTCFullYear(); }
function emailValido(e) { return typeof e === "string" && e.length <= 120 && /^[^\s@<>"]+@[^\s@<>"]+\.[^\s@<>"]{2,}$/.test(e); }
function codigoValido(c) { return typeof c === "string" && c.length === 8 && [...c].every(x => B32.includes(x)); }
function esc(s) { return String(s).replace(/[&<>"']/g, c => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c])); }

function b64ToBytes(b) { const s = atob(b.replace(/\s+/g, "")); const a = new Uint8Array(s.length); for (let i = 0; i < s.length; i++) a[i] = s.charCodeAt(i); return a; }
function b64url(bytes) { let s = ""; for (const x of bytes) s += String.fromCharCode(x); return btoa(s).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, ""); }
function hex(buf) { return [...new Uint8Array(buf)].map(b => b.toString(16).padStart(2, "0")).join(""); }

async function assinarChave(env, codigo, dia) {
  const key = await crypto.subtle.importKey("pkcs8", b64ToBytes(env.LICENCA_PRIV), { name: "ECDSA", namedCurve: "P-256" }, false, ["sign"]);
  const sig = new Uint8Array(await crypto.subtle.sign({ name: "ECDSA", hash: "SHA-256" }, key, new TextEncoder().encode(codigo + "." + dia)));
  return "PCT-" + codigo + "." + dia + "." + b64url(sig);
}

async function hmacValido(env, corpo, assinatura) {
  if (!env.PURIN_WEBHOOK_SECRET) return null;            // sem segredo configurado: confere pela API
  if (!assinatura) return false;
  const k = await crypto.subtle.importKey("raw", new TextEncoder().encode(env.PURIN_WEBHOOK_SECRET), { name: "HMAC", hash: "SHA-256" }, false, ["sign"]);
  const esperado = hex(await crypto.subtle.sign("HMAC", k, new TextEncoder().encode(corpo)));
  const a = assinatura.trim().toLowerCase().replace(/^sha256=/, "");
  if (a.length !== esperado.length) return false;
  let d = 0; for (let i = 0; i < a.length; i++) d |= a.charCodeAt(i) ^ esperado.charCodeAt(i);
  return d === 0;
}

async function purin(env, metodo, caminho, corpo) {
  const r = await fetch(API + caminho, {
    method: metodo,
    headers: { "authorization": "Bearer " + env.PURIN_KEY, "content-type": "application/json" },
    body: corpo ? JSON.stringify(corpo) : undefined
  });
  const t = await r.text();
  let o = {}; try { o = JSON.parse(t); } catch (e) { o = { error: t.slice(0, 200) }; }
  if (!r.ok) throw new Error("PurinCash " + r.status + ": " + (o.error || t.slice(0, 200)));
  return o;
}

async function enviarEmail(env, p) {
  if (!env.BREVO_KEY || !env.REMETENTE_EMAIL) return "sem configuração de e-mail";
  const nome = env.REMETENTE_NOME || "SerpyraToolkit";
  const html = `<!DOCTYPE html><html><body style="margin:0;background:#0A0E14;font-family:Arial,Helvetica,sans-serif;color:#E8EEF5">
<div style="max-width:560px;margin:0 auto;padding:28px 20px">
  <div style="font-size:22px;font-weight:bold;margin-bottom:4px">SerpyraToolkit Pro ativado ✅</div>
  <div style="color:#9AA8BA;margin-bottom:22px">Obrigado pela compra! Seu pagamento foi confirmado.</div>
  <div style="background:#121821;border:1px solid #263142;border-radius:14px;padding:18px;margin-bottom:16px">
    <div style="color:#9AA8BA;font-size:12px;text-transform:uppercase;letter-spacing:1px;margin-bottom:10px">Pedido</div>
    <table style="width:100%;font-size:14px;color:#E8EEF5" cellpadding="4">
      <tr><td style="color:#9AA8BA">Número</td><td align="right"><b>${esc(p.numero)}</b></td></tr>
      <tr><td style="color:#9AA8BA">Data</td><td align="right">${esc(p.dataPago)}</td></tr>
      <tr><td style="color:#9AA8BA">Produto</td><td align="right">SerpyraToolkit Pro · 1 mês</td></tr>
      <tr><td style="color:#9AA8BA">Valor</td><td align="right">${esc(p.valor)} (Pix)</td></tr>
      <tr><td style="color:#9AA8BA">Celular</td><td align="right">${esc(p.codigo.slice(0, 4) + "-" + p.codigo.slice(4))}</td></tr>
      <tr><td style="color:#9AA8BA">Válido até</td><td align="right"><b style="color:#3DDC97">${esc(p.validade)}</b></td></tr>
    </table>
  </div>
  <div style="background:#121821;border:1px solid #263142;border-radius:14px;padding:18px;margin-bottom:16px">
    <div style="color:#9AA8BA;font-size:12px;text-transform:uppercase;letter-spacing:1px;margin-bottom:10px">Sua chave de ativação</div>
    <div style="font-family:Consolas,Menlo,monospace;font-size:13px;word-break:break-all;background:#0A0E14;border:1px solid #263142;border-radius:10px;padding:12px;color:#3DDC97">${esc(p.chave)}</div>
    <div style="color:#9AA8BA;font-size:13px;margin-top:12px">Se o app estava aberto, o Pro já foi ativado sozinho. Se não, abra <b>SerpyraToolkit &gt; SerpyraToolkit Pro</b>, toque em <b>Colar</b> e depois em <b>Ativar</b>. A chave funciona só no celular deste pedido.</div>
  </div>
  <div style="color:#66758A;font-size:12px">Para renovar, gere um novo Pix pelo app antes de vencer: os dias se somam. Guarde este e-mail como comprovante.</div>
</div></body></html>`;
  const texto = `SerpyraToolkit Pro ativado!\n\nPedido: ${p.numero}\nData: ${p.dataPago}\nProduto: SerpyraToolkit Pro - 1 mês\nValor: ${p.valor} (Pix)\nVálido até: ${p.validade}\n\nSua chave de ativação:\n${p.chave}\n\nNo app: SerpyraToolkit Pro > Colar > Ativar.`;
  const r = await fetch("https://api.brevo.com/v3/smtp/email", {
    method: "POST",
    headers: { "api-key": env.BREVO_KEY, "content-type": "application/json", "accept": "application/json" },
    body: JSON.stringify({
      sender: { name: nome, email: env.REMETENTE_EMAIL },
      to: [{ email: p.email }],
      subject: `Pedido ${p.numero} confirmado: sua chave do SerpyraToolkit Pro`,
      htmlContent: html,
      textContent: texto
    })
  });
  return r.ok ? "enviado" : "falhou " + r.status + " " + (await r.text()).slice(0, 150);
}

/** Libera um pedido pago (uma vez só). Chamado pelo webhook ou pela consulta do app. */
async function liberar(env, id, valorPago) {
  const raw = await env.PCT.get("pedido:" + id);
  if (!raw) return null;
  const p = JSON.parse(raw);
  if (p.status === "pago") return p;
  if (valorPago != null && valorPago < p.valor) { p.obs = "valor menor que o cobrado: " + valorPago; await env.PCT.put("pedido:" + id, JSON.stringify(p)); return p; }
  const licAtual = parseInt((await env.PCT.get("lic:" + p.codigo)) || "0", 10);
  const dia = Math.max(hoje(), p.atual || 0, licAtual || 0) + 30;
  p.chave = await assinarChave(env, p.codigo, dia);
  p.dia = dia;
  p.status = "pago";
  p.pagoEm = new Date().toISOString();
  await env.PCT.put("lic:" + p.codigo, String(dia));
  await env.PCT.put("chave:" + p.codigo, JSON.stringify({ chave: p.chave, dia }));
  await env.PCT.put("pedido:" + id, JSON.stringify(p));
  const agora = new Date(Date.now() - 3 * 3600000); // horário de Brasília
  p.email_status = await enviarEmail(env, {
    numero: p.numero, email: p.email, codigo: p.codigo, chave: p.chave, valor: reais(p.valor),
    validade: dataBr(dia),
    dataPago: String(agora.getUTCDate()).padStart(2, "0") + "/" + String(agora.getUTCMonth() + 1).padStart(2, "0") + "/" + agora.getUTCFullYear() + " " + String(agora.getUTCHours()).padStart(2, "0") + ":" + String(agora.getUTCMinutes()).padStart(2, "0")
  });
  await env.PCT.put("pedido:" + id, JSON.stringify(p));
  return p;
}

async function criarPedido(req, env, base) {
  let b; try { b = await req.json(); } catch (e) { return json({ erro: "Pedido inválido." }, 400); }
  const codigo = String(b.codigo || "").toUpperCase().replace(/[^A-Z0-9]/g, "");
  const email = String(b.email || "").trim().toLowerCase();
  const atual = parseInt(b.atual || "0", 10) || 0;
  if (!codigoValido(codigo)) return json({ erro: "Código do celular inválido." }, 400);
  if (!emailValido(email)) return json({ erro: "E-mail inválido." }, 400);
  // limite simples: no máximo 5 Pix por celular por hora
  const lk = "lim:" + codigo + ":" + Math.floor(Date.now() / 3600000);
  const n = parseInt((await env.PCT.get(lk)) || "0", 10);
  if (n >= 5) return json({ erro: "Muitos pedidos seguidos. Tente de novo em alguns minutos." }, 429);
  await env.PCT.put(lk, String(n + 1), { expirationTtl: 3700 });
  const valor = preco(env);
  const numero = "PCT-" + Date.now().toString(36).toUpperCase().slice(-6);
  let r;
  try {
    r = await purin(env, "POST", "/payments", {
      valueCents: valor,
      description: "SerpyraToolkit Pro - 1 mês (" + numero + ")",
      callbackUrl: base + "/webhook",
      customer: { email, externalId: codigo },
      metadata: JSON.stringify({ numero, codigo, atual, email })
    });
  } catch (e) { return json({ erro: "Não consegui gerar o Pix agora. " + e.message }, 502); }
  const id = r.paymentId;
  await env.PCT.put("pedido:" + id, JSON.stringify({ id, numero, codigo, email, atual, valor, status: "pendente", criado: new Date().toISOString() }), { expirationTtl: 60 * 60 * 24 * 400 });
  return json({ pedido: id, numero, valor: reais(valor), brCode: r.pix && r.pix.brCode, qr: r.pix && r.pix.qrCodeImage, expira: r.expiresAt, teste: String(env.PURIN_KEY || "").startsWith("ps_test_") });
}

async function status(url, env) {
  const id = url.searchParams.get("pedido") || "";
  if (!/^ps[a-z]?_[A-Za-z0-9]+$/.test(id)) return json({ erro: "Pedido inválido." }, 400);
  const raw = await env.PCT.get("pedido:" + id);
  if (!raw) return json({ status: "desconhecido" }, 404);
  let p = JSON.parse(raw);
  if (p.status !== "pago") {
    // confere direto na PurinCash (funciona mesmo se o webhook atrasar ou não estiver configurado)
    try {
      const r = await purin(env, "GET", "/payments/" + id);
      if (r.status === "paid") p = (await liberar(env, id, r.amountCents != null ? r.amountCents : null)) || p;
      else if (r.status && r.status !== "pending") return json({ status: r.status });
    } catch (e) { /* tenta de novo na próxima consulta */ }
  }
  if (p.status === "pago") return json({ status: "pago", chave: p.chave, numero: p.numero, validade: dataBr(p.dia), email: p.email_status === "enviado" });
  return json({ status: "pendente" });
}

async function webhook(req, env) {
  const corpo = await req.text();
  const ok = await hmacValido(env, corpo, req.headers.get("x-webhook-signature"));
  if (ok === false) return new Response("assinatura inválida", { status: 401 });
  let ev; try { ev = JSON.parse(corpo); } catch (e) { return new Response("ok"); }
  const wid = req.headers.get("x-webhook-id") || "";
  if (wid) { if (await env.PCT.get("wh:" + wid)) return new Response("ok"); await env.PCT.put("wh:" + wid, "1", { expirationTtl: 60 * 60 * 24 * 3 }); }
  if (ev.event !== "payment.paid" || !ev.paymentId) return new Response("ok");
  if (ok === null) {
    // sem segredo de webhook: só libera depois de confirmar na própria PurinCash
    try { const r = await purin(env, "GET", "/payments/" + ev.paymentId); if (r.status !== "paid") return new Response("ok"); }
    catch (e) { return new Response("tentar depois", { status: 500 }); }
  }
  await liberar(env, ev.paymentId, ev.amountCents != null ? ev.amountCents : null);
  return new Response("ok");
}

/** Devolve a chave mais recente deste celular (reinstalou o app). A chave só funciona no próprio aparelho. */
async function licenca(url, env) {
  const codigo = String(url.searchParams.get("codigo") || "").toUpperCase().replace(/[^A-Z0-9]/g, "");
  if (!codigoValido(codigo)) return json({ erro: "Código inválido." }, 400);
  const raw = await env.PCT.get("chave:" + codigo);
  if (!raw) return json({ status: "nenhuma" }, 404);
  const o = JSON.parse(raw);
  if (o.dia < hoje()) return json({ status: "vencida", validade: dataBr(o.dia) }, 404);
  return json({ status: "ativa", chave: o.chave, validade: dataBr(o.dia) });
}

/* ---------- atualização do app: o APK fica no GitHub; GITHUB_TOKEN só é preciso se o repositório for privado ---------- */
const APK_RAW = "https://raw.githubusercontent.com/Serpyraofficial/frio-toolkit/android-app/apk/";
function github(env, arquivo) {
  const h = { "user-agent": "serpyratoolkit" };
  if (env.GITHUB_TOKEN) h.authorization = "token " + env.GITHUB_TOKEN;
  return fetch(APK_RAW + arquivo + "?v=" + Math.floor(Date.now() / 120000), { headers: h });
}
async function versao(env) {
  const r = await github(env, "versao.json");
  if (!r.ok) return json({ erro: "Versão indisponível (" + r.status + ")." }, 502);
  return new Response(await r.text(), { headers: { "content-type": "application/json; charset=utf-8", "access-control-allow-origin": "*", "cache-control": "no-store" } });
}
async function baixarApk(env) {
  const r = await github(env, "SerpyraToolkit.apk");
  if (!r.ok) return json({ erro: "APK indisponível (" + r.status + ")." }, 502);
  const h = { "content-type": "application/vnd.android.package-archive", "content-disposition": "attachment; filename=SerpyraToolkit.apk", "cache-control": "no-store" };
  const n = r.headers.get("content-length"); if (n) h["content-length"] = n;
  return new Response(r.body, { headers: h });
}

/** Só no modo de teste (chave ps_test_): simula o pagamento de um pedido. */
async function simular(url, env) {
  if (!String(env.PURIN_KEY || "").startsWith("ps_test_")) return json({ erro: "Só funciona com a chave de teste ps_test_." }, 403);
  const id = url.searchParams.get("pedido") || "";
  try { await purin(env, "POST", "/sandbox/payments/" + encodeURIComponent(id) + "/simulate-paid"); } catch (e) { return json({ erro: e.message }, 502); }
  return json({ ok: true });
}

export default {
  async fetch(req, env) {
    const url = new URL(req.url);
    const base = url.origin;
    if (req.method === "OPTIONS") return new Response(null, { headers: { "access-control-allow-origin": "*", "access-control-allow-headers": "content-type", "access-control-allow-methods": "GET,POST" } });
    try {
      if (req.method === "POST" && url.pathname === "/pedido") return await criarPedido(req, env, base);
      if (req.method === "GET" && url.pathname === "/status") return await status(url, env);
      if (req.method === "POST" && url.pathname === "/webhook") return await webhook(req, env);
      if (req.method === "POST" && url.pathname === "/simular") return await simular(url, env);
      if (req.method === "GET" && url.pathname === "/licenca") return await licenca(url, env);
      if (req.method === "GET" && url.pathname === "/versao") return await versao(env);
      if (req.method === "GET" && url.pathname === "/apk") return await baixarApk(env);
      if (url.pathname === "/") {
        const faltando = ["PURIN_KEY", "LICENCA_PRIV", "BREVO_KEY", "REMETENTE_EMAIL"].filter(k => !env[k]);
        return json({ servico: "SerpyraToolkit Pro", ok: faltando.length === 0 && !!env.PCT, faltando: env.PCT ? faltando : faltando.concat(["KV PCT"]), modo: String(env.PURIN_KEY || "").startsWith("ps_test_") ? "teste" : "produção", preco: reais(preco(env)) });
      }
      return json({ erro: "Não encontrado" }, 404);
    } catch (e) {
      return json({ erro: "Erro interno: " + e.message }, 500);
    }
  }
};
