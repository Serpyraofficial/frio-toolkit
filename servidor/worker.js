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
const TESTE_DIAS = 0, TESTE_MIN = 60;
const TROCA_INTERVALO = 30;
const PLANOS = {
  s7: { nome: "7 dias", dias: 7, centavos: 490, v: 3 },
  m1: { nome: "1 mês", dias: 30, centavos: 1290 },
  m3: { nome: "3 meses", dias: 90, centavos: 2990 },
  m6: { nome: "6 meses", dias: 180, centavos: 4990 },
  a1: { nome: "1 ano", dias: 365, centavos: 7990, v: 3 },
  vida: { nome: "Vitalício", dias: 0, centavos: 11990, vitalicio: true, v: 2 },
  f1: { nome: "Família · 1 mês", dias: 30, centavos: 2490, familia: 3, v: 3 },
  f12: { nome: "Família · 1 ano", dias: 365, centavos: 14990, familia: 3, v: 3 }
};
const DIA_VITALICIO = 99999;
const LICENCA_PUB = "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEe1+AJ9msQvTj5AdyZO0SzXl0SNyekG3tBq3nUeQHdWSvy3u7i8k6qTJsZ8iU890vHizszVAKeAxs4fjOVXVnFQ==";
const IND_PCT = 10, IND_DIAS = 5, RENOVA_PCT = 10, IND_MAX_MES = 15, IND_PREMIO = { s7: 2, m1: 5, m3: 10, m6: 15, a1: 30, vida: 30, f1: 5, f12: 30 }, COMISSAO_PADRAO = 20;
const MOTIVOS = ["Caro", "Não usei muito", "Não funcionou no meu celular", "Difícil de usar", "Só queria testar", "Outro"];
const CORS = { "access-control-allow-origin": "*", "access-control-allow-headers": "content-type, x-admin, x-dono", "access-control-allow-methods": "GET,POST,DELETE" };

const json = (o, status = 200) => new Response(JSON.stringify(o), { status, headers: { "content-type": "application/json; charset=utf-8", ...CORS } });
function reais(c) { return "R$ " + (c / 100).toFixed(2).replace(".", ","); }
function validadeTxt(dia) { return dia >= 99999 ? "Vitalício (não vence)" : dataBr(dia); }
function hoje() { return Math.floor(Date.now() / DIA); }
function dataBr(dia) { const d = new Date(dia * DIA); return String(d.getUTCDate()).padStart(2, "0") + "/" + String(d.getUTCMonth() + 1).padStart(2, "0") + "/" + d.getUTCFullYear(); }
function horaBr(ms) { const a = new Date(ms - 3 * 3600000); return String(a.getUTCHours()).padStart(2, "0") + ":" + String(a.getUTCMinutes()).padStart(2, "0") + " de " + String(a.getUTCDate()).padStart(2, "0") + "/" + String(a.getUTCMonth() + 1).padStart(2, "0"); }
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

/* confere uma chave PCT-CODIGO.DIA.ASSINATURA; devolve { codigo, dia } ou null */
async function conferirChave(chave) {
  const t = String(chave || "").trim().replace(/\s+/g, "").replace(/^PCT-/i, "");
  const p = t.split(".");
  if (p.length !== 3) return null;
  const codigo = p[0].toUpperCase().replace(/-/g, ""), dia = parseInt(p[1], 10);
  if (!codigoValido(codigo) || !(dia > 0)) return null;
  try {
    const sig = b64ToBytes(p[2].replace(/-/g, "+").replace(/_/g, "/") + "===".slice((p[2].length + 3) % 4));
    if (sig.length !== 64) return null;
    const pub = await crypto.subtle.importKey("spki", b64ToBytes(LICENCA_PUB), { name: "ECDSA", namedCurve: "P-256" }, false, ["verify"]);
    const ok = await crypto.subtle.verify({ name: "ECDSA", hash: "SHA-256" }, pub, sig, new TextEncoder().encode(codigo + "." + dia));
    return ok ? { codigo, dia } : null;
  } catch (e) { return null; }
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
    (p.familia && p.familia.length ? `<div style="${CAIXA}"><div style="${ROT}">Códigos para a sua família</div>` + p.familia.map(v => `<div style="font-size:22px;font-weight:bold;letter-spacing:3px;color:#3DDC97;margin:6px 0">${esc(v)}</div>`).join("") + `<div style="color:#9AA8BA;font-size:13px;margin-top:10px">Cada código libera o Pro em mais um celular, pelo mesmo tempo do seu plano. A pessoa baixa o app em serpyratoolkit.pages.dev, abre SerpyraToolkit Pro e cola o código em “Tem um código?”.</div></div>` : "") +
    `<div style="color:#66758A;font-size:12px">Para renovar, gere um novo Pix pelo app antes de vencer: os dias se somam. Guarde este e-mail como comprovante.</div>`);
  const texto = (p.familia && p.familia.length ? "Códigos para a sua família: " + p.familia.join(", ") + "\n\n" : "") + `SerpyraToolkit Pro ativado!\n\nPedido: ${p.numero}\nData: ${p.dataPago}\nProduto: SerpyraToolkit Pro - ${p.plano}\nValor: ${p.valor} (Pix)\nVálido até: ${p.validade}\n\nSua chave de ativação:\n${p.chave}\n\nNo app: SerpyraToolkit Pro > Colar > Ativar.`;
  return { assunto: `Pedido ${p.numero} confirmado: sua chave do SerpyraToolkit Pro`, html, texto };
}

/* ---------------- planos e cupons ---------------- */
async function cupomValido(env, cod, codigo) {
  if (!cod) return null;
  if (/^IND[A-Z2-9]{8}$/.test(cod)) {
    const ref = cod.slice(3);
    if (!codigoValido(ref) || ref === codigo) return null;
    const c = await lerJson(env, "chave:" + ref);
    return c && !c.revogada ? { pct: IND_PCT, ativo: true, especial: "indicacao", ref } : null;
  }
  if (cod === "RENOVA10") {
    if (!codigoValido(codigo)) return null;
    const c = await lerJson(env, "chave:" + codigo);
    if (!c || c.dia >= DIA_VITALICIO || c.dia > hoje() + 7 || c.dia < hoje() - 7) return null;
    return { pct: RENOVA_PCT, ativo: true, especial: "renovacao" };
  }
  const c = await lerJson(env, "cupom:" + cod);
  if (!c || !c.ativo) return null;
  if (c.limite && (c.usos || 0) >= c.limite) return null;
  return c;
}
function comDesconto(centavos, c) { return c ? Math.max(100, Math.round(centavos * (100 - c.pct) / 100)) : centavos; }

function planos(url) {
  // apps antigos (sem ?v=2) nao sabem mostrar o vitalicio (dias = 0): so os planos mensais para eles
  const v = parseInt(url.searchParams.get("v") || "0", 10) || 0;
  return json({ teste: 0, testeMin: TESTE_MIN, indicacao: { pct: IND_PCT, dias: IND_DIAS }, renova: RENOVA_PCT, planos: Object.entries(PLANOS).filter(([id, p]) => (p.v || 0) <= v).map(([id, p]) => ({ id, nome: p.nome, dias: p.dias, centavos: p.centavos, preco: reais(p.centavos), vitalicio: !!p.vitalicio, familia: p.familia || 0 })) });
}

async function verCupom(url, env) {
  const cod = limpaCupom(url.searchParams.get("c"));
  const plano = PLANOS[url.searchParams.get("plano")] ? url.searchParams.get("plano") : "m1";
  const c = await cupomValido(env, cod, limpaCodigo(url.searchParams.get("codigo")));
  if (!c) return json({ ok: false, erro: "Cupom inválido ou esgotado." }, 404);
  const final = comDesconto(PLANOS[plano].centavos, c);
  return json({ ok: true, cupom: cod, pct: c.pct, centavos: final, preco: reais(final), tipo: c.especial || "cupom" });
}

/* ---------------- compra ---------------- */
async function contarCache(chave) {
  const cache = caches.default, req = new Request("https://conta.local/" + chave);
  const r = await cache.match(req);
  const n = (r ? parseInt(await r.text(), 10) || 0 : 0) + 1;
  await cache.put(req, new Response(String(n), { headers: { "cache-control": "max-age=90000" } }));
  return n;
}
async function cacheJson(chave, segundos, fazer) {
  const cache = caches.default, req = new Request("https://cache.local/" + encodeURIComponent(chave));
  const r = await cache.match(req);
  if (r) return JSON.parse(await r.text());
  const o = await fazer();
  await cache.put(req, new Response(JSON.stringify(o), { headers: { "cache-control": "max-age=" + segundos } }));
  return o;
}
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
  // compra pelo site: sem celular ainda; a pessoa recebe um código de ativação por e-mail
  const site = !!b.site;
  const codigo = site ? "WEB" + idAleatorio(5) : limpaCodigo(b.codigo);
  const email = String(b.email || "").trim().toLowerCase();
  const atual = site ? 0 : (parseInt(b.atual || "0", 10) || 0);
  const planoId = PLANOS[b.plano] ? b.plano : "m1";
  const plano = PLANOS[planoId];
  if (!codigoValido(codigo)) return json({ erro: "Código do celular inválido." }, 400);
  if (!emailValido(email)) return json({ erro: "E-mail inválido." }, 400);
  if (!(await limitar("pix:" + (site ? "site:" + (req.headers.get("cf-connecting-ip") || "x") : codigo), 6))) return json({ erro: "Muitos pedidos seguidos. Tente de novo em alguns minutos." }, 429);
  const cupomCod = limpaCupom(b.cupom);
  const cupom = cupomCod ? await cupomValido(env, cupomCod, codigo) : null;
  const presente = site ? email : (b.presente ? String(b.presente).trim().toLowerCase() : "");
  if (site && cupom && cupom.especial === "renovacao") return json({ erro: "Esse cupom é para renovar pelo app." }, 400);
  if (presente && !emailValido(presente)) return json({ erro: "E-mail de quem vai ganhar o presente é inválido." }, 400);
  if (presente && cupom && cupom.especial === "renovacao") return json({ erro: "Esse cupom é só para renovar o seu próprio Pro." }, 400);
  if (cupomCod && !cupom) return json({ erro: "Cupom inválido ou esgotado." }, 400);
  const valor = comDesconto(plano.centavos, cupom);
  const numero = "PCT-" + Date.now().toString(36).toUpperCase().slice(-6);
  let r;
  try {
    r = await purin(env, "POST", "/payments", {
      valueCents: valor, description: "SerpyraToolkit Pro - " + plano.nome + (site ? " (site)" : presente ? " (presente)" : "") + " (" + numero + ")", callbackUrl: base + "/webhook",
      customer: { email, externalId: codigo }, metadata: JSON.stringify({ numero, codigo, plano: planoId, cupom: cupomCod })
    });
  } catch (e) { return json({ erro: /\b50[234]\b/.test(e.message) ? "O sistema de pagamento Pix está fora do ar agora. Tente de novo em alguns minutos." : "Não consegui gerar o Pix agora. " + e.message }, 502); }
  const id = r.paymentId;
  await env.PCT.put("pedido:" + id, JSON.stringify({ id, numero, codigo, email, atual, valor, plano: planoId, dias: plano.dias, cupom: cupom ? cupomCod : "", presente, site: site || undefined, status: "pendente", criado: new Date().toISOString() }), { expirationTtl: 60 * 60 * 24 * 400 });
  return json({ pedido: id, numero, valor: reais(valor), plano: plano.nome, brCode: r.pix && r.pix.brCode, qr: r.pix && r.pix.qrCodeImage, expira: r.expiresAt, teste: String(env.PURIN_KEY || "").startsWith("ps_test_") });
}

async function validadeAtual(env, codigo) {
  const c = await lerJson(env, "chave:" + codigo);
  if (c && c.dia) return c.ate && Date.now() > c.ate ? 0 : c.dia;
  return parseInt((await env.PCT.get("lic:" + codigo)) || "0", 10) || 0;
}

