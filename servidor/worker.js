/**
 * SerpyraToolkit Pro — servidor de vendas (Cloudflare Worker)
 *
 * App:    GET /planos · GET /cupom · POST /pedido · GET /status · POST /teste · GET /licenca
 *         POST /troca/pedir · POST /troca/confirmar · GET /versao · GET /apk
 * Pix:    POST /webhook (PurinCash) · POST /simular (só no modo de teste)
 * Painel: /admin/* com o cabeçalho x-admin = ADMIN_TOKEN
 * Site:   GET / (site/index.html do GitHub) · GET /saude
 * Agendado (Cron, 1x por dia): reenvia os e-mails que falharam (limite diário do Brevo).
 *
 * Segredos/variáveis: PURIN_KEY, PURIN_WEBHOOK_SECRET (opcional), LICENCA_PRIV, BREVO_KEY,
 * REMETENTE_EMAIL, REMETENTE_NOME (opcional), ADMIN_TOKEN, GITHUB_TOKEN (só com o repositório privado).
 * Binding KV: PCT.
 *
 * Gravações no KV por venda: pedido (1) + pagamento: pedido, chave e e-mail (3) = 4.
 */

const API = "https://api.purin.cash/v1";
const DIA = 86400000;
const B32 = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
const TESTE_DIAS = 3;
const TROCA_INTERVALO = 30;
const PLANOS = {
  m1: { nome: "1 mês", dias: 30, centavos: 1500 },
  m3: { nome: "3 meses", dias: 90, centavos: 3900 },
  m6: { nome: "6 meses", dias: 180, centavos: 6900 }
};
const CORS = { "access-control-allow-origin": "*", "access-control-allow-headers": "content-type, x-admin", "access-control-allow-methods": "GET,POST,DELETE" };

