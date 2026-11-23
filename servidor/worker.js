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
  m1: { nome: "1 mês", dias: 30, centavos: 1500 },
  m3: { nome: "3 meses", dias: 90, centavos: 3900 },
  m6: { nome: "6 meses", dias: 180, centavos: 6900 },
  vida: { nome: "Vitalício", dias: 0, centavos: 14900, vitalicio: true }
};
const DIA_VITALICIO = 99999;
const LICENCA_PUB = "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEe1+AJ9msQvTj5AdyZO0SzXl0SNyekG3tBq3nUeQHdWSvy3u7i8k6qTJsZ8iU890vHizszVAKeAxs4fjOVXVnFQ==";
const IND_PCT = 10, IND_DIAS = 15, RENOVA_PCT = 10;
const MOTIVOS = ["Caro", "Não usei muito", "Não funcionou no meu celular", "Difícil de usar", "Só queria testar", "Outro"];
const CORS = { "access-control-allow-origin": "*", "access-control-allow-headers": "content-type, x-admin", "access-control-allow-methods": "GET,POST,DELETE" };

const json = (o, status = 200) => new Response(JSON.stringify(o), { status, headers: { "content-type": "application/json; charset=utf-8", ...CORS } });
function reais(c) { return "R$ " + (c / 100).toFixed(2).replace(".", ","); }
function validadeTxt(dia) { return dia >= 99999 ? "Vitalício (não vence)" : dataBr(dia); }
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
    `<div style="color:#66758A;font-size:12px">Para renovar, gere um novo Pix pelo app antes de vencer: os dias se somam. Guarde este e-mail como comprovante.</div>`);
  const texto = `SerpyraToolkit Pro ativado!\n\nPedido: ${p.numero}\nData: ${p.dataPago}\nProduto: SerpyraToolkit Pro - ${p.plano}\nValor: ${p.valor} (Pix)\nVálido até: ${p.validade}\n\nSua chave de ativação:\n${p.chave}\n\nNo app: SerpyraToolkit Pro > Colar > Ativar.`;
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
  const novo = parseInt(url.searchParams.get("v") || "0", 10) >= 2;
  return json({ teste: 0, testeMin: TESTE_MIN, indicacao: { pct: IND_PCT, dias: IND_DIAS }, renova: RENOVA_PCT, planos: Object.entries(PLANOS).filter(([id, p]) => novo || !p.vitalicio).map(([id, p]) => ({ id, nome: p.nome, dias: p.dias, centavos: p.centavos, preco: reais(p.centavos), vitalicio: !!p.vitalicio })) });
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
  const cupom = cupomCod ? await cupomValido(env, cupomCod, codigo) : null;
  const presente = b.presente ? String(b.presente).trim().toLowerCase() : "";
  if (presente && !emailValido(presente)) return json({ erro: "E-mail de quem vai ganhar o presente é inválido." }, 400);
  if (presente && cupom && cupom.especial === "renovacao") return json({ erro: "Esse cupom é só para renovar o seu próprio Pro." }, 400);
  if (cupomCod && !cupom) return json({ erro: "Cupom inválido ou esgotado." }, 400);
  const valor = comDesconto(plano.centavos, cupom);
  const numero = "PCT-" + Date.now().toString(36).toUpperCase().slice(-6);
  let r;
  try {
    r = await purin(env, "POST", "/payments", {
      valueCents: valor, description: "SerpyraToolkit Pro - " + plano.nome + (presente ? " (presente)" : "") + " (" + numero + ")", callbackUrl: base + "/webhook",
      customer: { email, externalId: codigo }, metadata: JSON.stringify({ numero, codigo, plano: planoId, cupom: cupomCod })
    });
  } catch (e) { return json({ erro: "Não consegui gerar o Pix agora. " + e.message }, 502); }
  const id = r.paymentId;
  await env.PCT.put("pedido:" + id, JSON.stringify({ id, numero, codigo, email, atual, valor, plano: planoId, dias: plano.dias, cupom: cupom ? cupomCod : "", presente, status: "pendente", criado: new Date().toISOString() }), { expirationTtl: 60 * 60 * 24 * 400 });
  return json({ pedido: id, numero, valor: reais(valor), plano: plano.nome, brCode: r.pix && r.pix.brCode, qr: r.pix && r.pix.qrCodeImage, expira: r.expiresAt, teste: String(env.PURIN_KEY || "").startsWith("ps_test_") });
}