async function guardarRecente(env, p, plano) {
  try {
    const l = (await lerJson(env, "vendas:recentes")) || [];
    l.unshift({ em: Date.now(), valor: reais(p.valor), plano, presente: !!p.presente, cupom: p.cupom || "", numero: p.numero });
    await env.PCT.put("vendas:recentes", JSON.stringify(l.slice(0, 30)));
  } catch (e) { }
}
async function avisarDono(env, p, plano) { await guardarRecente(env, p, plano); if (!env.AVISO_POR_EMAIL) return; try { const ti = (p.presente ? "🎁 Presente vendido: " : "💰 Nova venda: ") + reais(p.valor) + " · " + plano; const linhas = [["Plano", plano + (p.presente ? " (presente para " + p.presente + ")" : "")], ["Valor", reais(p.valor)], ["Cliente", p.email], ["Celular", p.codigo ? p.codigo.slice(0, 4) + "-" + p.codigo.slice(4) : "—"], ["Cupom", p.cupom || "—"], ["Pedido", p.numero], ["Quando", agoraBr()]]; const html = moldura(ti, "Pix confirmado. O cliente já recebeu a chave por e-mail.", `<div style="${CAIXA}"><table style="width:100%;font-size:14px;color:#E8EEF5" cellpadding="4">` + linhas.map(([a, b]) => `<tr><td style="color:#9AA8BA">${a}</td><td align="right">${esc(b)}</td></tr>`).join("") + `</table></div><a href="https://serpyratoolkit.pages.dev/painel" style="color:#3DDC97">Abrir o painel de vendas</a>`); await mandarEmail(env, env.AVISO_EMAIL || env.REMETENTE_EMAIL, ti, html, ti + "\n" + linhas.map(l => l.join(": ")).join("\n")); } catch (e) { } }
async function liberar(env, id, valorPago) {
  const p = await lerJson(env, "pedido:" + id);
  if (!p) return null;
  if (p.status === "pago") return p;
  if (valorPago != null && valorPago < p.valor) { p.obs = "valor menor que o cobrado: " + valorPago; await env.PCT.put("pedido:" + id, JSON.stringify(p)); return p; }
  const planoObj = PLANOS[p.plano] || PLANOS.m1;
  if (p.presente) return await liberarPresente(env, id, p, planoObj);
  const dias = p.dias || 30;
  const dia = planoObj.vitalicio ? DIA_VITALICIO : Math.max(hoje(), p.atual || 0, await validadeAtual(env, p.codigo)) + dias;
  p.chave = await assinarChave(env, p.codigo, dia);
  p.dia = dia; p.status = "pago"; p.pagoEm = new Date().toISOString();
  const plano = (PLANOS[p.plano] || PLANOS.m1).nome;
  if (planoObj.familia && !p.familia) {
    p.familia = [];
    for (let i = 1; i < planoObj.familia; i++) {
      const v = novoVoucher();
      await env.PCT.put("presente:" + v, JSON.stringify({ plano: p.plano, dias: dias, vitalicio: false, de: p.email, para: "família de " + p.email, familia: true, usado: "", criado: new Date().toISOString() }));
      p.familia.push(v);
    }
  }
  const m = emailCompra({ numero: p.numero, codigo: p.codigo, chave: p.chave, valor: reais(p.valor), plano, cupom: p.cupom, validade: validadeTxt(dia), dataPago: agoraBr(), familia: p.familia });
  p.email_status = await mandarEmail(env, p.email, m.assunto, m.html, m.texto);
  await avisarDono(env, p, plano);
  await env.PCT.put("pedido:" + id, JSON.stringify(p));
  await env.PCT.put("chave:" + p.codigo, JSON.stringify({ chave: p.chave, dia, email: p.email }));
  const em = (await lerJson(env, "email:" + p.email)) || {};
  await env.PCT.put("email:" + p.email, JSON.stringify({ codigo: p.codigo, dia, troca: em.troca || 0, otp: "", pc: em.pc, pcEm: em.pcEm }));
  if (/^IND/.test(p.cupom || "")) await premiarIndicacao(env, p.cupom.slice(3), p.codigo, p.plano);
  else if (p.cupom && p.cupom !== "RENOVA10") {
    const c = await lerJson(env, "cupom:" + p.cupom);
    if (c) { c.usos = (c.usos || 0) + 1; c.receita = (c.receita || 0) + p.valor; c.comissaoTotal = (c.comissaoTotal || 0) + Math.round(p.valor * (c.comissao != null ? c.comissao : COMISSAO_PADRAO) / 100); await env.PCT.put("cupom:" + p.cupom, JSON.stringify(c)); }
  }
  return p;
}

/* ---------------- indicação e presente ---------------- */
async function premiarIndicacao(env, ref, comprador, plano) {
  if (!codigoValido(ref) || ref === comprador) return;
  const c = await lerJson(env, "chave:" + ref);
  if (!c || c.dia >= DIA_VITALICIO) return;
  const mes = mesAtual(), usados = c.indMes === mes ? (c.indDias || 0) : 0;
  const ganho = Math.min(IND_PREMIO[plano] || IND_DIAS, Math.max(0, IND_MAX_MES - usados));
  if (ganho <= 0) { await env.PCT.put("chave:" + ref, JSON.stringify({ ...c, indicacoes: (c.indicacoes || 0) + 1, indMes: mes, indDias: usados })); return; }
  const dia = Math.max(hoje(), c.dia) + ganho;
  const chave = await assinarChave(env, ref, dia);
  await env.PCT.put("chave:" + ref, JSON.stringify({ ...c, ate: undefined, chave, dia, indicacoes: (c.indicacoes || 0) + 1, indMes: mes, indDias: usados + ganho }));
  if (c.email) {
    const html = moldura("Você ganhou " + ganho + " dias de Pro 🎉", "Um amigo comprou o SerpyraToolkit Pro com o seu código de indicação.",
      `<div style="${CAIXA}"><div style="${ROT}">Seu Pro agora vale até</div><div style="font-size:22px;font-weight:bold;color:#3DDC97">${dataBr(dia)}</div><div style="color:#9AA8BA;font-size:13px;margin-top:12px">Os dias entram sozinhos ao abrir o app (tela do Pro &gt; Já comprei, se precisar). Se quiser colar à mão:</div><div style="${CHV};margin-top:10px">${esc(chave)}</div></div>`);
    await mandarEmail(env, c.email, "Você ganhou " + ganho + " dias de SerpyraToolkit Pro", html, "Um amigo comprou com o seu código. Seu Pro agora vale até " + dataBr(dia) + ".\nChave: " + chave);
  }
}

function novoVoucher() { const r = crypto.getRandomValues(new Uint8Array(9)); return "PRE" + [...r].map(x => B32[x % 32]).join(""); }

function emailPresente(p, plano) {
  const html = moldura("Você ganhou o SerpyraToolkit Pro 🎁", "Alguém (" + esc(p.email) + ") te deu de presente o SerpyraToolkit Pro · " + esc(plano.nome) + ".",
    `<div style="${CAIXA}"><div style="${ROT}">Seu código de presente</div><div style="font-size:26px;font-weight:bold;letter-spacing:3px;color:#3DDC97">${esc(p.voucher)}</div>` +
    `<div style="color:#9AA8BA;font-size:13px;margin-top:12px">1. Baixe o app em <a style="color:#3DDC97" href="https://serpyratoolkit.pages.dev">serpyratoolkit.pages.dev</a><br>2. Abra <b>SerpyraToolkit Pro</b> e toque em <b>Tenho um código de presente</b><br>3. Digite o código acima. Pronto!</div></div>`);
  return { assunto: "Você ganhou um presente: SerpyraToolkit Pro", html, texto: "Você ganhou o SerpyraToolkit Pro (" + plano.nome + ") de " + p.email + ".\nCódigo de presente: " + p.voucher + "\nBaixe em serpyratoolkit.pages.dev, abra SerpyraToolkit Pro > Tenho um código de presente." };
}

function emailSite(p, plano) {
  const cods = p.vouchers || [p.voucher];
  const lista = cods.map((v, i) => `<div style="font-size:24px;font-weight:bold;letter-spacing:3px;color:#C8FF6B;margin:6px 0">${esc(v)}</div>` + (cods.length > 1 ? `<div style="color:#9AA8BA;font-size:12px;margin-bottom:8px">${i === 0 ? "para o seu celular" : "para mais um celular da família"}</div>` : "")).join("");
  const html = moldura("Seu SerpyraToolkit Pro chegou ✅", "Pagamento confirmado · " + esc(plano.nome) + " · pedido " + esc(p.numero) + ".",
    `<div style="${CAIXA}"><div style="${ROT}">${cods.length > 1 ? "Seus códigos de ativação (um por celular)" : "Seu código de ativação"}</div>${lista}` +
    `<div style="color:#9AA8BA;font-size:13px;margin-top:12px">1. Baixe o app em <a style="color:#C8FF6B" href="https://serpyratoolkit.pages.dev/apk">serpyratoolkit.pages.dev</a> no celular Android<br>2. Abra <b>SerpyraToolkit Pro</b> e toque em <b>Tem um código?</b><br>3. Cole o código acima. O Pro liga na hora.</div></div><div style="color:#9AA8BA;font-size:12px">Os dias começam a contar quando você ativar no celular. Guarde este e-mail.</div>`);
  return { assunto: "Seu código do SerpyraToolkit Pro (pedido " + p.numero + ")", html, texto: "Pagamento confirmado: " + plano.nome + ".\nCódigo(s) de ativação: " + cods.join(", ") + "\nBaixe em serpyratoolkit.pages.dev, abra SerpyraToolkit Pro > Tem um código? e cole o código." };
}
async function liberarPresente(env, id, p, plano) {
  if (p.site) {
    if (!p.vouchers) {
      p.vouchers = [];
      for (let i = 0; i < (plano.familia || 1); i++) {
        const v = novoVoucher();
        await env.PCT.put("presente:" + v, JSON.stringify({ plano: p.plano, dias: p.dias, vitalicio: !!plano.vitalicio, de: p.email, para: p.email, familia: !!plano.familia || undefined, site: true, usado: "", criado: new Date().toISOString() }));
        p.vouchers.push(v);
      }
      p.voucher = p.vouchers[0];
    }
    p.status = "pago"; p.pagoEm = new Date().toISOString(); p.dia = 0;
    const m = emailSite(p, plano);
    p.email_status = await mandarEmail(env, p.email, m.assunto, m.html, m.texto);
    await avisarDono(env, p, plano.nome + " (site)");
    if (p.cupom && p.cupom !== "RENOVA10" && !/^IND/.test(p.cupom)) {
      const c = await lerJson(env, "cupom:" + p.cupom);
      if (c) { c.usos = (c.usos || 0) + 1; c.receita = (c.receita || 0) + p.valor; c.comissaoTotal = (c.comissaoTotal || 0) + Math.round(p.valor * (c.comissao != null ? c.comissao : COMISSAO_PADRAO) / 100); await env.PCT.put("cupom:" + p.cupom, JSON.stringify(c)); }
    }
    await env.PCT.put("pedido:" + id, JSON.stringify(p));
    return p;
  }
  p.voucher = p.voucher || novoVoucher();
  p.status = "pago"; p.pagoEm = new Date().toISOString(); p.dia = 0;
  await env.PCT.put("presente:" + p.voucher, JSON.stringify({ plano: p.plano, dias: p.dias, vitalicio: !!plano.vitalicio, de: p.email, para: p.presente, usado: "" }));
  const m = emailPresente(p, plano);
  p.email_status = await mandarEmail(env, p.presente, m.assunto, m.html, m.texto);
  await avisarDono(env, p, plano.nome);
  await mandarEmail(env, p.email, "Presente enviado: pedido " + p.numero, moldura("Presente enviado ✅", "Pagamento confirmado.", `<div style="${CAIXA}">Mandamos o código <b>${esc(p.voucher)}</b> para <b>${esc(p.presente)}</b>. Guarde este e-mail: se a pessoa não achar, repasse o código.</div>`), "Presente enviado para " + p.presente + ". Código: " + p.voucher);
  await env.PCT.put("pedido:" + id, JSON.stringify(p));
  return p;
}

async function resgatarPresente(req, env) {
  let b; try { b = await req.json(); } catch (e) { return json({ erro: "Pedido inválido." }, 400); }
  const codigo = limpaCodigo(b.codigo);
  const v = limpaCupom(b.voucher);
  if (!codigoValido(codigo)) return json({ erro: "Código do celular inválido." }, 400);
  if (!(await limitar("presente:" + codigo, 8))) return json({ erro: "Muitas tentativas. Tente de novo em uma hora." }, 429);
  const cfgD = (await lerJson(env, "config")) || {};
  const g = cfgD.donoChave && igual(v, cfgD.donoChave) ? null : await lerJson(env, "presente:" + v);
  if (!g) return json({ erro: "Código de presente não encontrado. Confira as letras." }, 404);
  if (g.usado && g.usado !== codigo) return json({ erro: "Esse presente já foi usado em outro celular." }, 409);
  if (!g.usado && !g.vitalicio) { const atual = await validadeAtual(env, codigo); if (atual >= hoje()) return json({ erro: "Você já tem o Pro ativo até " + validadeTxt(atual) + ". Use este código quando ele acabar: ele continua guardado para você." }, 409); }
  const ate = g.usado ? (g.ate || 0) : (g.horas && !g.vitalicio ? Date.now() + g.horas * 3600000 : 0);
  const dia = g.vitalicio ? DIA_VITALICIO : (g.usado ? g.dia : ate ? Math.floor(ate / DIA) : Math.max(hoje(), await validadeAtual(env, codigo)) + (g.dias || 30));
  const chave = await assinarChave(env, codigo, dia);
  if (!g.usado) {
    g.usado = codigo; g.dia = dia; g.em = new Date().toISOString(); if (ate) g.ate = ate;
    await env.PCT.put("presente:" + v, JSON.stringify(g));
    await env.PCT.put("chave:" + codigo, JSON.stringify({ chave, dia, ate: ate || undefined, email: emailValido(g.para) ? g.para : "", origem: g.de === "cortesia" ? "cortesia" : "presente" }));
    if (emailValido(g.para)) { const ant = (await lerJson(env, "email:" + g.para)) || {}; await env.PCT.put("email:" + g.para, JSON.stringify({ codigo, dia, troca: 0, otp: "", pc: ant.pc, pcEm: ant.pcEm })); }
  }
  return json({ ok: true, chave, validade: ate ? "até " + horaBr(ate) : validadeTxt(dia), ate: ate || undefined });
}