const json = (o, status = 200) => new Response(JSON.stringify(o), { status, headers: { "content-type": "application/json; charset=utf-8", ...CORS } });
function reais(c) { return "R$ " + (c / 100).toFixed(2).replace(".", ","); }
function hoje() { return Math.floor(Date.now() / DIA); }
function dataBr(dia) { const d = new Date(dia * DIA); return String(d.getUTCDate()).padStart(2, "0") + "/" + String(d.getUTCMonth() + 1).padStart(2, "0") + "/" + d.getUTCFullYear(); }
function agoraBr() { const a = new Date(Date.now() - 3 * 3600000); return String(a.getUTCDate()).padStart(2, "0") + "/" + String(a.getUTCMonth() + 1).padStart(2, "0") + "/" + a.getUTCFullYear() + " " + String(a.getUTCHours()).padStart(2, "0") + ":" + String(a.getUTCMinutes()).padStart(2, "0"); }
function emailValido(e) { return typeof e === "string" && e.length <= 120 && /^[^\s@<>"]+@[^\s@<>"]+\.[^\s@<>"]{2,}$/.test(e); }
function codigoValido(c) { return typeof c === "string" && c.length === 8 && [...c].every(x => B32.includes(x)); }
function limpaCodigo(c) { return String(c || "").toUpperCase().replace(/[^A-Z0-9]/g, ""); }
function limpaCupom(c) { return String(c || "").toUpperCase().replace(/[^A-Z0-9]/g, "").slice(0, 20); }
function esc(s) { return String(s).replace(/[&<>"']/g, c => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c])); }
function b64ToBytes(b) { const s = atob(b.replace(/\s+/g, "")); const a = new Uint8Array(s.length); for (let i = 0; i < s.length; i++) a[i] = s.charCodeAt(i); return a; }
function b64url(bytes) { let s = ""; for (const x of bytes) s += String.fromCharCode(x); return btoa(s).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, ""); }
function hex(buf) { return [...new Uint8Array(buf)].map(b => b.toString(16).padStart(2, "0")).join(""); }
function igual(a, b) { a = String(a || ""); b = String(b || ""); if (!a || a.length !== b.length) return false; let d = 0; for (let i = 0; i < a.length; i++) d |= a.charCodeAt(i) ^ b.charCodeAt(i); return d === 0; }
async function lerJson(env, k) { const r = await env.PCT.get(k); if (!r) return null; try { return JSON.parse(r); } catch (e) { return null; } }

async function assinarChave(env, codigo, dia) {
  const key = await crypto.subtle.importKey("pkcs8", b64ToBytes(env.LICENCA_PRIV), { name: "ECDSA", namedCurve: "P-256" }, false, ["sign"]);
  const sig = new Uint8Array(await crypto.subtle.sign({ name: "ECDSA", hash: "SHA-256" }, key, new TextEncoder().encode(codigo + "." + dia)));
  return "PCT-" + codigo + "." + dia + "." + b64url(sig);
}

async function hmacValido(env, corpo, assinatura) {
  if (!env.PURIN_WEBHOOK_SECRET) return null;
  if (!assinatura) return false;
  const k = await crypto.subtle.importKey("raw", new TextEncoder().encode(env.PURIN_WEBHOOK_SECRET), { name: "HMAC", hash: "SHA-256" }, false, ["sign"]);
  const esperado = hex(await crypto.subtle.sign("HMAC", k, new TextEncoder().encode(corpo)));
  return igual(assinatura.trim().toLowerCase().replace(/^sha256=/, ""), esperado);
}

async function purin(env, metodo, caminho, corpo) {
  const r = await fetch(API + caminho, { method: metodo, headers: { "authorization": "Bearer " + env.PURIN_KEY, "content-type": "application/json" }, body: corpo ? JSON.stringify(corpo) : undefined });
  const t = await r.text();
  let o = {}; try { o = JSON.parse(t); } catch (e) { o = { error: t.slice(0, 200) }; }
  if (!r.ok) throw new Error("PurinCash " + r.status + ": " + (o.error || t.slice(0, 200)));
  return o;
}

/* ---------------- e-mail ---------------- */
async function mandarEmail(env, para, assunto, html, texto) {
  if (!env.BREVO_KEY || !env.REMETENTE_EMAIL) return "sem configuração de e-mail";
  const r = await fetch("https://api.brevo.com/v3/smtp/email", {
    method: "POST",
    headers: { "api-key": env.BREVO_KEY, "content-type": "application/json", "accept": "application/json" },
    body: JSON.stringify({ sender: { name: env.REMETENTE_NOME || "SerpyraToolkit", email: env.REMETENTE_EMAIL }, to: [{ email: para }], subject: assunto, htmlContent: html, textContent: texto })
  });
  return r.ok ? "enviado" : "falhou " + r.status + " " + (await r.text()).slice(0, 150);
}

function moldura(titulo, sub, corpo) {
  return `<!DOCTYPE html><html><body style="margin:0;background:#0A0E14;font-family:Arial,Helvetica,sans-serif;color:#E8EEF5"><div style="max-width:560px;margin:0 auto;padding:28px 20px"><div style="font-size:22px;font-weight:bold;margin-bottom:4px">${titulo}</div><div style="color:#9AA8BA;margin-bottom:22px">${sub}</div>${corpo}</div></body></html>`;
}
const CAIXA = "background:#121821;border:1px solid #263142;border-radius:14px;padding:18px;margin-bottom:16px";
const ROT = "color:#9AA8BA;font-size:12px;text-transform:uppercase;letter-spacing:1px;margin-bottom:10px";
const CHV = "font-family:Consolas,Menlo,monospace;font-size:13px;word-break:break-all;background:#0A0E14;border:1px solid #263142;border-radius:10px;padding:12px;color:#3DDC97";

function emailCompra(p) {
  const linha = (a, b) => `<tr><td style="color:#9AA8BA">${a}</td><td align="right">${b}</td></tr>`;
  const html = moldura("SerpyraToolkit Pro ativado ✅", "Obrigado pela compra! Seu pagamento foi confirmado.",
    `<div style="${CAIXA}"><div style="${ROT}">Pedido</div><table style="width:100%;font-size:14px;color:#E8EEF5" cellpadding="4">` +
    linha("Número", "<b>" + esc(p.numero) + "</b>") + linha("Data", esc(p.dataPago)) + linha("Produto", "SerpyraToolkit Pro · " + esc(p.plano)) +
    (p.cupom ? linha("Cupom", esc(p.cupom)) : "") + linha("Valor", esc(p.valor) + " (Pix)") +
    linha("Celular", esc(p.codigo.slice(0, 4) + "-" + p.codigo.slice(4))) + linha("Válido até", `<b style="color:#3DDC97">${esc(p.validade)}</b>`) +
    `</table></div><div style="${CAIXA}"><div style="${ROT}">Sua chave de ativação</div><div style="${CHV}">${esc(p.chave)}</div>` +
    `<div style="color:#9AA8BA;font-size:13px;margin-top:12px">Se o app estava aberto, o Pro já foi ativado sozinho. Se não, abra <b>SerpyraToolkit &gt; SerpyraToolkit Pro</b>, toque em <b>Colar</b> e depois em <b>Ativar</b>. A chave funciona só no celular deste pedido. Trocou de celular? No app, use <b>Trocar de celular</b> com este e-mail.</div></div>` +
    `<div style="color:#66758A;font-size:12px">Para renovar, gere um novo Pix pelo app antes de vencer: os dias se somam. Guarde este e-mail como comprovante.</div>`);
  const texto = `SerpyraToolkit Pro ativado!\n\nPedido: ${p.numero}\nData: ${p.dataPago}\nProduto: SerpyraToolkit Pro - ${p.plano}\nValor: ${p.valor} (Pix)\nVálido até: ${p.validade}\n\nSua chave de ativação:\n${p.chave}\n\nNo app: SerpyraToolkit Pro > Colar > Ativar.`;
  return { assunto: `Pedido ${p.numero} confirmado: sua chave do SerpyraToolkit Pro`, html, texto };
}

/* ---------------- planos e cupons ---------------- */
async function cupomValido(env, cod) {
  if (!cod) return null;
  const c = await lerJson(env, "cupom:" + cod);
  if (!c || !c.ativo) return null;
  if (c.limite && (c.usos || 0) >= c.limite) return null;
  return c;
}
function comDesconto(centavos, c) { return c ? Math.max(100, Math.round(centavos * (100 - c.pct) / 100)) : centavos; }

function planos() {
  return json({ teste: TESTE_DIAS, planos: Object.entries(PLANOS).map(([id, p]) => ({ id, nome: p.nome, dias: p.dias, centavos: p.centavos, preco: reais(p.centavos) })) });
}

async function verCupom(url, env) {
  const cod = limpaCupom(url.searchParams.get("c"));
  const plano = PLANOS[url.searchParams.get("plano")] ? url.searchParams.get("plano") : "m1";
  const c = await cupomValido(env, cod);
  if (!c) return json({ ok: false, erro: "Cupom inválido ou esgotado." }, 404);
  const final = comDesconto(PLANOS[plano].centavos, c);
  return json({ ok: true, cupom: cod, pct: c.pct, centavos: final, preco: reais(final) });
}

/* ---------------- compra ---------------- */
async function limitar(chave, max) {
  const cache = caches.default;
  const req = new Request("https://limite.local/" + chave + "/" + Math.floor(Date.now() / 3600000));
  const r = await cache.match(req);
  const n = r ? parseInt(await r.text(), 10) || 0 : 0;
  if (n >= max) return false;
  await cache.put(req, new Response(String(n + 1), { headers: { "cache-control": "max-age=3700" } }));
  return true;
}

async function criarPedido(req, env, base) {
  let b; try { b = await req.json(); } catch (e) { return json({ erro: "Pedido inválido." }, 400); }
  const codigo = limpaCodigo(b.codigo);
  const email = String(b.email || "").trim().toLowerCase();
  const atual = parseInt(b.atual || "0", 10) || 0;
  const planoId = PLANOS[b.plano] ? b.plano : "m1";
  const plano = PLANOS[planoId];
  if (!codigoValido(codigo)) return json({ erro: "Código do celular inválido." }, 400);
  if (!emailValido(email)) return json({ erro: "E-mail inválido." }, 400);
  if (!(await limitar("pix:" + codigo, 6))) return json({ erro: "Muitos pedidos seguidos. Tente de novo em alguns minutos." }, 429);
  const cupomCod = limpaCupom(b.cupom);
  const cupom = cupomCod ? await cupomValido(env, cupomCod) : null;
  if (cupomCod && !cupom) return json({ erro: "Cupom inválido ou esgotado." }, 400);
  const valor = comDesconto(plano.centavos, cupom);
  const numero = "PCT-" + Date.now().toString(36).toUpperCase().slice(-6);
  let r;
  try {
    r = await purin(env, "POST", "/payments", {
      valueCents: valor, description: "SerpyraToolkit Pro - " + plano.nome + " (" + numero + ")", callbackUrl: base + "/webhook",
      customer: { email, externalId: codigo }, metadata: JSON.stringify({ numero, codigo, plano: planoId, cupom: cupomCod })
    });
  } catch (e) { return json({ erro: "Não consegui gerar o Pix agora. " + e.message }, 502); }
  const id = r.paymentId;
  await env.PCT.put("pedido:" + id, JSON.stringify({ id, numero, codigo, email, atual, valor, plano: planoId, dias: plano.dias, cupom: cupom ? cupomCod : "", status: "pendente", criado: new Date().toISOString() }), { expirationTtl: 60 * 60 * 24 * 400 });
  return json({ pedido: id, numero, valor: reais(valor), plano: plano.nome, brCode: r.pix && r.pix.brCode, qr: r.pix && r.pix.qrCodeImage, expira: r.expiresAt, teste: String(env.PURIN_KEY || "").startsWith("ps_test_") });
}

async function validadeAtual(env, codigo) {
  const c = await lerJson(env, "chave:" + codigo);
  if (c && c.dia) return c.dia;
  return parseInt((await env.PCT.get("lic:" + codigo)) || "0", 10) || 0;
}

async function liberar(env, id, valorPago) {
  const p = await lerJson(env, "pedido:" + id);
  if (!p) return null;
  if (p.status === "pago") return p;
  if (valorPago != null && valorPago < p.valor) { p.obs = "valor menor que o cobrado: " + valorPago; await env.PCT.put("pedido:" + id, JSON.stringify(p)); return p; }
  const dias = p.dias || 30;
  const dia = Math.max(hoje(), p.atual || 0, await validadeAtual(env, p.codigo)) + dias;
  p.chave = await assinarChave(env, p.codigo, dia);
  p.dia = dia; p.status = "pago"; p.pagoEm = new Date().toISOString();
  const plano = (PLANOS[p.plano] || PLANOS.m1).nome;
  const m = emailCompra({ numero: p.numero, codigo: p.codigo, chave: p.chave, valor: reais(p.valor), plano, cupom: p.cupom, validade: dataBr(dia), dataPago: agoraBr() });
  p.email_status = await mandarEmail(env, p.email, m.assunto, m.html, m.texto);
  await env.PCT.put("pedido:" + id, JSON.stringify(p));
  await env.PCT.put("chave:" + p.codigo, JSON.stringify({ chave: p.chave, dia, email: p.email }));
  const em = (await lerJson(env, "email:" + p.email)) || {};
  await env.PCT.put("email:" + p.email, JSON.stringify({ codigo: p.codigo, dia, troca: em.troca || 0, otp: "" }));
  if (p.cupom) {
    const c = await lerJson(env, "cupom:" + p.cupom);
    if (c) { c.usos = (c.usos || 0) + 1; c.receita = (c.receita || 0) + p.valor; await env.PCT.put("cupom:" + p.cupom, JSON.stringify(c)); }
  }
  return p;
}

async function status(url, env) {
  const id = url.searchParams.get("pedido") || "";
  if (!/^ps[a-z]?_[A-Za-z0-9]+$/.test(id)) return json({ erro: "Pedido inválido." }, 400);
  let p = await lerJson(env, "pedido:" + id);
  if (!p) return json({ status: "desconhecido" }, 404);
  if (p.status !== "pago") {
    try {
      const r = await purin(env, "GET", "/payments/" + id);
      if (r.status === "paid") p = (await liberar(env, id, r.amountCents != null ? r.amountCents : null)) || p;
      else if (r.status && r.status !== "pending") return json({ status: r.status });
    } catch (e) { }
  }
  if (p.status === "pago") return json({ status: "pago", chave: p.chave, numero: p.numero, validade: dataBr(p.dia), email: p.email_status === "enviado" });
  return json({ status: "pendente" });
}

async function webhook(req, env) {
  const corpo = await req.text();
  const ok = await hmacValido(env, corpo, req.headers.get("x-webhook-signature"));
  if (ok === false) return new Response("assinatura inválida", { status: 401 });
  let ev; try { ev = JSON.parse(corpo); } catch (e) { return new Response("ok"); }
  if (ev.event !== "payment.paid" || !ev.paymentId) return new Response("ok");
  if (ok === null) {
    try { const r = await purin(env, "GET", "/payments/" + ev.paymentId); if (r.status !== "paid") return new Response("ok"); }
    catch (e) { return new Response("tentar depois", { status: 500 }); }
  }
  await liberar(env, ev.paymentId, ev.amountCents != null ? ev.amountCents : null);
  return new Response("ok");
}

/* ---------------- teste grátis, recuperar e trocar de celular ---------------- */
async function testeGratis(req, env) {
  let b; try { b = await req.json(); } catch (e) { return json({ erro: "Pedido inválido." }, 400); }
  const codigo = limpaCodigo(b.codigo);
  if (!codigoValido(codigo)) return json({ erro: "Código do celular inválido." }, 400);
  if (await env.PCT.get("teste:" + codigo)) return json({ erro: "O teste grátis deste celular já foi usado." }, 409);
  if (await validadeAtual(env, codigo)) return json({ erro: "Este celular já tem ou já teve o Pro. Use Já comprei para recuperar." }, 409);
  if (!(await limitar("teste", 300))) return json({ erro: "Muitos testes agora. Tente mais tarde." }, 429);
  const dia = hoje() + TESTE_DIAS;
  const chave = await assinarChave(env, codigo, dia);
  await env.PCT.put("teste:" + codigo, String(dia));
  return json({ chave, validade: dataBr(dia), dias: TESTE_DIAS });
}

async function licenca(url, env) {
  const codigo = limpaCodigo(url.searchParams.get("codigo"));
  if (!codigoValido(codigo)) return json({ erro: "Código inválido." }, 400);
  const o = await lerJson(env, "chave:" + codigo);
  if (!o) return json({ status: "nenhuma" }, 404);
  if (o.dia < hoje()) return json({ status: "vencida", validade: dataBr(o.dia) }, 404);
  return json({ status: "ativa", chave: o.chave, validade: dataBr(o.dia) });
}

async function trocaPedir(req, env) {
  let b; try { b = await req.json(); } catch (e) { return json({ erro: "Pedido inválido." }, 400); }
  const email = String(b.email || "").trim().toLowerCase();
  const codigo = limpaCodigo(b.codigo);
  if (!emailValido(email) || !codigoValido(codigo)) return json({ erro: "Confira o e-mail." }, 400);
  if (!(await limitar("troca:" + email, 4))) return json({ erro: "Muitas tentativas. Tente de novo em uma hora." }, 429);
  const em = await lerJson(env, "email:" + email);
  if (!em) return json({ erro: "Não encontrei compra com esse e-mail." }, 404);
  if (em.dia < hoje()) return json({ erro: "A assinatura desse e-mail venceu em " + dataBr(em.dia) + ". Faça uma nova compra." }, 410);
  if (em.codigo === codigo) return json({ erro: "O Pro desse e-mail já é deste celular. Use Já comprei para recuperar." }, 409);
  if (em.troca && hoje() - em.troca < TROCA_INTERVALO) return json({ erro: "Você já trocou de celular em " + dataBr(em.troca) + ". A próxima troca fica liberada em " + dataBr(em.troca + TROCA_INTERVALO) + "." }, 429);
  const otp = String(crypto.getRandomValues(new Uint32Array(1))[0] % 1000000).padStart(6, "0");
  em.otp = otp; em.otpPara = codigo; em.otpAte = Date.now() + 15 * 60000; em.otpErros = 0;
  await env.PCT.put("email:" + email, JSON.stringify(em));
  const html = moldura("Código para trocar de celular", "Alguém pediu para passar o seu SerpyraToolkit Pro para outro celular.",
    `<div style="${CAIXA}"><div style="${ROT}">Seu código</div><div style="font-size:34px;font-weight:bold;letter-spacing:6px;color:#3DDC97">${otp}</div><div style="color:#9AA8BA;font-size:13px;margin-top:12px">Digite no app, no celular novo. Vale por 15 minutos. Se não foi você, ignore este e-mail: nada muda.</div></div>`);
  const st = await mandarEmail(env, email, "Seu código para trocar de celular: " + otp, html, "Seu código para trocar o SerpyraToolkit Pro de celular: " + otp + "\nVale por 15 minutos. Se não foi você, ignore.");
  if (st !== "enviado") return json({ erro: "Não consegui enviar o e-mail agora. Tente mais tarde." }, 502);
  return json({ ok: true });
}

async function trocaConfirmar(req, env) {
  let b; try { b = await req.json(); } catch (e) { return json({ erro: "Pedido inválido." }, 400); }
  const email = String(b.email || "").trim().toLowerCase();
  const codigo = limpaCodigo(b.codigo);
  const otp = String(b.otp || "").replace(/\D/g, "");
  const em = await lerJson(env, "email:" + email);
  if (!em || !em.otp || em.otpPara !== codigo || Date.now() > (em.otpAte || 0)) return json({ erro: "Código vencido. Peça um novo." }, 410);
  if (!igual(otp, em.otp)) {
    em.otpErros = (em.otpErros || 0) + 1;
    if (em.otpErros >= 5) em.otp = "";
    await env.PCT.put("email:" + email, JSON.stringify(em));
    return json({ erro: em.otp ? "Código errado." : "Código errado várias vezes. Peça um novo." }, 401);
  }
  const chave = await assinarChave(env, codigo, em.dia);
  await env.PCT.put("chave:" + codigo, JSON.stringify({ chave, dia: em.dia, email }));
  await env.PCT.put("email:" + email, JSON.stringify({ codigo, dia: em.dia, troca: hoje(), otp: "" }));
  return json({ ok: true, chave, validade: dataBr(em.dia) });
}

/* ---------------- painel do dono ---------------- */
async function listar(env, prefixo, max) {
  const nomes = []; let cursor;
  do {
    const l = await env.PCT.list({ prefix: prefixo, cursor, limit: 1000 });
    for (const k of l.keys) nomes.push(k.name);
    cursor = l.list_complete ? null : l.cursor;
  } while (cursor && nomes.length < max);
  return nomes;
}

async function admin(req, url, env) {
  if (!env.ADMIN_TOKEN || !igual(req.headers.get("x-admin"), env.ADMIN_TOKEN)) return json({ erro: "Token do painel incorreto." }, 401);
  const rota = url.pathname.slice(7);
  if (rota === "resumo") {
    const nomes = await listar(env, "pedido:", 3000);
    const pedidos = (await Promise.all(nomes.map(n => lerJson(env, n)))).filter(Boolean);
    const pagos = pedidos.filter(p => p.status === "pago").sort((a, b) => String(b.pagoEm).localeCompare(String(a.pagoEm)));
    const desde = d => pagos.filter(p => Date.parse(p.pagoEm) >= Date.now() - d * DIA);
    const soma = l => l.reduce((t, p) => t + (p.valor || 0), 0);
    const porPlano = {}; for (const p of pagos) { const k = (PLANOS[p.plano] || PLANOS.m1).nome; porPlano[k] = (porPlano[k] || 0) + 1; }
    return json({
      hoje: { vendas: desde(1).length, total: reais(soma(desde(1))) },
      semana: { vendas: desde(7).length, total: reais(soma(desde(7))) },
      mes: { vendas: desde(30).length, total: reais(soma(desde(30))) },
      tudo: { vendas: pagos.length, total: reais(soma(pagos)) },
      pendentes: pedidos.length - pagos.length, porPlano, emailsPendentes: pagos.filter(p => p.email_status !== "enviado").length,
      ultimas: pagos.slice(0, 60).map(p => ({ numero: p.numero, data: p.pagoEm, email: p.email, codigo: p.codigo, plano: (PLANOS[p.plano] || PLANOS.m1).nome, valor: reais(p.valor), cupom: p.cupom || "", validade: dataBr(p.dia), email_ok: p.email_status === "enviado" })),
      modo: String(env.PURIN_KEY || "").startsWith("ps_test_") ? "teste" : "produção"
    });
  }
  if (rota === "chave" && req.method === "POST") {
    const b = await req.json().catch(() => ({}));
    const codigo = limpaCodigo(b.codigo);
    if (!codigoValido(codigo)) return json({ erro: "Código do celular inválido (8 letras/números)." }, 400);
    const dias = parseInt(b.dias, 10);
    if (!(dias >= 1 && dias <= 100000)) return json({ erro: "Dias inválidos." }, 400);
    const dia = (b.somar ? Math.max(hoje(), await validadeAtual(env, codigo)) : hoje()) + dias;
    const chave = await assinarChave(env, codigo, dia);
    if (b.guardar !== false) await env.PCT.put("chave:" + codigo, JSON.stringify({ chave, dia, email: String(b.email || "").toLowerCase() }));
    return json({ chave, validade: dataBr(dia) });
  }
  if (rota === "cupons" && req.method === "GET") {
    const nomes = await listar(env, "cupom:", 500);
    const l = await Promise.all(nomes.map(async n => ({ codigo: n.slice(6), ...(await lerJson(env, n)) })));
    return json({ cupons: l.map(c => ({ ...c, receita: reais(c.receita || 0) })) });
  }
  if (rota === "cupom" && req.method === "POST") {
    const b = await req.json().catch(() => ({}));
    const cod = limpaCupom(b.codigo);
    const pct = parseInt(b.pct, 10);
    if (cod.length < 3) return json({ erro: "O cupom precisa de pelo menos 3 letras ou números." }, 400);
    if (!(pct >= 1 && pct <= 90)) return json({ erro: "Desconto entre 1% e 90%." }, 400);
    const atual = (await lerJson(env, "cupom:" + cod)) || { usos: 0, receita: 0, criado: new Date().toISOString() };
    atual.pct = pct; atual.ativo = b.ativo !== false; atual.limite = parseInt(b.limite, 10) || 0; atual.dono = String(b.dono || "").slice(0, 60);
    await env.PCT.put("cupom:" + cod, JSON.stringify(atual));
    return json({ ok: true });
  }
  if (rota === "cupom" && req.method === "DELETE") {
    await env.PCT.delete("cupom:" + limpaCupom(url.searchParams.get("c")));
    return json({ ok: true });
  }
  if (rota === "reenviar" && req.method === "POST") return json(await reenviarEmails(env));
  return json({ erro: "Não encontrado" }, 404);
}

/* ---------------- e-mails que falharam: reenvio diário ---------------- */
async function reenviarEmails(env) {
  const nomes = await listar(env, "pedido:", 3000);
  let enviados = 0, faltam = 0;
  for (const n of nomes) {
    const p = await lerJson(env, n);
    if (!p || p.status !== "pago" || p.email_status === "enviado" || !p.chave) continue;
    if (Date.parse(p.pagoEm) < Date.now() - 7 * DIA) continue;
    const m = emailCompra({ numero: p.numero, codigo: p.codigo, chave: p.chave, valor: reais(p.valor), plano: (PLANOS[p.plano] || PLANOS.m1).nome, cupom: p.cupom, validade: dataBr(p.dia), dataPago: agoraBr() });
    p.email_status = await mandarEmail(env, p.email, m.assunto, m.html, m.texto);
    if (p.email_status === "enviado") { enviados++; await env.PCT.put(n, JSON.stringify(p)); } else faltam++;
    if (faltam > 3) break;
  }
  return { enviados, faltam };
}

/* ---------------- atualização do app e site ---------------- */
const RAW = "https://raw.githubusercontent.com/Serpyraofficial/frio-toolkit/android-app/";
function github(env, arquivo) {
  const h = { "user-agent": "serpyratoolkit" };
  if (env.GITHUB_TOKEN) h.authorization = "token " + env.GITHUB_TOKEN;
  return fetch(RAW + arquivo + "?v=" + Math.floor(Date.now() / 120000), { headers: h });
}
async function versao(env) {
  const r = await github(env, "apk/versao.json");
  if (!r.ok) return json({ erro: "Versão indisponível (" + r.status + ")." }, 502);
  return new Response(await r.text(), { headers: { "content-type": "application/json; charset=utf-8", "cache-control": "no-store", ...CORS } });
}
async function baixarApk(env) {
  let arquivo = "SerpyraToolkit.apk";
  try { const v = await (await github(env, "apk/versao.json")).json(); if (v.arquivo) arquivo = v.arquivo; } catch (e) { }
  const r = await github(env, "apk/" + arquivo);
  if (!r.ok) return json({ erro: "APK indisponível (" + r.status + ")." }, 502);
  const h = { "content-type": "application/vnd.android.package-archive", "content-disposition": "attachment; filename=SerpyraToolkit.apk", "cache-control": "no-store" };
  const n = r.headers.get("content-length"); if (n) h["content-length"] = n;
  return new Response(r.body, { headers: h });
}

async function simular(url, env) {
  if (!String(env.PURIN_KEY || "").startsWith("ps_test_")) return json({ erro: "Só funciona com a chave de teste ps_test_." }, 403);
  try { await purin(env, "POST", "/sandbox/payments/" + encodeURIComponent(url.searchParams.get("pedido") || "") + "/simulate-paid"); } catch (e) { return json({ erro: e.message }, 502); }
  return json({ ok: true });
}

export default {
  async fetch(req, env) {
    const url = new URL(req.url);
    const p = url.pathname, m = req.method;
    if (m === "OPTIONS") return new Response(null, { headers: CORS });
    try {
      if (p.startsWith("/admin/")) return await admin(req, url, env);
      if (m === "GET" && p === "/planos") return planos();
      if (m === "GET" && p === "/cupom") return await verCupom(url, env);
      if (m === "POST" && p === "/pedido") return await criarPedido(req, env, url.origin);
      if (m === "GET" && p === "/status") return await status(url, env);
      if (m === "POST" && p === "/webhook") return await webhook(req, env);
      if (m === "POST" && p === "/teste") return await testeGratis(req, env);
      if (m === "GET" && p === "/licenca") return await licenca(url, env);
      if (m === "POST" && p === "/troca/pedir") return await trocaPedir(req, env);
      if (m === "POST" && p === "/troca/confirmar") return await trocaConfirmar(req, env);
      if (m === "POST" && p === "/simular") return await simular(url, env);
      if (m === "GET" && p === "/versao") return await versao(env);
      if (m === "GET" && (p === "/apk" || p === "/baixar")) return await baixarApk(env);
      if (m === "GET" && (p === "/" || p === "/index.html")) return Response.redirect("https://serpyratoolkit.pages.dev/", 302);
      if (p === "/saude") {
        const faltando = ["PURIN_KEY", "LICENCA_PRIV", "BREVO_KEY", "REMETENTE_EMAIL", "ADMIN_TOKEN"].filter(k => !env[k]);
        return json({ servico: "SerpyraToolkit Pro", ok: faltando.length === 0 && !!env.PCT, faltando: env.PCT ? faltando : faltando.concat(["KV PCT"]), modo: String(env.PURIN_KEY || "").startsWith("ps_test_") ? "teste" : "produção" });
      }
      return json({ erro: "Não encontrado" }, 404);
    } catch (e) {
      return json({ erro: "Erro interno: " + e.message }, 500);
    }
  },
  async scheduled(event, env, ctx) { ctx.waitUntil(reenviarEmails(env)); }
};