async function validadeAtual(env, codigo) {
  const c = await lerJson(env, "chave:" + codigo);
  if (c && c.dia) return c.dia;
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
  const m = emailCompra({ numero: p.numero, codigo: p.codigo, chave: p.chave, valor: reais(p.valor), plano, cupom: p.cupom, validade: validadeTxt(dia), dataPago: agoraBr() });
  p.email_status = await mandarEmail(env, p.email, m.assunto, m.html, m.texto);
  await avisarDono(env, p, plano);
  await env.PCT.put("pedido:" + id, JSON.stringify(p));
  await env.PCT.put("chave:" + p.codigo, JSON.stringify({ chave: p.chave, dia, email: p.email }));
  const em = (await lerJson(env, "email:" + p.email)) || {};
  await env.PCT.put("email:" + p.email, JSON.stringify({ codigo: p.codigo, dia, troca: em.troca || 0, otp: "" }));
  if (/^IND/.test(p.cupom || "")) await premiarIndicacao(env, p.cupom.slice(3), p.codigo);
  else if (p.cupom && p.cupom !== "RENOVA10") {
    const c = await lerJson(env, "cupom:" + p.cupom);
    if (c) { c.usos = (c.usos || 0) + 1; c.receita = (c.receita || 0) + p.valor; await env.PCT.put("cupom:" + p.cupom, JSON.stringify(c)); }
  }
  return p;
}

/* ---------------- indicação e presente ---------------- */
async function premiarIndicacao(env, ref, comprador) {
  if (!codigoValido(ref) || ref === comprador) return;
  const c = await lerJson(env, "chave:" + ref);
  if (!c || c.dia >= DIA_VITALICIO) return;
  const dia = Math.max(hoje(), c.dia) + IND_DIAS;
  const chave = await assinarChave(env, ref, dia);
  await env.PCT.put("chave:" + ref, JSON.stringify({ ...c, chave, dia, indicacoes: (c.indicacoes || 0) + 1 }));
  if (c.email) {
    const html = moldura("Você ganhou " + IND_DIAS + " dias de Pro 🎉", "Um amigo comprou o SerpyraToolkit Pro com o seu código de indicação.",
      `<div style="${CAIXA}"><div style="${ROT}">Seu Pro agora vale até</div><div style="font-size:22px;font-weight:bold;color:#3DDC97">${dataBr(dia)}</div><div style="color:#9AA8BA;font-size:13px;margin-top:12px">Os dias entram sozinhos ao abrir o app (tela do Pro &gt; Já comprei, se precisar). Se quiser colar à mão:</div><div style="${CHV};margin-top:10px">${esc(chave)}</div></div>`);
    await mandarEmail(env, c.email, "Você ganhou " + IND_DIAS + " dias de SerpyraToolkit Pro", html, "Um amigo comprou com o seu código. Seu Pro agora vale até " + dataBr(dia) + ".\nChave: " + chave);
  }
}

function novoVoucher() { const r = crypto.getRandomValues(new Uint8Array(9)); return "PRE" + [...r].map(x => B32[x % 32]).join(""); }

function emailPresente(p, plano) {
  const html = moldura("Você ganhou o SerpyraToolkit Pro 🎁", "Alguém (" + esc(p.email) + ") te deu de presente o SerpyraToolkit Pro · " + esc(plano.nome) + ".",
    `<div style="${CAIXA}"><div style="${ROT}">Seu código de presente</div><div style="font-size:26px;font-weight:bold;letter-spacing:3px;color:#3DDC97">${esc(p.voucher)}</div>` +
    `<div style="color:#9AA8BA;font-size:13px;margin-top:12px">1. Baixe o app em <a style="color:#3DDC97" href="https://serpyratoolkit.pages.dev">serpyratoolkit.pages.dev</a><br>2. Abra <b>SerpyraToolkit Pro</b> e toque em <b>Tenho um código de presente</b><br>3. Digite o código acima. Pronto!</div></div>`);
  return { assunto: "Você ganhou um presente: SerpyraToolkit Pro", html, texto: "Você ganhou o SerpyraToolkit Pro (" + plano.nome + ") de " + p.email + ".\nCódigo de presente: " + p.voucher + "\nBaixe em serpyratoolkit.pages.dev, abra SerpyraToolkit Pro > Tenho um código de presente." };
}

async function liberarPresente(env, id, p, plano) {
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
  const g = await lerJson(env, "presente:" + v);
  if (!g) return json({ erro: "Código de presente não encontrado. Confira as letras." }, 404);
  if (g.usado && g.usado !== codigo) return json({ erro: "Esse presente já foi usado em outro celular." }, 409);
  if (!g.usado && !g.vitalicio) { const atual = await validadeAtual(env, codigo); if (atual >= hoje()) return json({ erro: "Você já tem o Pro ativo até " + validadeTxt(atual) + ". Use este código quando ele acabar: ele continua guardado para você." }, 409); }
  const dia = g.vitalicio ? DIA_VITALICIO : (g.usado ? g.dia : Math.max(hoje(), await validadeAtual(env, codigo)) + (g.dias || 30));
  const chave = await assinarChave(env, codigo, dia);
  if (!g.usado) {
    g.usado = codigo; g.dia = dia; g.em = new Date().toISOString();
    await env.PCT.put("presente:" + v, JSON.stringify(g));
    await env.PCT.put("chave:" + codigo, JSON.stringify({ chave, dia, email: emailValido(g.para) ? g.para : "", origem: g.de === "cortesia" ? "cortesia" : "presente" }));
    if (emailValido(g.para)) await env.PCT.put("email:" + g.para, JSON.stringify({ codigo, dia, troca: 0, otp: "" }));
  }
  return json({ ok: true, chave, validade: validadeTxt(dia) });
}