/* ---------------- mensagens, relatos, pirataria e notas ---------------- */
/* ---------------- banco D1 (100 mil gravações por dia no plano grátis) ---------------- */
let dbPronto = false;
async function db(env) {
  if (!env.DB) return null;
  if (!dbPronto) {
    await env.DB.batch([
      env.DB.prepare("CREATE TABLE IF NOT EXISTS contagem (dia TEXT, tipo TEXT, chave TEXT, n INTEGER, PRIMARY KEY (dia, tipo, chave))"),
      env.DB.prepare("CREATE TABLE IF NOT EXISTS botoes (id TEXT PRIMARY KEY, pkg TEXT, jogo TEXT, nome TEXT, autor TEXT, codigo TEXT, n INTEGER, baixados INTEGER DEFAULT 0, em INTEGER, oculto INTEGER DEFAULT 0, celular TEXT)"),
      env.DB.prepare("CREATE INDEX IF NOT EXISTS botoes_pkg ON botoes (pkg, oculto, baixados)"),
      env.DB.prepare("CREATE TABLE IF NOT EXISTS erros (em INTEGER, mes TEXT, sig TEXT, tipo TEXT, onde TEXT, msg TEXT, codigo TEXT, modelo TEXT, versao TEXT, android TEXT, tela TEXT, rastro TEXT, extra TEXT, print TEXT)"),
      env.DB.prepare("CREATE INDEX IF NOT EXISTS erros_mes ON erros (mes, sig)")
    ]);
    dbPronto = true;
  }
  return env.DB;
}
async function contar(env, tipo, chave) {
  const d = await db(env); if (!d) return;
  await d.prepare("INSERT INTO contagem (dia, tipo, chave, n) VALUES (?, ?, ?, 1) ON CONFLICT (dia, tipo, chave) DO UPDATE SET n = n + 1").bind(diaAtual(), tipo, String(chave).slice(0, 80)).run();
}
async function botoesLista(url, env) {
  const d = await db(env); if (!d) return json({ botoes: [] });
  const pkg = String(url.searchParams.get("jogo") || "").slice(0, 120);
  if (!pkg) return json({ botoes: [] });
  const r = await cacheJson("botoes:" + pkg, 600, async () => (await d.prepare("SELECT id, nome, autor, n, baixados, em FROM botoes WHERE pkg = ? AND oculto = 0 ORDER BY baixados DESC, em DESC LIMIT 30").bind(pkg).all()).results || []);
  return json({ botoes: r });
}
async function botoesUm(url, env) {
  const d = await db(env); if (!d) return json({ erro: "Indisponível agora." }, 503);
  const id = String(url.searchParams.get("id") || "").slice(0, 20);
  const b = await d.prepare("SELECT codigo, nome FROM botoes WHERE id = ? AND oculto = 0").bind(id).first();
  if (!b) return json({ erro: "Não encontrado." }, 404);
  await d.prepare("UPDATE botoes SET baixados = baixados + 1 WHERE id = ?").bind(id).run();
  return json({ codigo: b.codigo, nome: b.nome });
}
async function botoesPublicar(req, env) {
  const d = await db(env); if (!d) return json({ erro: "Indisponível agora. Tente mais tarde." }, 503);
  let b; try { b = await req.json(); } catch (e) { return json({ erro: "Pedido inválido." }, 400); }
  const celular = limpaCodigo(b.celular), pkg = String(b.pkg || "").slice(0, 120), codigo = String(b.botoes || "");
  if (!codigoValido(celular) || !pkg) return json({ erro: "Pedido inválido." }, 400);
  if (!codigo.startsWith("SERPYRA-BOTOES:") || codigo.length > 20000) return json({ erro: "Botões inválidos." }, 400);
  const nome = String(b.nome || "").replace(/\s+/g, " ").trim().slice(0, 40), autor = String(b.autor || "").replace(/\s+/g, " ").trim().slice(0, 30);
  if (nome.length < 2) return json({ erro: "Dê um nome para os seus botões." }, 400);
  if (/https?:\/\/|www\./i.test(nome + autor)) return json({ erro: "Não use links." }, 400);
  if (!(await limitar("botpub:" + celular, 3)) || !(await limitar("botpub-total", 60))) return json({ erro: "Muitas publicações agora. Tente mais tarde." }, 429);
  const id = Date.now().toString(36) + Math.random().toString(36).slice(2, 5);
  await d.prepare("INSERT INTO botoes (id, pkg, jogo, nome, autor, codigo, n, em, celular) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)").bind(id, pkg, String(b.jogo || "").slice(0, 60), nome, autor, codigo, parseInt(b.n, 10) || 0, Date.now(), celular).run();
  try { await caches.default.delete(new Request("https://cache.local/" + encodeURIComponent("botoes:" + pkg))); } catch (e) { }
  return json({ ok: true, id });
}

async function avaliar(req, env) {
  let b; try { b = await req.json(); } catch (e) { return json({ erro: "Pedido inválido." }, 400); }
  if (b.site) return json({ ok: true });
  const estrelas = parseInt(b.estrelas, 10), texto = String(b.texto || "").replace(/\s+/g, " ").trim().slice(0, 600), nome = String(b.nome || "").replace(/\s+/g, " ").trim().slice(0, 40);
  if (!(estrelas >= 1 && estrelas <= 5)) return json({ erro: "Escolha de 1 a 5 estrelas." }, 400);
  if (texto.length < 3) return json({ erro: "Escreva um comentário." }, 400);
  if (/https?:\/\/|www\./i.test(texto + nome)) return json({ erro: "Não use links no comentário." }, 400);
  const ip = req.headers.get("cf-connecting-ip") || "x";
  if (!(await limitar("aval:" + ip, 2)) || !(await limitar("aval-total", 20))) return json({ erro: "Muitas avaliações agora. Tente de novo mais tarde." }, 429);
  const l = (await lerJson(env, "avaliacoes")) || [];
  l.unshift({ id: Date.now().toString(36) + Math.random().toString(36).slice(2, 5), em: Date.now(), estrelas, texto, nome, visivel: true });
  await env.PCT.put("avaliacoes", JSON.stringify(l.slice(0, 500)));
  try { await caches.default.delete(new Request("https://cache.local/avaliacoes")); } catch (e) { }
  return json({ ok: true });
}
async function avaliacoes(env) {
  const r = await cacheJson("avaliacoes", 300, async () => {
    const v = ((await lerJson(env, "avaliacoes")) || []).filter(a => a.visivel);
    const ord = v.filter(a => a.fixada).sort((a, b) => b.fixada - a.fixada).concat(v.filter(a => !a.fixada));
    return { total: v.length, media: v.length ? Math.round(v.reduce((t, a) => t + a.estrelas, 0) * 10 / v.length) / 10 : 0, lista: ord.slice(0, 30).map(a => ({ estrelas: a.estrelas, texto: a.texto, nome: a.nome || "", em: a.em, fixada: !!a.fixada, resposta: a.resposta || "" })) };
  });
  return json(r);
}
async function lerSite(env) {
  const c = (await lerJson(env, "site")) || {};
  return { aviso: c.aviso || { ligado: false, texto: "", botao: "", link: "" }, faixa: c.faixa || [], avaliacoes: c.avaliacoes !== false, destaque: c.destaque || { ligado: false, titulo: "", texto: "", botao: "", link: "" }, em: c.em || 0 };
}
async function siteConfig(env) { return json(await cacheJson("site", 60, () => lerSite(env))); }

/* ---------------- relatório completo (a cada 15 dias e quando o dono pedir) ---------------- */
function mesesEntre(de, ate) { const l = []; let d = new Date(de - 3 * 3600000); d = new Date(Date.UTC(d.getUTCFullYear(), d.getUTCMonth(), 1)); const fim = new Date(ate - 3 * 3600000); while (d <= fim) { l.push(d.toISOString().slice(0, 7)); d = new Date(Date.UTC(d.getUTCFullYear(), d.getUTCMonth() + 1, 1)); } return l; }
function diaDe(ms) { return new Date(ms - 3 * 3600000).toISOString().slice(0, 10); }
async function montarRelatorio(env, de, ate, auto) {
  const nomes = await listar(env, "pedido:", 3000);
  const pedidos = (await Promise.all(nomes.map(n => lerJson(env, n)))).filter(Boolean);
  const pagos = pedidos.filter(p => p.status === "pago" && p.pagoEm);
  const no = (p, a, b) => { const t = Date.parse(p.pagoEm); return t >= a && t < b; };
  const periodo = pagos.filter(p => no(p, de, ate)), antes = pagos.filter(p => no(p, de - (ate - de), de));
  const soma = l => l.reduce((t, p) => t + (p.valor || 0), 0);
  const porPlano = {}, cupons = {};
  for (const p of periodo) { const k = (PLANOS[p.plano] || PLANOS.m1).nome; porPlano[k] = porPlano[k] || { n: 0, centavos: 0 }; porPlano[k].n++; porPlano[k].centavos += p.valor || 0; if (p.cupom) cupons[p.cupom] = (cupons[p.cupom] || 0) + 1; }
  const diasDe = []; for (let t = de; t < ate; t += DIA) diasDe.push(diaDe(t));
  const funil = {}; for (const dd of diasDe.slice(-31)) { const f = (await lerJson(env, "funil:" + dd)) || {}; for (const [k, v] of Object.entries(f)) if (typeof v === "number") funil[k] = (funil[k] || 0) + v; }
  let uso = null;
  const d = await db(env);
  if (d) { try { const r = (await d.prepare("SELECT tipo, SUM(n) AS n FROM contagem WHERE dia >= ? AND dia <= ? GROUP BY tipo").bind(diaDe(de), diaDe(ate)).all()).results || []; uso = Object.fromEntries(r.map(x => [x.tipo, x.n])); } catch (e) { } }
  const av = ((await lerJson(env, "avaliacoes")) || []).filter(a => a.em >= de && a.em < ate);
  const rl = ((await lerJson(env, "relatos")) || []).filter(r => r.em >= de && r.em < ate);
  const grupos = [];
  for (const mes of mesesEntre(de, ate)) {
    const m = (await lerJson(env, "erros:" + mes)) || {};
    for (const [sig, x] of Object.entries(m)) {
      const vezes = Object.entries(x.dias || {}).filter(([dd]) => dd >= diaDe(de) && dd <= diaDe(ate)).reduce((t, [, n]) => t + n, 0);
      const resolvidoAqui = x.resolvido && x.resolvido >= de && x.resolvido < ate;
      if (!vezes && !resolvidoAqui) continue;
      grupos.push({ sig, tipo: x.tipo, onde: x.onde, msg: x.msg, vezes, celulares: x.celulares.length, novo: x.primeiro >= de, resolvido: x.resolvido || 0, voltou: (x.reaberto || 0) >= de, modelos: Object.entries(x.modelos || {}).sort((a, b) => b[1] - a[1]).slice(0, 3).map(e => e[0]), versoes: Object.keys(x.versoes || {}).slice(-3), pilha: x.pilha || "", casos: (x.casos || []).filter(c => c.em >= de - DIA).slice(0, 2).map(c => ({ em: c.em, modelo: c.modelo, versao: c.versao, android: c.android, tela: c.tela, rastro: c.rastro, extra: c.extra, print: c.print })) });
    }
  }
  grupos.sort((a, b) => (b.celulares - a.celulares) || (b.vezes - a.vezes));
  let pirata = 0; for (const mes of mesesEntre(de, ate)) { const m = await lerJson(env, "pirata:" + mes); if (m) pirata += m.total || 0; }
  let botoes = null; if (d) { try { botoes = ((await d.prepare("SELECT COUNT(*) AS n FROM botoes WHERE em >= ? AND em < ?").bind(de, ate).first()) || {}).n || 0; } catch (e) { } }
  const nt = ((await lerJson(env, "notas")) || {})._todos || { n: 0, soma: 0 };
  const media = l => l.length ? Math.round(l.reduce((t, a) => t + a.estrelas, 0) * 10 / l.length) / 10 : 0;
  return {
    id: diaDe(ate) + (auto ? "" : "-" + idAleatorio(3).toLowerCase()), de, ate, gerado: Date.now(), auto: !!auto,
    vendas: { n: periodo.length, centavos: soma(periodo), total: reais(soma(periodo)), antesN: antes.length, antesCentavos: soma(antes), antesTotal: reais(soma(antes)), porPlano, cupons, presentes: periodo.filter(p => p.presente).length, indicacoes: periodo.filter(p => /^IND/.test(p.cupom || "")).length, pendentes: pedidos.filter(p => p.status !== "pago" && Date.parse(p.criado || 0) >= de).length },
    funil, uso,
    avaliacoes: { n: av.length, media: media(av), lista: av.slice(0, 40).map(a => ({ estrelas: a.estrelas, texto: a.texto, nome: a.nome || "", em: a.em, visivel: a.visivel })) },
    relatos: { n: rl.length, abertos: rl.filter(r => r.status === "novo").length, lista: rl.slice(0, 40).map(r => ({ em: r.em, texto: r.texto, modelo: r.modelo, versao: r.versao, pro: r.pro, contato: r.contato, status: r.status, tela: r.tela })) },
    erros: { tipos: grupos.length, vezes: grupos.reduce((t, g) => t + g.vezes, 0), crashes: grupos.filter(g => g.tipo === "crash").reduce((t, g) => t + g.vezes, 0), novos: grupos.filter(g => g.novo).length, voltaram: grupos.filter(g => g.voltou).length, resolvidos: grupos.filter(g => g.resolvido >= de).length, abertos: grupos.filter(g => !g.resolvido).length, lista: grupos.slice(0, 25) },
    pirataria: pirata, botoes, raiox: { n: nt.n, media: nt.n ? Math.round(nt.soma / nt.n) : 0 }
  };
}
async function salvarRelatorio(env, r) {
  await env.PCT.put("relatorio:" + r.id, JSON.stringify(r), { expirationTtl: 60 * 60 * 24 * 400 });
  const l = ((await lerJson(env, "relatorios")) || []).filter(x => x.id !== r.id);
  l.unshift({ id: r.id, de: r.de, ate: r.ate, auto: r.auto, vendas: r.vendas.n, total: r.vendas.total, erros: r.erros.vezes, novosErros: r.erros.novos, avaliacoes: r.avaliacoes.n, relatos: r.relatos.n });
  await env.PCT.put("relatorios", JSON.stringify(l.slice(0, 40)));
}
async function mandarRelatorio(env, r) {
  const est = n => "★".repeat(n) + "☆".repeat(5 - n);
  const dt = ms => new Date(ms - 3 * 3600000).toISOString().slice(0, 10).split("-").reverse().join("/");
  const varia = r.vendas.antesCentavos ? Math.round((r.vendas.centavos - r.vendas.antesCentavos) * 100 / r.vendas.antesCentavos) : null;
  const tab = l => `<table style="width:100%;font-size:14px;color:#E8EEF5" cellpadding="4">` + l.map(([a, b]) => `<tr><td style="color:#9AA8BA">${esc(a)}</td><td align="right">${esc(b)}</td></tr>`).join("") + `</table>`;
  const html = moldura("Relatório de " + dt(r.de) + " a " + dt(r.ate), r.vendas.n + " venda(s) · " + r.vendas.total + " · " + r.erros.vezes + " erro(s) · " + r.avaliacoes.n + " avaliação(ões)",
    `<div style="${CAIXA}"><div style="${ROT}">Vendas</div>` + tab([["Vendas", String(r.vendas.n)], ["Total", r.vendas.total], ["Período anterior", r.vendas.antesTotal + (varia === null ? "" : " (" + (varia >= 0 ? "+" : "") + varia + "%)")], ["Presentes", String(r.vendas.presentes)], ["Por indicação", String(r.vendas.indicacoes)]].concat(Object.entries(r.vendas.porPlano).map(([k, v]) => [k, v.n + " · " + reais(v.centavos)]))) + `</div>` +
    (r.uso ? `<div style="${CAIXA}"><div style="${ROT}">Uso do app</div>` + tab([["Aberturas (celular por dia)", String(r.uso.abriu || 0)], ["Modo completo ligou", String(r.uso.mc_ok || 0)], ["Modo completo falhou", String(r.uso.mc_falha || 0)]]) + `</div>` : "") +
    `<div style="${CAIXA}"><div style="${ROT}">Erros</div>` + tab([["Ocorrências", String(r.erros.vezes)], ["Fechamentos do app", String(r.erros.crashes)], ["Tipos de erro", String(r.erros.tipos)], ["Novos", String(r.erros.novos)], ["Voltaram", String(r.erros.voltaram)], ["Ainda abertos", String(r.erros.abertos)]]) +
    r.erros.lista.slice(0, 8).map(g => `<div style="margin-top:12px;border-top:1px solid #263142;padding-top:10px"><b>${esc(g.onde)}</b>${g.novo ? " · novo" : ""}<br><span style="color:#B8BEF0;font-size:13px">${esc(g.msg)}</span><br><span style="color:#9AA8BA;font-size:12px">${g.vezes}x em ${g.celulares} celular(es) · ${esc(g.modelos.join(", "))}</span></div>`).join("") + `</div>` +
    `<div style="${CAIXA}"><div style="${ROT}">Avaliações do site (média ${String(r.avaliacoes.media).replace(".", ",")})</div>` + (r.avaliacoes.n ? r.avaliacoes.lista.map(a => `<div style="margin-bottom:10px"><span style="color:#FFC857">${est(a.estrelas)}</span> <b>${esc(a.nome || "Anônimo")}</b><br>${esc(a.texto)}</div>`).join("") : "Nenhuma avaliação nova.") + `</div>` +
    `<div style="${CAIXA}"><div style="${ROT}">Relatos pelo app</div>` + (r.relatos.n ? r.relatos.lista.map(x => `<div style="margin-bottom:10px"><b>${esc(x.modelo || "?")}</b> · app ${esc(x.versao || "?")}${x.pro ? " · Pro" : ""}${x.contato ? " · " + esc(x.contato) : ""}<br>${esc(x.texto)}</div>`).join("") : "Nenhum relato novo.") + `</div>` +
    `<a href="https://serpyratoolkit.pages.dev/painel#relatorios" style="color:#C8FF6B">Ver o relatório completo no painel</a>`);
  const texto = "Relatório " + dt(r.de) + " a " + dt(r.ate) + "\nVendas: " + r.vendas.n + " (" + r.vendas.total + ")\nErros: " + r.erros.vezes + " (" + r.erros.novos + " novos)\nAvaliações: " + r.avaliacoes.n + "\nRelatos: " + r.relatos.n;
  return await mandarEmail(env, env.AVISO_EMAIL || env.REMETENTE_EMAIL, "SerpyraToolkit: relatório de " + dt(r.de) + " a " + dt(r.ate), html, texto);
}
async function resumoQuinzenal(env) {
  try {
    const ult = parseInt((await env.PCT.get("resumo_em")) || "0", 10) || 0;
    if (!ult) { await env.PCT.put("resumo_em", String(Date.now())); return; }
    const dias = (await config(env)).relatorioDias || 15;
    if (Date.now() - ult < dias * DIA - 3600000) return;
    await env.PCT.put("resumo_em", String(Date.now()));
    const r = await montarRelatorio(env, ult, Date.now(), true);
    await salvarRelatorio(env, r);
    await mandarRelatorio(env, r);
  } catch (e) { }
}
async function mensagens(env) {
  const l = await cacheJson("mensagens", 300, async () => (await lerJson(env, "mensagens")) || []);
  const agora = Date.now();
  return json({ mensagens: l.filter(m => (!m.inicio || agora >= m.inicio) && (!m.ate || agora <= m.ate)).map(m => ({ id: m.id, titulo: m.titulo, texto: m.texto, publico: m.publico, cupom: m.cupom || "" })) });
}
async function relato(req, env) {
  let b; try { b = await req.json(); } catch (e) { return json({ erro: "Pedido inválido." }, 400); }
  const codigo = limpaCodigo(b.codigo), texto = String(b.texto || "").trim().slice(0, 1500);
  if (!codigoValido(codigo)) return json({ erro: "Código do celular inválido." }, 400);
  if (texto.length < 5) return json({ erro: "Conte um pouco mais do que aconteceu." }, 400);
  if (!(await limitar("relato:" + codigo, 5)) || !(await limitar("relatos-total", 10))) return json({ erro: "Muitas mensagens agora. Tente de novo mais tarde." }, 429);
  const l = (await lerJson(env, "relatos")) || [];
  l.unshift({ id: Date.now().toString(36) + Math.random().toString(36).slice(2, 6), em: Date.now(), codigo, texto, contato: String(b.contato || "").trim().slice(0, 120), versao: String(b.versao || "").slice(0, 12), modelo: String(b.modelo || "").slice(0, 40), android: String(b.android || "").slice(0, 6), pro: !!b.pro, tela: String(b.tela || "").slice(0, 60), status: "novo" });
  await env.PCT.put("relatos", JSON.stringify(l.slice(0, 300)));
  return json({ ok: true });
}
async function pirata(req, env) {
  let b; try { b = await req.json(); } catch (e) { return json({ ok: true }); }
  const codigo = limpaCodigo(b.codigo);
  if (!(await limitar("pirata:" + (codigo || "x"), 3)) || !(await limitar("pirata-total", 4))) return json({ ok: true });
  const k = "pirata:" + mesAtual(), m = (await lerJson(env, k)) || { total: 0, celulares: [], certs: {}, versoes: {}, modelos: {} };
  m.total++; m.ultimo = Date.now();
  if (codigoValido(codigo) && !m.celulares.includes(codigo) && m.celulares.length < 300) m.celulares.push(codigo);
  const ce = String(b.cert || "?").slice(0, 16), ve = String(b.versao || "?").slice(0, 12), mo = String(b.modelo || "?").slice(0, 40);
  m.certs[ce] = (m.certs[ce] || 0) + 1; m.versoes[ve] = (m.versoes[ve] || 0) + 1; m.modelos[mo] = (m.modelos[mo] || 0) + 1;
  await env.PCT.put(k, JSON.stringify(m), { expirationTtl: 60 * 60 * 24 * 400 });
  return json({ ok: true });
}
function chaveModelo(m) { return String(m || "").toLowerCase().replace(/[^a-z0-9]+/g, " ").trim().slice(0, 40) || "?"; }
async function nota(req, url, env) {
  if (req.method === "POST") {
    let b; try { b = await req.json(); } catch (e) { return json({ ok: true }); }
    const codigo = limpaCodigo(b.codigo), n = Math.max(0, Math.min(100, parseInt(b.nota, 10) || 0)), mod = chaveModelo(b.modelo);
    if (codigoValido(codigo) && n && await limitar("nota:" + codigo, 1) && await limitar("notas-total", 4)) {
      const t = (await lerJson(env, "notas")) || { _todos: { n: 0, soma: 0, hist: Array(10).fill(0) } };
      for (const k of [mod, "_todos"]) {
        if (!t[k]) { if (Object.keys(t).length > 400) continue; t[k] = { n: 0, soma: 0, hist: Array(10).fill(0) }; }
        t[k].n++; t[k].soma += n; t[k].hist[Math.min(9, Math.floor(n / 10))]++;
      }
      await env.PCT.put("notas", JSON.stringify(t));
    }
  }
  const nota0 = Math.max(0, Math.min(100, parseInt(url.searchParams.get("nota") || "0", 10) || 0));
  const mod = chaveModelo(url.searchParams.get("modelo"));
  const t = await cacheJson("notas", 1800, async () => (await lerJson(env, "notas")) || {});
  let x = t[mod], de = "modelo";
  if (!x || x.n < 5) { x = t._todos; de = "todos"; }
  if (!x || !x.n) return json({ n: 0 });
  const b10 = Math.min(9, Math.floor(nota0 / 10)); let abaixo = 0; for (let i = 0; i < b10; i++) abaixo += x.hist[i];
  abaixo += x.hist[b10] / 2;
  return json({ n: x.n, media: Math.round(x.soma / x.n), melhorQue: Math.round(abaixo * 100 / x.n), de });
}

/* ---------------- erros do app, funil e promocao ---------------- */
function mesAtual() { return new Date(Date.now() - 3 * 3600000).toISOString().slice(0, 7); }
function diaAtual() { return new Date(Date.now() - 3 * 3600000).toISOString().slice(0, 10); }