/* ---------------- erros do app, funil e promocao ---------------- */
function mesAtual() { return new Date(Date.now() - 3 * 3600000).toISOString().slice(0, 7); }
function diaAtual() { return new Date(Date.now() - 3 * 3600000).toISOString().slice(0, 10); }

async function registrarErro(req, env) {
  let b; try { b = await req.json(); } catch (e) { return json({ ok: true }); }
  const codigo = limpaCodigo(b.codigo);
  if (!codigoValido(codigo)) return json({ ok: true });
  if (!(await limitar("erro:" + codigo, 10)) || !(await limitar("erros-total", 300))) return json({ ok: true });
  const lista = Array.isArray(b.erros) ? b.erros.slice(0, 10) : [b];
  const k = "erros:" + mesAtual();
  const m = (await lerJson(env, k)) || {};
  for (const e of lista) {
    const onde = String(e.onde || "app").slice(0, 60), msg = String(e.msg || "").slice(0, 200), tipo = e.tipo === "crash" ? "crash" : "falha";
    const sig = (tipo + "|" + onde + "|" + msg.replace(/\d+/g, "#")).slice(0, 180);
    const x = m[sig] || { tipo, onde, msg, n: 0, celulares: [], modelos: {}, versoes: {}, primeiro: Date.now(), pilha: String(e.pilha || "").slice(0, 1500) };
    x.n++; x.ultimo = Date.now();
    if (!x.celulares.includes(codigo) && x.celulares.length < 50) x.celulares.push(codigo);
    const mod = String(b.modelo || "?").slice(0, 40), ver = String(b.versao || "?").slice(0, 12);
    x.modelos[mod] = (x.modelos[mod] || 0) + 1; x.versoes[ver] = (x.versoes[ver] || 0) + 1;
    m[sig] = x;
  }
  const chaves = Object.keys(m);
  if (chaves.length > 200) chaves.sort((a, b) => m[a].ultimo - m[b].ultimo).slice(0, chaves.length - 200).forEach(c => delete m[c]);
  await env.PCT.put(k, JSON.stringify(m), { expirationTtl: 60 * 60 * 24 * 400 });
  return json({ ok: true });
}