async function config(env) { return await cacheJson("config", 60, async () => (await lerJson(env, "config")) || {}); }
function idAleatorio(n) { const r = crypto.getRandomValues(new Uint8Array(n)); return [...r].map(x => B32[x % 32]).join(""); }
async function registrarErro(req, env) {
  let b; try { b = await req.json(); } catch (e) { return json({ ok: true }); }
  const codigo = limpaCodigo(b.codigo);
  if (!codigoValido(codigo)) return json({ ok: true });
  const d = await db(env);
  // com o D1 cada ocorrencia vai para o banco; sem ele, o KV aguenta poucas por hora
  if (!(await limitar("erro:" + codigo, d ? 30 : 4)) || !(await limitar("erros-total", d ? 400 : 20))) return json({ ok: true });
  const lista = Array.isArray(b.erros) ? b.erros.slice(0, 10) : [b];
  const k = "erros:" + mesAtual();
  const m = (await lerJson(env, k)) || {};
  const mod = String(b.modelo || "?").slice(0, 40), ver = String(b.versao || "?").slice(0, 12), and = String(b.android || "?").slice(0, 6);
  const avisos = [];
  let printsAqui = 0;
  for (const e of lista) {
    const onde = String(e.onde || "app").slice(0, 60), msg = String(e.msg || "").slice(0, 200), tipo = e.tipo === "crash" ? "crash" : "falha";
    const sig = (tipo + "|" + onde + "|" + msg.replace(/\d+/g, "#")).slice(0, 180);
    const novo = !m[sig];
    const x = m[sig] || { tipo, onde, msg, n: 0, celulares: [], modelos: {}, versoes: {}, primeiro: Date.now(), pilha: String(e.pilha || "").slice(0, 1500) };
    x.n++; x.ultimo = Date.now();
    const dd = diaAtual(); x.dias = x.dias || {}; x.dias[dd] = (x.dias[dd] || 0) + 1;
    const ds = Object.keys(x.dias).sort(); if (ds.length > 35) ds.slice(0, ds.length - 35).forEach(k2 => delete x.dias[k2]);
    x.androids = x.androids || {}; x.androids[and] = (x.androids[and] || 0) + 1;
    const voltou = !!x.resolvido;
    if (x.resolvido) { x.reaberto = Date.now(); delete x.resolvido; }
    if (!x.celulares.includes(codigo) && x.celulares.length < 50) x.celulares.push(codigo);
    x.modelos[mod] = (x.modelos[mod] || 0) + 1; x.versoes[ver] = (x.versoes[ver] || 0) + 1;
    // o caso: onde estava, o caminho até ali e o print da tela do app
    const caso = { em: Number(e.em) > 0 && Number(e.em) < Date.now() + DIA ? Math.min(Number(e.em), Date.now()) : Date.now(), modelo: mod, versao: ver, android: and, codigo,
      tela: String(e.tela || "").slice(0, 80), rastro: (Array.isArray(e.rastro) ? e.rastro : []).slice(-12).map(r => String(r).slice(0, 60)),
      extra: Object.fromEntries(Object.entries(e.extra && typeof e.extra === "object" ? e.extra : {}).slice(0, 12).map(([a2, b2]) => [String(a2).slice(0, 24), String(b2).slice(0, 60)])), print: "" };
    const img = typeof e.print === "string" ? e.print.replace(/\s+/g, "") : "";
    if (img && img.length < 220000 && /^[A-Za-z0-9+/=]+$/.test(img) && printsAqui < 2 && (await limitar("prints", 30))) {
      const temPrint = (x.casos || []).filter(c => c.print);
      const ultimo = temPrint.length ? temPrint[0].em : 0;
      if (temPrint.length < 4 || Date.now() - ultimo > 6 * 3600000 || voltou) {
        caso.print = idAleatorio(20);
        await env.PCT.put("print:" + caso.print, img, { expirationTtl: 60 * 60 * 24 * 60 });
        printsAqui++;
      }
    }
    x.casos = [caso].concat(x.casos || []);
    // guarda os 6 mais recentes, mas não joga fora os que têm print enquanto houver espaço
    if (x.casos.length > 6) { const comP = x.casos.filter(c => c.print).slice(0, 4), semP = x.casos.filter(c => !c.print); x.casos = comP.concat(semP).sort((p1, p2) => p2.em - p1.em).slice(0, 6); }
    if (d) { try { await d.prepare("INSERT INTO erros (em, mes, sig, tipo, onde, msg, codigo, modelo, versao, android, tela, rastro, extra, print) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)").bind(caso.em, mesAtual(), sig, tipo, onde, msg, codigo, mod, ver, and, caso.tela, JSON.stringify(caso.rastro), JSON.stringify(caso.extra), caso.print).run(); } catch (e2) { } }
    m[sig] = x;
    avisos.push({ sig, x, caso, novo, voltou });
  }
  const chaves = Object.keys(m);
  if (chaves.length > 200) chaves.sort((a2, b2) => m[a2].ultimo - m[b2].ultimo).slice(0, chaves.length - 200).forEach(c => delete m[c]);
  // com o D1 ligado o KV só grava de vez em quando (o painel soma o resto pelo banco)
  if (!d || avisos.some(a2 => a2.novo || a2.voltou || a2.caso.print) || (await limitar("erros-kv", 20))) await env.PCT.put(k, JSON.stringify(m), { expirationTtl: 60 * 60 * 24 * 400 });
  for (const a2 of avisos) await avisarErro(env, a2, req.url);
  return json({ ok: true });
}
async function avisarErro(env, a, base) {
  try {
    const cfg = await config(env);
    if (cfg.emailErro === false) return;
    const chave = "aviso-erro:" + (await crypto.subtle.digest("SHA-1", new TextEncoder().encode(a.sig)).then(hex)).slice(0, 16);
    if (!(await limitar(chave, 1)) || !(await limitar("aviso-erro-total", 20))) return;
    const { x, caso } = a, origem = new URL(base).origin;
    const ti = (x.tipo === "crash" ? "💥 O app fechou: " : "⚠️ Erro no app: ") + x.onde + (a.novo ? " (novo)" : a.voltou ? " (voltou)" : "");
    const linhas = [["O que deu", x.msg || "—"], ["Tela", caso.tela || x.onde], ["Celular", caso.modelo + " · Android " + caso.android], ["Versão do app", caso.versao], ["Vezes este mês", String(x.n) + " em " + x.celulares.length + " celular(es)"], ["Quando", agoraBr()]].concat(Object.entries(caso.extra || {}).slice(0, 6));
    const img = caso.print ? `<div style="${CAIXA};text-align:center"><div style="${ROT}">Print da tela do app na hora do erro</div><img src="${origem}/print?id=${caso.print}" alt="Print da tela do app" style="max-width:260px;width:100%;border-radius:14px;border:1px solid #3A3480"></div>` : "";
    const rastro = caso.rastro && caso.rastro.length ? `<div style="${CAIXA}"><div style="${ROT}">Caminho até o erro</div>` + caso.rastro.map((r, i) => `<div style="padding:3px 0">${i + 1}. ${esc(r)}</div>`).join("") + `</div>` : "";
    const html = moldura(ti, a.novo ? "Esse erro apareceu pela primeira vez agora." : a.voltou ? "Você tinha marcado como resolvido, mas aconteceu de novo." : "Mais uma vez (no máximo 1 e-mail por hora para o mesmo erro).",
      `<div style="${CAIXA}"><table style="width:100%;font-size:14px;color:#E8EEF5" cellpadding="4">` + linhas.map(([l1, l2]) => `<tr><td style="color:#9AA8BA;vertical-align:top">${esc(l1)}</td><td align="right">${esc(l2)}</td></tr>`).join("") + `</table></div>` + img + rastro +
      (x.pilha ? `<div style="${CAIXA}"><div style="${ROT}">Onde no código</div><pre style="white-space:pre-wrap;font-size:12px;margin:0;color:#B8BEF0">${esc(x.pilha)}</pre></div>` : "") + `<a href="https://serpyratoolkit.pages.dev/painel#erros" style="color:#C8FF6B">Abrir os erros no painel</a>`);
    await mandarEmail(env, env.AVISO_EMAIL || env.REMETENTE_EMAIL, ti, html, ti + "\n" + linhas.map(l => l.join(": ")).join("\n") + (caso.rastro && caso.rastro.length ? "\nCaminho: " + caso.rastro.join(" > ") : ""));
  } catch (e) { }
}
/* área do dono no app: entra com a chave do dono (definida no painel) e recebe um passe só para este celular */
async function passeDono(env, codigo) {
  const k = await crypto.subtle.importKey("raw", new TextEncoder().encode(String(env.ADMIN_TOKEN || "")), { name: "HMAC", hash: "SHA-256" }, false, ["sign"]);
  return hex(await crypto.subtle.sign("HMAC", k, new TextEncoder().encode("dono|" + codigo)));
}
async function donoEntrar(req, env) {
  let b; try { b = await req.json(); } catch (e) { return json({ erro: "Pedido inválido." }, 400); }
  const codigo = limpaCodigo(b.codigo), chave = limpaCupom(b.chave);
  if (!codigoValido(codigo) || !env.ADMIN_TOKEN) return json({ erro: "Pedido inválido." }, 400);
  if (!(await limitar("dono:" + (req.headers.get("cf-connecting-ip") || "x"), 5))) return json({ erro: "Muitas tentativas. Tente de novo em uma hora." }, 429);
  const c = (await lerJson(env, "config")) || {};
  if (!c.donoChave || !igual(chave, c.donoChave)) return json({ erro: "Chave errada." }, 401);
  return json({ ok: true, passe: await passeDono(env, codigo) });
}
async function donoRota(req, url, env) {
  const codigo = limpaCodigo(url.searchParams.get("codigo"));
  if (!codigoValido(codigo) || !igual(req.headers.get("x-dono"), await passeDono(env, codigo))) return json({ erro: "Token do painel incorreto." }, 401);
  const u = new URL(url); u.pathname = url.pathname.replace("/dono/", "/admin/");
  return admin(new Request(u, { method: "GET", headers: { "x-admin": env.ADMIN_TOKEN } }), u, env);
}
async function verPrint(url, env) {
  const id = String(url.searchParams.get("id") || "");
  if (!/^[A-Z2-9]{20}$/.test(id)) return json({ erro: "Não encontrado" }, 404);
  const b = await env.PCT.get("print:" + id);
  if (!b) return json({ erro: "Esse print já expirou (ficam 60 dias)." }, 404);
  return new Response(b64ToBytes(b), { headers: { "content-type": "image/jpeg", "cache-control": "private, max-age=86400", ...CORS } });
}

async function evento(req, env) {
  let b; try { b = await req.json(); } catch (e) { return json({ ok: true }); }
  const codigo = limpaCodigo(b.codigo);
  if (!codigoValido(codigo)) return json({ ok: true });
  if (b.tipo === "abriu" || b.tipo === "mc") {
    try {
      if (b.tipo === "abriu") { if (await limitar("abriu:" + codigo + ":" + diaAtual(), 1)) await contar(env, "abriu", b.pro ? "pro" : "gratis"); }
      else if (await limitar("mc:" + codigo, 6)) await contar(env, b.ok ? "mc_ok" : "mc_falha", String(b.marca || "?").toLowerCase().slice(0, 30) + (b.ok ? "" : "|" + String(b.etapa || "?").slice(0, 40)));
    } catch (e) { }
    return json({ ok: true });
  }
  const tipo = b.tipo === "pro" ? "pro" : "";
  if (!tipo) return json({ ok: true });
  // uma vez por celular por dia, e no maximo 400 gravacoes por dia no total
  if (!(await limitar("ev:" + tipo + ":" + codigo + ":" + diaAtual(), 1))) return json({ ok: true });
  const n = await contarCache("evc:" + tipo + ":" + diaAtual());
  if (n % 10 !== 0) return json({ ok: true });
  const k = "funil:" + diaAtual();
  const f = (await lerJson(env, k)) || {};
  f[tipo] = (f[tipo] || 0) + 10;
  await env.PCT.put(k, JSON.stringify(f), { expirationTtl: 60 * 60 * 24 * 120 });
  return json({ ok: true });
}

async function promoAtual(env) {
  const cache = caches.default;
  const req = new Request("https://promo.local/atual");
  const r = await cache.match(req);
  let corpo = r ? await r.text() : null;
  if (corpo === null) {
    const p = (await lerJson(env, "promo")) || {};
    corpo = JSON.stringify(p);
    await cache.put(req, new Response(corpo, { headers: { "cache-control": "max-age=120" } }));
  }
  const p = JSON.parse(corpo || "{}");
  if (!p.texto || (p.ate && Date.now() > p.ate) || (p.inicio && Date.now() < p.inicio)) return json({});
  return json(p);
}

/* ---------------- avisos e pesquisa ---------------- */
/* o app avisa qual chave esta usando: assim o codigo de indicacao dele vale e o painel enxerga o cliente */
async function registrar(req, env) {
  let b; try { b = await req.json(); } catch (e) { return json({ erro: "Pedido inválido." }, 400); }
  const k = await conferirChave(b.chave);
  if (!k || k.codigo !== limpaCodigo(b.codigo)) return json({ erro: "Chave inválida." }, 400);
  if (k.dia < hoje()) return json({ erro: "Chave vencida." }, 410);
  if (!(await limitar("reg:" + k.codigo, 6))) return json({ ok: true });
  const t = parseInt((await env.PCT.get("teste:" + k.codigo)) || "0", 10);
  if (t && t === k.dia) return json({ ok: true, teste: true });
  const c = await lerJson(env, "chave:" + k.codigo);
  if (c && c.revogada) return json({ status: "revogada" }, 410);
  if (c && c.dia >= k.dia) return json({ ok: true });
  // a chave vitalicia do dono nao fica guardada em texto no servidor
  await env.PCT.put("chave:" + k.codigo, JSON.stringify({ ...(c || {}), ate: undefined, chave: k.dia >= DIA_VITALICIO ? "" : String(b.chave).trim(), dia: k.dia, email: (c && c.email) || "", origem: (c && c.origem) || "registrada" }));
  return json({ ok: true });
}

async function avisoAtual(env) {
  const cache = caches.default;
  const req = new Request("https://aviso.local/atual");
  const r = await cache.match(req);
  if (r) return new Response(r.body, { headers: { "content-type": "application/json; charset=utf-8", ...CORS } });
  const a = (await lerJson(env, "aviso")) || {};
  const corpo = JSON.stringify(a);
  await cache.put(req, new Response(corpo, { headers: { "cache-control": "max-age=300" } }));
  return new Response(corpo, { headers: { "content-type": "application/json; charset=utf-8", ...CORS } });
}

async function pesquisa(req, env) {
  let b; try { b = await req.json(); } catch (e) { return json({ erro: "Pedido inválido." }, 400); }
  const codigo = limpaCodigo(b.codigo);
  const m = parseInt(b.motivo, 10);
  if (!codigoValido(codigo) || !(m >= 0 && m < MOTIVOS.length)) return json({ erro: "Resposta inválida." }, 400);
  if (!(await limitar("pesquisa:" + codigo, 2))) return json({ ok: true });
  const t = (await lerJson(env, "pesquisa")) || {};
  t[MOTIVOS[m]] = (t[MOTIVOS[m]] || 0) + 1;
  await env.PCT.put("pesquisa", JSON.stringify(t));
  return json({ ok: true });
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
  if (p.status === "pago" && p.site) return json({ status: "pago", site: true, vouchers: p.vouchers || [p.voucher], numero: p.numero, email: p.email_status === "enviado" });
  if (p.status === "pago" && p.presente) return json({ status: "pago", presente: true, voucher: p.voucher, para: p.presente, numero: p.numero, email: p.email_status === "enviado" });
  if (p.status === "pago") return json({ status: "pago", chave: p.chave, numero: p.numero, validade: validadeTxt(p.dia), email: p.email_status === "enviado", familia: p.familia || [] });
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
  const ip = req.headers.get("cf-connecting-ip") || "";
  if (await env.PCT.get("teste:" + codigo)) return json({ erro: "O teste grátis deste celular já foi usado. Veja os planos do Pro." }, 409);
  if (ip && await env.PCT.get("testeip:" + ip)) return json({ erro: "O teste grátis já foi usado nesta internet. Veja os planos do Pro." }, 409);
  if (await validadeAtual(env, codigo)) return json({ erro: "Este celular já tem ou já teve o Pro. Use Já comprei para recuperar." }, 409);
  if (!(await limitar("teste", 60))) return json({ erro: "Muitos testes agora. Tente mais tarde." }, 429);
  const ate = Date.now() + TESTE_MIN * 60000, dia = Math.floor(ate / DIA);
  const chave = await assinarChave(env, codigo, dia);
  await env.PCT.put("teste:" + codigo, String(dia));
  if (ip) await env.PCT.put("testeip:" + ip, codigo, { expirationTtl: 60 * 60 * 24 * 30 });
  return json({ chave, validade: "1 hora", ate, minutos: TESTE_MIN, dias: 0 });
}

async function licenca(url, env) {
  const codigo = limpaCodigo(url.searchParams.get("codigo"));
  if (!codigoValido(codigo)) return json({ erro: "Código inválido." }, 400);
  const o = await lerJson(env, "chave:" + codigo);
  if (!o) return json({ status: "nenhuma" }, 404);
  if (o.revogada) return json({ status: "revogada" }, 410);
  if (o.origem === "pc" && o.email) {
    const em = await lerJson(env, "email:" + o.email);
    if (em && em.pc === codigo && em.dia > o.dia) { o.dia = em.dia; o.chave = await assinarChave(env, codigo, em.dia); await env.PCT.put("chave:" + codigo, JSON.stringify(o)); }
  }
  if (o.dia < hoje() || (o.ate && Date.now() > o.ate)) return json({ status: "vencida", validade: o.ate ? horaBr(o.ate) : validadeTxt(o.dia) }, 404);
  return json({ status: "ativa", chave: o.chave, validade: o.ate ? "até " + horaBr(o.ate) : validadeTxt(o.dia), ate: o.ate || undefined });
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
  await env.PCT.put("email:" + email, JSON.stringify({ codigo, dia: em.dia, troca: hoje(), otp: "", pc: em.pc, pcEm: em.pcEm }));
  return json({ ok: true, chave, validade: validadeTxt(em.dia) });
}

/* ---------------- PC: o mesmo Pro do celular (um PC por e-mail de compra) ---------------- */
async function pcPedir(req, env) {
  let b; try { b = await req.json(); } catch (e) { return json({ erro: "Pedido inválido." }, 400); }
  const email = String(b.email || "").trim().toLowerCase(), codigo = limpaCodigo(b.codigo);
  if (!emailValido(email) || !codigoValido(codigo)) return json({ erro: "Confira o e-mail." }, 400);
  if (!(await limitar("pc:" + email, 4))) return json({ erro: "Muitas tentativas. Tente de novo em uma hora." }, 429);
  const em = await lerJson(env, "email:" + email);
  if (!em) return json({ erro: "Não encontrei Pro com esse e-mail. Use o e-mail da compra." }, 404);
  if (em.dia < hoje()) return json({ erro: "O Pro desse e-mail venceu em " + dataBr(em.dia) + ". Renove para usar no PC." }, 410);
  if (em.pc === codigo) { const c = await lerJson(env, "chave:" + codigo); if (c && !c.revogada) return json({ ok: true, chave: c.chave, validade: validadeTxt(c.dia), jaLigado: true }); }
  if (em.pc && em.pcEm && hoje() - em.pcEm < TROCA_INTERVALO) return json({ erro: "Esse Pro já está em outro PC desde " + dataBr(em.pcEm) + ". Dá para trocar de PC a partir de " + dataBr(em.pcEm + TROCA_INTERVALO) + "." }, 429);
  const otp = String(crypto.getRandomValues(new Uint32Array(1))[0] % 1000000).padStart(6, "0");
  em.pcOtp = otp; em.pcOtpPara = codigo; em.pcOtpAte = Date.now() + 15 * 60000; em.pcOtpErros = 0;
  await env.PCT.put("email:" + email, JSON.stringify(em));
  const html = moldura("Código para usar o Pro no PC", "Alguém pediu para ligar o seu SerpyraToolkit Pro num computador.",
    `<div style="${CAIXA}"><div style="${ROT}">Seu código</div><div style="font-size:34px;font-weight:bold;letter-spacing:6px;color:#C8FF6B">${otp}</div><div style="color:#9AA8BA;font-size:13px;margin-top:12px">Digite no SerpyraToolkit para PC. Vale por 15 minutos. O Pro continua no seu celular. Se não foi você, ignore este e-mail.</div></div>`);
  const st = await mandarEmail(env, email, "Seu código para usar o Pro no PC: " + otp, html, "Seu código para usar o SerpyraToolkit Pro no PC: " + otp + "\nVale por 15 minutos. Se não foi você, ignore.");
  if (st !== "enviado") return json({ erro: "Não consegui enviar o e-mail agora. Tente mais tarde." }, 502);
  return json({ ok: true });
}
async function pcConfirmar(req, env) {
  let b; try { b = await req.json(); } catch (e) { return json({ erro: "Pedido inválido." }, 400); }
  const email = String(b.email || "").trim().toLowerCase(), codigo = limpaCodigo(b.codigo), otp = String(b.otp || "").replace(/\D/g, "");
  const em = await lerJson(env, "email:" + email);
  if (!em || !em.pcOtp || em.pcOtpPara !== codigo || Date.now() > (em.pcOtpAte || 0)) return json({ erro: "Código vencido. Peça um novo." }, 410);
  if (!igual(otp, em.pcOtp)) {
    em.pcOtpErros = (em.pcOtpErros || 0) + 1; if (em.pcOtpErros >= 5) em.pcOtp = "";
    await env.PCT.put("email:" + email, JSON.stringify(em));
    return json({ erro: em.pcOtp ? "Código errado." : "Código errado várias vezes. Peça um novo." }, 401);
  }
  if (em.pc && em.pc !== codigo) { const velho = await lerJson(env, "chave:" + em.pc); if (velho && velho.origem === "pc") { velho.revogada = true; await env.PCT.put("chave:" + em.pc, JSON.stringify(velho)); } }
  const chave = await assinarChave(env, codigo, em.dia);
  await env.PCT.put("chave:" + codigo, JSON.stringify({ chave, dia: em.dia, email, origem: "pc" }));
  em.pc = codigo; em.pcEm = hoje(); em.pcOtp = ""; delete em.pcOtpPara; delete em.pcOtpAte; delete em.pcOtpErros;
  await env.PCT.put("email:" + email, JSON.stringify(em));
  return json({ ok: true, chave, validade: validadeTxt(em.dia) });
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
    const porPlano = {}; for (const p of pagos) { const k = (PLANOS[p.plano] || PLANOS.m1).nome + (p.presente ? " (presente)" : ""); porPlano[k] = (porPlano[k] || 0) + 1; }
    const dias30 = []; for (let d = 29; d >= 0; d--) { const ini = Date.now() - 3 * 3600000; const dd = new Date(ini - d * DIA).toISOString().slice(0, 10); const l = pagos.filter(p => new Date(Date.parse(p.pagoEm) - 3 * 3600000).toISOString().slice(0, 10) === dd); dias30.push({ dia: dd.slice(8, 10) + "/" + dd.slice(5, 7), vendas: l.length, centavos: soma(l) }); }
    const indicacoes = pagos.filter(p => /^IND/.test(p.cupom || "")).length;
    return json({
      hoje: { vendas: desde(1).length, total: reais(soma(desde(1))) },
      semana: { vendas: desde(7).length, total: reais(soma(desde(7))) },
      mes: { vendas: desde(30).length, total: reais(soma(desde(30))) },
      tudo: { vendas: pagos.length, total: reais(soma(pagos)) },
      pendentes: pedidos.length - pagos.length, porPlano, dias30, indicacoes, pesquisa: (await lerJson(env, "pesquisa")) || {}, aviso: (await lerJson(env, "aviso")) || {}, emailsPendentes: pagos.filter(p => p.email_status !== "enviado").length,
      ultimas: pagos.slice(0, 60).map(p => ({ numero: p.numero, data: p.pagoEm, email: p.email, codigo: p.codigo, plano: (PLANOS[p.plano] || PLANOS.m1).nome + (p.presente ? " (presente p/ " + p.presente + ")" : ""), valor: reais(p.valor), cupom: p.cupom || "", validade: p.presente ? "código " + (p.voucher || "") : (p.dia >= DIA_VITALICIO ? "vitalício" : dataBr(p.dia)), email_ok: p.email_status === "enviado" })),
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
    return json({ chave, validade: validadeTxt(dia) });
  }
  if (rota === "cupons" && req.method === "GET") {
    const nomes = await listar(env, "cupom:", 500);
    const l = await Promise.all(nomes.map(async n => ({ codigo: n.slice(6), ...(await lerJson(env, n)) })));
    return json({ cupons: l.map(c => ({ ...c, receita: reais(c.receita || 0), comissao: c.comissao != null ? c.comissao : COMISSAO_PADRAO, aPagar: reais(Math.max(0, (c.comissaoTotal || 0) - (c.comissaoPaga || 0))), aPagarC: Math.max(0, (c.comissaoTotal || 0) - (c.comissaoPaga || 0)), comissaoTotal: reais(c.comissaoTotal || 0), pagoEm: c.pagoEm || "" })) });
  }
  if (rota === "cupom" && req.method === "POST") {
    const b = await req.json().catch(() => ({}));
    const cod = limpaCupom(b.codigo);
    const pct = parseInt(b.pct, 10);
    if (cod.length < 3) return json({ erro: "O cupom precisa de pelo menos 3 letras ou números." }, 400);
    if (!(pct >= 1 && pct <= 90)) return json({ erro: "Desconto entre 1% e 90%." }, 400);
    const atual = (await lerJson(env, "cupom:" + cod)) || { usos: 0, receita: 0, criado: new Date().toISOString() };
    atual.pct = pct; atual.ativo = b.ativo !== false; atual.limite = parseInt(b.limite, 10) || 0; atual.dono = String(b.dono || "").slice(0, 60);
    const com = parseInt(b.comissao, 10); atual.comissao = com >= 0 && com <= 90 ? com : (atual.comissao != null ? atual.comissao : COMISSAO_PADRAO);
    await env.PCT.put("cupom:" + cod, JSON.stringify(atual));
    return json({ ok: true });
  }
  if (rota === "cupom-pago" && req.method === "POST") {
    const b = await req.json().catch(() => ({}));
    const cod = limpaCupom(b.codigo), c = await lerJson(env, "cupom:" + cod);
    if (!c) return json({ erro: "Cupom não encontrado." }, 404);
    const valor = Math.max(0, (c.comissaoTotal || 0) - (c.comissaoPaga || 0));
    c.comissaoPaga = c.comissaoTotal || 0; c.pagoEm = new Date().toISOString();
    c.pagamentos = (c.pagamentos || []).concat([{ em: c.pagoEm, valor }]).slice(-50);
    await env.PCT.put("cupom:" + cod, JSON.stringify(c));
    return json({ ok: true, pago: reais(valor) });
  }
  if (rota === "cupom" && req.method === "DELETE") {
    await env.PCT.delete("cupom:" + limpaCupom(url.searchParams.get("c")));
    return json({ ok: true });
  }
  if (rota === "reenviar" && req.method === "POST") return json(await reenviarEmails(env));
  if (rota === "cortesia" && req.method === "POST") {
    const b = await req.json().catch(() => ({}));
    const vital = !!b.vitalicio;
    const horas = parseInt(b.horas, 10) || 0;
    const dias = horas ? 0 : parseInt(b.dias, 10);
    if (horas && !(horas >= 1 && horas <= 720)) return json({ erro: "Escolha de 1 a 720 horas." }, 400);
    if (!vital && !horas && !(dias >= 1 && dias <= 3650)) return json({ erro: "Escolha de 1 a 3650 dias (ou vitalício)." }, 400);
    const qtd = Math.min(20, Math.max(1, parseInt(b.qtd, 10) || 1));
    const nota = String(b.nota || "").trim().slice(0, 60);
    const codigos = [];
    for (let i = 0; i < qtd; i++) {
      const v = novoVoucher();
      await env.PCT.put("presente:" + v, JSON.stringify({ plano: "cortesia", dias: vital ? 0 : dias, horas: vital ? 0 : horas, vitalicio: vital, de: "cortesia", para: nota, usado: "", criado: new Date().toISOString() }));
      codigos.push(v);
    }
    return json({ codigos, duracao: vital ? "vitalício" : horas ? horas + " hora(s)" : dias + " dia(s)" });
  }
  if (rota === "cortesias" && req.method === "GET") {
    const nomes = await listar(env, "presente:", 1000);
    const l = (await Promise.all(nomes.map(async n => ({ codigo: n.slice(9), ...(await lerJson(env, n)) })))).filter(x => x && x.plano);
    l.sort((a, b) => String(b.criado || b.em || "").localeCompare(String(a.criado || a.em || "")));
    return json({ cortesias: l.slice(0, 300).map(x => ({ codigo: x.codigo, tipo: x.de === "cortesia" ? "cortesia" : "presente pago", duracao: x.vitalicio ? "vitalício" : x.horas ? x.horas + " hora(s)" : (x.dias || 30) + " dias", para: x.para || "", usado: x.usado || "", em: x.em || "", validade: x.dia ? validadeTxt(x.dia) : "", criado: x.criado || "" })) });
  }
  if (rota === "cortesia" && req.method === "DELETE") {
    const v = limpaCupom(url.searchParams.get("c"));
    const g = await lerJson(env, "presente:" + v);
    if (!g) return json({ erro: "Código não encontrado." }, 404);
    if (g.usado) return json({ erro: "Esse código já foi usado. Para tirar o Pro, use Clientes > Bloquear." }, 409);
    await env.PCT.delete("presente:" + v);
    return json({ ok: true });
  }
  if (rota === "cliente" && req.method === "GET") {
    const q = String(url.searchParams.get("q") || "").trim().toLowerCase();
    let codigo = limpaCodigo(q);
    let em = null;
    if (q.includes("@")) { em = await lerJson(env, "email:" + q); codigo = em ? em.codigo : ""; }
    if (!codigoValido(codigo)) return json({ erro: em === null && q.includes("@") ? "Nenhuma compra com esse e-mail." : "Digite o código do celular (8 letras/números) ou o e-mail." }, 404);
    const c = await lerJson(env, "chave:" + codigo);
    const teste = await env.PCT.get("teste:" + codigo);
    const nomes = await listar(env, "pedido:", 3000);
    const pedidos = (await Promise.all(nomes.map(n => lerJson(env, n)))).filter(p => p && p.status === "pago" && (p.codigo === codigo || (c && c.email && p.email === c.email)));
    return json({ codigo, existe: !!c, validade: c ? validadeTxt(c.dia) : "", dias: c ? c.dia - hoje() : 0, vitalicio: !!(c && c.dia >= DIA_VITALICIO), email: (c && c.email) || "", origem: (c && c.origem) || (c ? "compra" : ""), revogada: !!(c && c.revogada), indicacoes: (c && c.indicacoes) || 0, teste: teste ? validadeTxt(parseInt(teste, 10)) : "",
      compras: pedidos.map(p => ({ numero: p.numero, data: p.pagoEm, plano: (PLANOS[p.plano] || PLANOS.m1).nome, valor: reais(p.valor) })) });
  }
  if (rota === "cliente" && req.method === "POST") {
    const b = await req.json().catch(() => ({}));
    const codigo = limpaCodigo(b.codigo);
    if (!codigoValido(codigo)) return json({ erro: "Código inválido." }, 400);
    const c = (await lerJson(env, "chave:" + codigo)) || { email: "", origem: "painel" };
    if (b.acao === "bloquear") { c.revogada = true; await env.PCT.put("chave:" + codigo, JSON.stringify(c)); return json({ ok: true, msg: "Bloqueado. O Pro sai do celular na próxima vez que o app conferir (em até 12 horas com internet)." }); }
    let dia;
    if (b.acao === "vitalicio") dia = DIA_VITALICIO;
    else {
      const d = parseInt(b.dias, 10);
      if (!(d >= -3650 && d <= 3650) || d === 0) return json({ erro: "Dias inválidos." }, 400);
      dia = Math.max(hoje(), c.dia && c.dia < DIA_VITALICIO ? c.dia : hoje()) + d;
      if (dia <= hoje()) dia = hoje() - 1;
    }
    const chave = await assinarChave(env, codigo, dia);
    delete c.revogada;
    await env.PCT.put("chave:" + codigo, JSON.stringify({ ...c, ate: undefined, chave, dia }));
    return json({ ok: true, chave, validade: validadeTxt(dia), msg: "Pronto. O celular recebe sozinho ao abrir o app (ou cole a chave)." });
  }
  if (rota === "reenviar-chave" && req.method === "POST") {
    const b = await req.json().catch(() => ({}));
    const codigo = limpaCodigo(b.codigo);
    const c = await lerJson(env, "chave:" + codigo);
    if (!c || !c.email) return json({ erro: "Esse cliente não tem e-mail guardado." }, 404);
    if (!c.chave) return json({ erro: "Não há chave guardada para reenviar." }, 404);
    const m = emailCompra({ numero: "reenvio", codigo, chave: c.chave, valor: "—", plano: "reenvio da chave", cupom: "", validade: validadeTxt(c.dia), dataPago: agoraBr() });
    const st = await mandarEmail(env, c.email, "Sua chave do SerpyraToolkit Pro", m.html, m.texto);
    return st === "enviado" ? json({ ok: true }) : json({ erro: "Falhou: " + st }, 502);
  }
  if (rota === "erros" && req.method === "GET") {
    const mes = /^\d{4}-\d{2}$/.test(url.searchParams.get("mes") || "") ? url.searchParams.get("mes") : mesAtual();
    const m = (await lerJson(env, "erros:" + mes)) || {};
    const dbe = await db(env);
    if (dbe) { try { for (const r of ((await dbe.prepare("SELECT sig, COUNT(*) AS n, MAX(em) AS u FROM erros WHERE mes = ? GROUP BY sig").bind(mes).all()).results || [])) { const x = m[r.sig]; if (x) { x.n = Math.max(x.n, r.n); x.ultimo = Math.max(x.ultimo || 0, r.u || 0); } } } catch (e) { } }
    const hojeD = diaAtual(), semana = []; for (let k = 0; k < 7; k++) semana.push(new Date(Date.now() - 3 * 3600000 - k * DIA).toISOString().slice(0, 10));
    const nDias = (x, ds) => ds.reduce((t, d) => t + ((x.dias || {})[d] || 0), 0);
    const todos = Object.entries(m).map(([sig, x]) => ({ sig, ...x }));
    const abertos = todos.filter(x => !x.resolvido);
    const l = todos.sort((a, b) => ((a.resolvido ? 1 : 0) - (b.resolvido ? 1 : 0)) || b.celulares.length - a.celulares.length || b.n - a.n);
    const meses = (await listar(env, "erros:", 50)).map(n => n.slice(6)).sort().reverse();
    return json({ mes, meses: meses.length ? meses : [mes], total: l.reduce((t, x) => t + x.n, 0), celulares: new Set(l.flatMap(x => x.celulares)).size, crashes: l.filter(x => x.tipo === "crash").reduce((t, x) => t + x.n, 0),
      hoje: abertos.reduce((t, x) => t + nDias(x, [hojeD]), 0), semana: abertos.reduce((t, x) => t + nDias(x, semana), 0), abertos: abertos.length, novosHoje: abertos.filter(x => x.primeiro > Date.now() - DIA).length,
      erros: l.slice(0, 150).map(x => ({ sig: x.sig, tipo: x.tipo, onde: x.onde, msg: x.msg, n: x.n, celulares: x.celulares.length, modelos: x.modelos, versoes: x.versoes, androids: x.androids || {}, dias: x.dias || {}, primeiro: x.primeiro, ultimo: x.ultimo, pilha: x.pilha, resolvido: x.resolvido || 0, reaberto: x.reaberto || 0, casos: (x.casos || []).map(c => ({ ...c, codigo: undefined })) })) });
  }
  if (rota === "erros" && req.method === "POST") {
    const b = await req.json().catch(() => ({}));
    const mes = /^\d{4}-\d{2}$/.test(b.mes || "") ? b.mes : mesAtual();
    const k = "erros:" + mes, m = (await lerJson(env, k)) || {};
    if (b.acao === "limpar") { for (const s2 of Object.keys(m)) if (m[s2].resolvido) delete m[s2]; }
    else { const x = m[String(b.sig || "")]; if (!x) return json({ erro: "Erro não encontrado." }, 404); if (b.acao === "reabrir") delete x.resolvido; else { x.resolvido = Date.now(); delete x.reaberto; } }
    await env.PCT.put(k, JSON.stringify(m), { expirationTtl: 60 * 60 * 24 * 400 });
    return json({ ok: true });
  }

  if (rota === "funil" && req.method === "GET") {
    const nomes = await listar(env, "pedido:", 3000);
    const pedidos = (await Promise.all(nomes.map(n => lerJson(env, n)))).filter(Boolean);
    const dias = [];
    for (let d = 29; d >= 0; d--) {
      const dd = new Date(Date.now() - 3 * 3600000 - d * DIA).toISOString().slice(0, 10);
      const f = (await lerJson(env, "funil:" + dd)) || {};
      const doDia = pedidos.filter(p => new Date(Date.parse(p.criado || p.pagoEm || 0) - 3 * 3600000).toISOString().slice(0, 10) === dd);
      dias.push({ dia: dd.slice(8, 10) + "/" + dd.slice(5, 7), pro: f.pro || 0, pix: doDia.length, pagos: doDia.filter(p => p.status === "pago").length });
    }
    const t = dias.reduce((a, x) => ({ pro: a.pro + x.pro, pix: a.pix + x.pix, pagos: a.pagos + x.pagos }), { pro: 0, pix: 0, pagos: 0 });
    return json({ dias, total: t });
  }
  if (rota === "promo" && req.method === "POST") {
    const b = await req.json().catch(() => ({}));
    const texto = String(b.texto || "").trim().slice(0, 200);
    if (!texto) await env.PCT.delete("promo");
    else {
      const horas = Math.min(24 * 60, Math.max(0, parseInt(b.horas, 10) || 0));
      const ini = Math.max(Date.now(), parseInt(b.inicio, 10) || 0);
    await env.PCT.put("promo", JSON.stringify({ id: Date.now(), texto, cupom: limpaCupom(b.cupom), cor: /^(verde|amarelo|vermelho|azul)$/.test(b.cor) ? b.cor : "verde", inicio: ini > Date.now() + 60000 ? ini : 0, ate: horas ? ini + horas * 3600000 : 0 }));
    }
    try { await caches.default.delete(new Request("https://promo.local/atual")); } catch (e) { }
    return json({ ok: true });
  }
  if (rota === "promo" && req.method === "GET") return json((await lerJson(env, "promo")) || {});
  if (rota === "mensagem" && req.method === "POST") {
    const b = await req.json().catch(() => ({}));
    const titulo = String(b.titulo || "").trim().slice(0, 60), texto = String(b.texto || "").trim().slice(0, 240);
    if (!titulo || !texto) return json({ erro: "Escreva o título e o texto." }, 400);
    const publico = ["todos", "gratis", "pro", "vencidos"].includes(b.publico) ? b.publico : "todos";
    const ini = Math.max(Date.now(), parseInt(b.inicio, 10) || 0), horas = Math.min(24 * 30, Math.max(1, parseInt(b.horas, 10) || 48));
    const l = (await lerJson(env, "mensagens")) || [];
    l.unshift({ id: Date.now(), titulo, texto, publico, cupom: limpaCupom(b.cupom), inicio: ini > Date.now() + 60000 ? ini : 0, ate: ini + horas * 3600000, criado: Date.now() });
    await env.PCT.put("mensagens", JSON.stringify(l.filter(m => !m.ate || m.ate > Date.now() - 7 * DIA).slice(0, 30)));
    try { await caches.default.delete(new Request("https://cache.local/mensagens")); } catch (e) { }
    return json({ ok: true });
  }
  if (rota === "mensagem" && req.method === "DELETE") {
    const id = parseInt(url.searchParams.get("id"), 10);
    const l = ((await lerJson(env, "mensagens")) || []).filter(m => m.id !== id);
    await env.PCT.put("mensagens", JSON.stringify(l));
    try { await caches.default.delete(new Request("https://cache.local/mensagens")); } catch (e) { }
    return json({ ok: true });
  }
  if (rota === "mensagens" && req.method === "GET") return json({ mensagens: (await lerJson(env, "mensagens")) || [] });
  if (rota === "relatos" && req.method === "GET") return json({ relatos: (await lerJson(env, "relatos")) || [] });
  if (rota === "relatos" && req.method === "POST") {
    const b = await req.json().catch(() => ({}));
    let l = (await lerJson(env, "relatos")) || [];
    if (b.acao === "apagar") l = l.filter(r => r.id !== b.id);
    else l.forEach(r => { if (r.id === b.id) r.status = b.acao === "reabrir" ? "novo" : "resolvido"; });
    await env.PCT.put("relatos", JSON.stringify(l));
    return json({ ok: true });
  }
  if (rota === "uso" && req.method === "GET") {
    const d = await db(env);
    if (!d) return json({ semBanco: true });
    const desde = n => new Date(Date.now() - 3 * 3600000 - n * DIA).toISOString().slice(0, 10);
    const soma = async (tipo, dias) => ((await d.prepare("SELECT chave, SUM(n) AS n FROM contagem WHERE tipo = ? AND dia >= ? GROUP BY chave ORDER BY n DESC LIMIT 40").bind(tipo, desde(dias)).all()).results || []);
    const ab = async dias => (await soma("abriu", dias)).reduce((t, x) => t + x.n, 0);
    const porDia = ((await d.prepare("SELECT dia, SUM(n) AS n FROM contagem WHERE tipo = 'abriu' AND dia >= ? GROUP BY dia ORDER BY dia").bind(desde(29)).all()).results || []);
    return json({ hoje: await ab(0), semana: await ab(6), mes: await ab(29), proHoje: ((await soma("abriu", 0)).find(x => x.chave === "pro") || { n: 0 }).n, porDia, mcOk: await soma("mc_ok", 29), mcFalha: await soma("mc_falha", 29) });
  }
  if (rota === "botoes" && req.method === "GET") {
    const d = await db(env); if (!d) return json({ botoes: [] });
    return json({ botoes: (await d.prepare("SELECT id, pkg, jogo, nome, autor, n, baixados, em, oculto FROM botoes ORDER BY em DESC LIMIT 200").all()).results || [] });
  }
  if (rota === "botoes" && req.method === "POST") {
    const d = await db(env); if (!d) return json({ erro: "Sem banco." }, 503);
    const b = await req.json().catch(() => ({}));
    if (b.acao === "apagar") await d.prepare("DELETE FROM botoes WHERE id = ?").bind(String(b.id)).run();
    else await d.prepare("UPDATE botoes SET oculto = ? WHERE id = ?").bind(b.acao === "ocultar" ? 1 : 0, String(b.id)).run();
    return json({ ok: true });
  }
  if (rota === "avaliacoes" && req.method === "GET") return json({ avaliacoes: (await lerJson(env, "avaliacoes")) || [] });
  if (rota === "avaliacoes" && req.method === "POST") {
    const b = await req.json().catch(() => ({}));
    let l = (await lerJson(env, "avaliacoes")) || [];
    if (b.acao === "apagar") l = l.filter(a => a.id !== b.id);
    else l.forEach(a => {
      if (a.id !== b.id) return;
      if (b.acao === "responder") { const r = String(b.resposta || "").replace(/\s+/g, " ").trim().slice(0, 600); if (r) { a.resposta = r; a.respondidoEm = Date.now(); } else { delete a.resposta; delete a.respondidoEm; } }
      else if (b.acao === "fixar" || b.acao === "desafixar") { if (b.acao === "fixar") a.fixada = Date.now(); else delete a.fixada; }
      else a.visivel = b.acao === "mostrar";
    });
    await env.PCT.put("avaliacoes", JSON.stringify(l));
    try { await caches.default.delete(new Request("https://cache.local/avaliacoes")); } catch (e) { }
    return json({ ok: true });
  }
  if (rota === "pirataria" && req.method === "GET") {
    const nomes = (await listar(env, "pirata:", 24)).sort().reverse();
    const m = nomes.length ? await lerJson(env, nomes[0]) : null;
    return json({ mes: nomes.length ? nomes[0].slice(7) : mesAtual(), total: m ? m.total : 0, celulares: m ? m.celulares.length : 0, versoes: m ? m.versoes : {}, modelos: m ? m.modelos : {}, ultimo: m ? m.ultimo : 0 });
  }
  if (rota === "notas" && req.method === "GET") {
    const t = ((await lerJson(env, "notas")) || {})._todos || { n: 0, soma: 0 };
    return json({ n: t.n, media: t.n ? Math.round(t.soma / t.n) : 0 });
  }
  if (rota === "novas" && req.method === "GET") {
    const desde = parseInt(url.searchParams.get("desde") || "0", 10) || 0;
    const l = (await lerJson(env, "vendas:recentes")) || [];
    const em = (await lerJson(env, "erros:" + mesAtual())) || {};
    const errosNovos = desde ? Object.values(em).filter(x => !x.resolvido && (x.primeiro > desde || (x.reaberto || 0) > desde)).slice(0, 5).map(x => ({ onde: x.onde, msg: x.msg, tipo: x.tipo, celulares: x.celulares.length })) : [];
    const relatosNovos = desde ? ((await lerJson(env, "relatos")) || []).filter(r => r.em > desde && r.status === "novo").slice(0, 5).map(r => ({ modelo: r.modelo, texto: r.texto.slice(0, 120) })) : [];
    return json({ agora: Date.now(), vendas: l.filter(v => v.em > desde), erros: errosNovos, relatos: relatosNovos });
  }
  if (rota === "testar-aviso" && req.method === "POST") {
    const para = env.AVISO_EMAIL || env.REMETENTE_EMAIL;
    const html = moldura("✅ Teste: aviso de venda funcionando", "É assim que você vai ser avisado a cada Pix pago.", `<div style="${CAIXA}"><table style="width:100%;font-size:14px;color:#E8EEF5" cellpadding="4"><tr><td style="color:#9AA8BA">Plano</td><td align="right">1 mês (exemplo)</td></tr><tr><td style="color:#9AA8BA">Valor</td><td align="right">R$ 15,00</td></tr><tr><td style="color:#9AA8BA">Quando</td><td align="right">${agoraBr()}</td></tr></table></div><a href="https://serpyratoolkit.pages.dev/painel" style="color:#3DDC97">Abrir o painel de vendas</a>`);
    const st = await mandarEmail(env, para, "✅ Teste: aviso de venda do SerpyraToolkit", html, "Teste do aviso de venda. A cada Pix pago você recebe um e-mail assim.");
    return st === "enviado" ? json({ ok: true, para }) : json({ erro: "Não consegui enviar: " + st }, 502);
  }
  if (rota === "vencendo") {
    const nomes = await listar(env, "chave:", 2000);
    const l = (await Promise.all(nomes.map(async n => ({ codigo: n.slice(6), ...(await lerJson(env, n)) })))).filter(c => c.dia && c.dia < DIA_VITALICIO && c.dia >= hoje() - 3 && c.dia <= hoje() + 7);
    return json({ vencendo: l.sort((a, b) => a.dia - b.dia).map(c => ({ codigo: c.codigo, email: c.email || "", validade: dataBr(c.dia), dias: c.dia - hoje() })) });
  }
  if (rota === "aviso" && req.method === "POST") {
    const b = await req.json().catch(() => ({}));
    const texto = String(b.texto || "").trim().slice(0, 240);
    if (!texto) { await env.PCT.delete("aviso"); }
    else await env.PCT.put("aviso", JSON.stringify({ id: Date.now(), texto, link: String(b.link || "").slice(0, 200) }));
    try { await caches.default.delete(new Request("https://aviso.local/atual")); } catch (e) { }
    return json({ ok: true });
  }
  if (rota === "print" && req.method === "GET") return await verPrint(url, env);
  if (rota === "config" && req.method === "GET") { const c = (await lerJson(env, "config")) || {}; return json({ ...c, donoChave: undefined, donoChaveDefinida: !!c.donoChave }); }
  if (rota === "config" && req.method === "POST") {
    const b = await req.json().catch(() => ({}));
    const c = (await lerJson(env, "config")) || {};
    if ("emailErro" in b) c.emailErro = !!b.emailErro;
    if ("donoChave" in b) { const k = limpaCupom(b.donoChave); if (k && k.length < 8) return json({ erro: "A chave do dono precisa ter pelo menos 8 letras ou números." }, 400); if (k) c.donoChave = k; else delete c.donoChave; }
    if ("relatorioDias" in b) c.relatorioDias = [7, 15, 30].includes(Number(b.relatorioDias)) ? Number(b.relatorioDias) : 15;
    await env.PCT.put("config", JSON.stringify(c));
    try { await caches.default.delete(new Request("https://cache.local/config")); } catch (e) { }
    return json({ ok: true, config: { ...c, donoChave: undefined, donoChaveDefinida: !!c.donoChave } });
  }
  if (rota === "site" && req.method === "GET") return json(await lerSite(env));
  if (rota === "site" && req.method === "POST") {
    const b = await req.json().catch(() => ({}));
    const link = v => /^https:\/\/|^\/|^#/.test(String(v || "")) ? String(v).slice(0, 200) : "";
    const t = (v, n) => String(v || "").replace(/\s+/g, " ").trim().slice(0, n);
    const sCfg = {
      aviso: { ligado: !!(b.aviso && b.aviso.ligado), texto: t(b.aviso && b.aviso.texto, 160), botao: t(b.aviso && b.aviso.botao, 30), link: link(b.aviso && b.aviso.link) },
      faixa: (Array.isArray(b.faixa) ? b.faixa : []).map(x => t(x, 30)).filter(Boolean).slice(0, 10),
      avaliacoes: !(b.avaliacoes === false),
      destaque: { ligado: !!(b.destaque && b.destaque.ligado), titulo: t(b.destaque && b.destaque.titulo, 80), texto: t(b.destaque && b.destaque.texto, 240), botao: t(b.destaque && b.destaque.botao, 30), link: link(b.destaque && b.destaque.link) },
      em: Date.now()
    };
    await env.PCT.put("site", JSON.stringify(sCfg));
    try { await caches.default.delete(new Request("https://cache.local/site")); } catch (e) { }
    return json({ ok: true, site: sCfg });
  }
  if (rota === "relatorios" && req.method === "GET") return json({ relatorios: (await lerJson(env, "relatorios")) || [], proximo: parseInt((await env.PCT.get("resumo_em")) || "0", 10) || 0, dias: (await config(env)).relatorioDias || 15 });
  if (rota === "relatorio" && req.method === "GET") {
    const r = await lerJson(env, "relatorio:" + String(url.searchParams.get("id") || "").slice(0, 30));
    return r ? json(r) : json({ erro: "Relatório não encontrado." }, 404);
  }
  if (rota === "relatorio" && req.method === "POST") {
    const b = await req.json().catch(() => ({}));
    const dias = Math.min(90, Math.max(1, parseInt(b.dias, 10) || 15));
    const r = await montarRelatorio(env, Date.now() - dias * DIA, Date.now(), false);
    if (b.salvar) await salvarRelatorio(env, r);
    if (b.email) await mandarRelatorio(env, r);
    return json(r);
  }
  if (rota === "relatorio" && req.method === "DELETE") {
    const id = String(url.searchParams.get("id") || "").slice(0, 30);
    await env.PCT.delete("relatorio:" + id);
    await env.PCT.put("relatorios", JSON.stringify(((await lerJson(env, "relatorios")) || []).filter(x => x.id !== id)));
    return json({ ok: true });
  }
  if (rota === "saude" && req.method === "GET") {
    const faltando = ["PURIN_KEY", "LICENCA_PRIV", "BREVO_KEY", "REMETENTE_EMAIL", "ADMIN_TOKEN"].filter(k => !env[k]);
    let d1 = false; try { const d = await db(env); if (d) { await d.prepare("SELECT 1").all(); d1 = true; } } catch (e) { }
    return json({ ok: faltando.length === 0, faltando, modo: String(env.PURIN_KEY || "").startsWith("ps_test_") ? "teste" : "produção", d1, emailVendas: !!env.AVISO_POR_EMAIL, backup: !!env.BACKUP_EMAIL, avisoPara: env.AVISO_EMAIL || env.REMETENTE_EMAIL || "", config: (({ donoChave, ...r }) => ({ ...r, donoChaveDefinida: !!donoChave }))((await lerJson(env, "config")) || {}) });
  }
  return json({ erro: "Não encontrado" }, 404);
}

/* ---------------- e-mails que falharam: reenvio diário ---------------- */
async function reenviarEmails(env) {
  await resumoQuinzenal(env);
  const nomes = await listar(env, "pedido:", 3000);
  let enviados = 0, faltam = 0;
  for (const n of nomes) {
    const p = await lerJson(env, n);
    if (!p || p.status !== "pago" || p.email_status === "enviado") continue;
    if (p.presente && p.voucher) {
      const pm = p.site ? emailSite(p, PLANOS[p.plano] || PLANOS.m1) : emailPresente(p, PLANOS[p.plano] || PLANOS.m1);
      p.email_status = await mandarEmail(env, p.presente, pm.assunto, pm.html, pm.texto);
      if (p.email_status === "enviado") { enviados++; await env.PCT.put(n, JSON.stringify(p)); } else faltam++;
      continue;
    }
    if (!p.chave) continue;
    if (Date.parse(p.pagoEm) < Date.now() - 7 * DIA) continue;
    const m = emailCompra({ numero: p.numero, codigo: p.codigo, chave: p.chave, valor: reais(p.valor), plano: (PLANOS[p.plano] || PLANOS.m1).nome, cupom: p.cupom, validade: validadeTxt(p.dia), dataPago: agoraBr() });
    p.email_status = await mandarEmail(env, p.email, m.assunto, m.html, m.texto);
    if (p.email_status === "enviado") { enviados++; await env.PCT.put(n, JSON.stringify(p)); } else faltam++;
    if (faltam > 3) break;
  }
  return { enviados, faltam };
}

/* ---------------- atualização do app e site ---------------- */
/* copia de seguranca diaria das vendas, clientes, cupons e codigos (1 e-mail por dia para o dono) */
async function backupDiario(env) {
  try {
    const dados = { data: new Date().toISOString() };
    for (const pre of ["pedido:", "chave:", "cupom:", "presente:", "email:"]) {
      const nomes = await listar(env, pre, 5000);
      const obj = {};
      for (const n of nomes) obj[n] = await env.PCT.get(n);
      dados[pre.slice(0, -1)] = obj;
    }
    const txt = JSON.stringify(dados);
    const bytes = new TextEncoder().encode(txt);
    let bin = ""; for (let i = 0; i < bytes.length; i += 0x8000) bin += String.fromCharCode.apply(null, bytes.subarray(i, i + 0x8000));
    const b64 = btoa(bin);
    const para = env.AVISO_EMAIL || env.REMETENTE_EMAIL;
    if (!env.BREVO_KEY || !para) return;
    const nome = "serpyratoolkit-backup-" + diaAtual() + ".json";
    await fetch("https://api.brevo.com/v3/smtp/email", {
      method: "POST", headers: { "api-key": env.BREVO_KEY, "content-type": "application/json", "accept": "application/json" },
      body: JSON.stringify({ sender: { name: env.REMETENTE_NOME || "SerpyraToolkit", email: env.REMETENTE_EMAIL }, to: [{ email: para }],
        subject: "Cópia de segurança das vendas · " + agoraBr().slice(0, 10), htmlContent: moldura("Cópia de segurança do dia", "Guarde este e-mail. O arquivo anexo tem todas as vendas, clientes, cupons e códigos.", `<div style="${CAIXA}">${Object.keys(dados.pedido || {}).length} pedidos · ${Object.keys(dados.chave || {}).length} clientes · ${Object.keys(dados.cupom || {}).length} cupons · ${Object.keys(dados.presente || {}).length} códigos de presente/cortesia</div>`),
        attachment: [{ name: nome, content: b64 }] })
    });
  } catch (e) { }
}

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

async function baixarPc(env) {
  const r = await github(env, "pc/dist/SerpyraToolkit-PC.exe");
  if (!r.ok) return json({ erro: "Programa para PC indisponível (" + r.status + ")." }, 502);
  const h = { "content-type": "application/vnd.microsoft.portable-executable", "content-disposition": 'attachment; filename="SerpyraToolkit-PC.exe"', "cache-control": "no-store" };
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
      if (m === "GET" && p === "/planos") return planos(url);
      if (m === "GET" && p === "/cupom") return await verCupom(url, env);
      if (m === "POST" && p === "/pedido") return await criarPedido(req, env, url.origin);
      if (m === "GET" && p === "/status") return await status(url, env);
      if (m === "POST" && p === "/webhook") return await webhook(req, env);
      if (m === "POST" && p === "/teste") return await testeGratis(req, env);
      if (m === "GET" && p === "/licenca") return await licenca(url, env);
      if (m === "POST" && p === "/troca/pedir") return await trocaPedir(req, env);
      if (m === "POST" && p === "/pc/pedir") return await pcPedir(req, env);
      if (m === "POST" && p === "/pc/confirmar") return await pcConfirmar(req, env);
      if (m === "POST" && p === "/troca/confirmar") return await trocaConfirmar(req, env);
      if (m === "POST" && p === "/simular") return await simular(url, env);
      if (m === "POST" && p === "/presente") return await resgatarPresente(req, env);
      if (m === "POST" && p === "/registrar") return await registrar(req, env);
      if (m === "GET" && p === "/aviso") return await avisoAtual(env);
      if (m === "GET" && p === "/promo") return await promoAtual(env);
      if (m === "POST" && p === "/erro") return await registrarErro(req, env);
      if (m === "GET" && p === "/print") return await verPrint(url, env);
      if (m === "POST" && p === "/dono/entrar") return await donoEntrar(req, env);
      if (m === "GET" && (p === "/dono/novas" || p === "/dono/saude")) return await donoRota(req, url, env);
      if (m === "GET" && p === "/site") return await siteConfig(env);
    if (m === "GET" && p === "/mensagens") return await mensagens(env);
    if (m === "POST" && p === "/avaliacao") return await avaliar(req, env);
    if (m === "GET" && p === "/botoes") return await botoesLista(url, env);
    if (m === "GET" && p === "/botoes/um") return await botoesUm(url, env);
    if (m === "POST" && p === "/botoes") return await botoesPublicar(req, env);
    if (m === "GET" && p === "/avaliacoes") return await avaliacoes(env);
    if (m === "POST" && p === "/relato") return await relato(req, env);
    if (m === "POST" && p === "/pirata") return await pirata(req, env);
    if (p === "/nota") return await nota(req, url, env);
      if (m === "POST" && p === "/evento") return await evento(req, env);
      if (m === "POST" && p === "/pesquisa") return await pesquisa(req, env);
      if (m === "GET" && p === "/versao") return await versao(env);
      if (m === "GET" && (p === "/apk" || p === "/baixar")) return await baixarApk(env);
      if (m === "GET" && p === "/pc") return await baixarPc(env);
      if (m === "GET" && p === "/pc/versao") { const r = await github(env, "pc/dist/versao.json"); return new Response(r.ok ? await r.text() : "{}", { status: r.ok ? 200 : 502, headers: { "content-type": "application/json; charset=utf-8", "cache-control": "no-store", ...CORS } }); }
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
  async scheduled(event, env, ctx) { ctx.waitUntil(reenviarEmails(env).then(() => env.BACKUP_EMAIL ? backupDiario(env) : null)); }
};