async function evento(req, env) {
  let b; try { b = await req.json(); } catch (e) { return json({ ok: true }); }
  const codigo = limpaCodigo(b.codigo), tipo = b.tipo === "pro" ? "pro" : "";
  if (!codigoValido(codigo) || !tipo) return json({ ok: true });
  // uma vez por celular por dia, e no maximo 400 gravacoes por dia no total
  if (!(await limitar("ev:" + tipo + ":" + codigo + ":" + diaAtual(), 1)) || !(await limitar("ev-total:" + diaAtual(), 400))) return json({ ok: true });
  const k = "funil:" + diaAtual();
  const f = (await lerJson(env, k)) || {};
  f[tipo] = (f[tipo] || 0) + 1;
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
    await cache.put(req, new Response(corpo, { headers: { "cache-control": "max-age=300" } }));
  }
  const p = JSON.parse(corpo || "{}");
  if (!p.texto || (p.ate && Date.now() > p.ate)) return json({});
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
  await env.PCT.put("chave:" + k.codigo, JSON.stringify({ ...(c || {}), chave: k.dia >= DIA_VITALICIO ? "" : String(b.chave).trim(), dia: k.dia, email: (c && c.email) || "", origem: (c && c.origem) || "registrada" }));
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
  if (p.status === "pago" && p.presente) return json({ status: "pago", presente: true, voucher: p.voucher, para: p.presente, numero: p.numero, email: p.email_status === "enviado" });
  if (p.status === "pago") return json({ status: "pago", chave: p.chave, numero: p.numero, validade: validadeTxt(p.dia), email: p.email_status === "enviado" });
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
  if (!(await limitar("teste", 300))) return json({ erro: "Muitos testes agora. Tente mais tarde." }, 429);
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
  if (o.dia < hoje()) return json({ status: "vencida", validade: validadeTxt(o.dia) }, 404);
  return json({ status: "ativa", chave: o.chave, validade: validadeTxt(o.dia) });
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
  if (rota === "cortesia" && req.method === "POST") {
    const b = await req.json().catch(() => ({}));
    const vital = !!b.vitalicio;
    const dias = parseInt(b.dias, 10);
    if (!vital && !(dias >= 1 && dias <= 3650)) return json({ erro: "Escolha de 1 a 3650 dias (ou vitalício)." }, 400);
    const qtd = Math.min(20, Math.max(1, parseInt(b.qtd, 10) || 1));
    const nota = String(b.nota || "").trim().slice(0, 60);
    const codigos = [];
    for (let i = 0; i < qtd; i++) {
      const v = novoVoucher();
      await env.PCT.put("presente:" + v, JSON.stringify({ plano: "cortesia", dias: vital ? 0 : dias, vitalicio: vital, de: "cortesia", para: nota, usado: "", criado: new Date().toISOString() }));
      codigos.push(v);
    }
    return json({ codigos, duracao: vital ? "vitalício" : dias + " dia(s)" });
  }
  if (rota === "cortesias" && req.method === "GET") {
    const nomes = await listar(env, "presente:", 1000);
    const l = (await Promise.all(nomes.map(async n => ({ codigo: n.slice(9), ...(await lerJson(env, n)) })))).filter(x => x && x.plano);
    l.sort((a, b) => String(b.criado || b.em || "").localeCompare(String(a.criado || a.em || "")));
    return json({ cortesias: l.slice(0, 300).map(x => ({ codigo: x.codigo, tipo: x.de === "cortesia" ? "cortesia" : "presente pago", duracao: x.vitalicio ? "vitalício" : (x.dias || 30) + " dias", para: x.para || "", usado: x.usado || "", em: x.em || "", validade: x.dia ? validadeTxt(x.dia) : "", criado: x.criado || "" })) });
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
    await env.PCT.put("chave:" + codigo, JSON.stringify({ ...c, chave, dia }));
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
    const l = Object.values(m).sort((a, b) => b.celulares.length - a.celulares.length || b.n - a.n);
    const meses = (await listar(env, "erros:", 50)).map(n => n.slice(6)).sort().reverse();
    return json({ mes, meses: meses.length ? meses : [mes], total: l.reduce((t, x) => t + x.n, 0), celulares: new Set(l.flatMap(x => x.celulares)).size, crashes: l.filter(x => x.tipo === "crash").reduce((t, x) => t + x.n, 0),
      erros: l.slice(0, 100).map(x => ({ tipo: x.tipo, onde: x.onde, msg: x.msg, n: x.n, celulares: x.celulares.length, modelos: x.modelos, versoes: x.versoes, primeiro: x.primeiro, ultimo: x.ultimo, pilha: x.pilha })) });
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
      await env.PCT.put("promo", JSON.stringify({ id: Date.now(), texto, cupom: limpaCupom(b.cupom), cor: /^(verde|amarelo|vermelho|azul)$/.test(b.cor) ? b.cor : "verde", ate: horas ? Date.now() + horas * 3600000 : 0 }));
    }
    try { await caches.default.delete(new Request("https://promo.local/atual")); } catch (e) { }
    return json({ ok: true });
  }
  if (rota === "promo" && req.method === "GET") return json((await lerJson(env, "promo")) || {});
  if (rota === "novas" && req.method === "GET") {
    const desde = parseInt(url.searchParams.get("desde") || "0", 10) || 0;
    const l = (await lerJson(env, "vendas:recentes")) || [];
    return json({ agora: Date.now(), vendas: l.filter(v => v.em > desde) });
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
  return json({ erro: "Não encontrado" }, 404);
}

/* ---------------- e-mails que falharam: reenvio diário ---------------- */
async function reenviarEmails(env) {
  const nomes = await listar(env, "pedido:", 3000);
  let enviados = 0, faltam = 0;
  for (const n of nomes) {
    const p = await lerJson(env, n);
    if (!p || p.status !== "pago" || p.email_status === "enviado") continue;
    if (p.presente && p.voucher) {
      const pm = emailPresente(p, PLANOS[p.plano] || PLANOS.m1);
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
      if (m === "POST" && p === "/troca/confirmar") return await trocaConfirmar(req, env);
      if (m === "POST" && p === "/simular") return await simular(url, env);
      if (m === "POST" && p === "/presente") return await resgatarPresente(req, env);
      if (m === "POST" && p === "/registrar") return await registrar(req, env);
      if (m === "GET" && p === "/aviso") return await avisoAtual(env);
      if (m === "GET" && p === "/promo") return await promoAtual(env);
      if (m === "POST" && p === "/erro") return await registrarErro(req, env);
      if (m === "POST" && p === "/evento") return await evento(req, env);
      if (m === "POST" && p === "/pesquisa") return await pesquisa(req, env);
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
  async scheduled(event, env, ctx) { ctx.waitUntil(reenviarEmails(env).then(() => env.BACKUP_EMAIL ? backupDiario(env) : null)); }
};
