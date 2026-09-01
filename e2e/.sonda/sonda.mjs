import { chromium } from '@playwright/test';

const ENTE   = '9793c45d-be0a-44bd-8599-1e887bc1f5bc';
const SESSAO = '641970db-5d96-4028-9670-663be0685c6e';
const PROP   = '8f4e22c0-11ba-47e0-83cb-161adc03500f';
const VER    = '0dae8f3f-c6b9-4cce-9e1f-d3f93c39689b';
const q = o => encodeURIComponent(JSON.stringify(o));
const TSEC = q({ 'identidade-id':'b548ed88-816f-4a67-a7be-cb59f241d7d7','ente-id':ENTE, papeis:['secretario','admin_ente'] });
const TVER = q({ 'identidade-id':'f79ddcfc-82ce-49f9-a775-b0847c989acf','ente-id':ENTE,papeis:['vereador'] });
const TNOT = q({ 'identidade-id':'c21fdef8-dbfb-4bee-b912-92955be85eab','ente-id':ENTE,papeis:['vereador'] });

const B = 'http://localhost:3000';
const rotas = [
  ['/', null, 'publico'],
  ['/entrar', null, 'publico'],
  [`/portal/casa/${ENTE}`, null, 'cidadao'],
  [`/portal/casa/${ENTE}/materias/${PROP}`, null, 'cidadao'],
  [`/portal/casa/${ENTE}/vereadores/${VER}`, null, 'cidadao'],
  ['/cadastros/vereadores', TSEC, 'secretaria'],
  ['/expediente', TSEC, 'secretaria'],
  ['/expediente/modelos', TSEC, 'secretaria'],
  ['/expediente/protocolo', TSEC, 'secretaria'],
  ['/expediente/recebidos', TSEC, 'secretaria'],
  ['/proposicoes', TSEC, 'secretaria'],
  ['/tramitacao', TSEC, 'secretaria'],
  ['/pauta-convocacao', TSEC, 'secretaria'],
  ['/editor-proposicao', TSEC, 'secretaria'],
  [`/editor-proposicao/${PROP}`, TSEC, 'secretaria'],
  [`/ficha-materia/${PROP}`, TSEC, 'secretaria'],
  [`/pos-aprovacao/${PROP}`, TSEC, 'secretaria'],
  [`/parecer/${PROP}`, TSEC, 'secretaria'],
  ['/paineis/mesa', TSEC, 'secretaria'],
  [`/sessoes/${SESSAO}/plenario`, TSEC, 'secretaria'],
  [`/sessoes/${SESSAO}/chamada`, TSEC, 'secretaria'],
  [`/sessoes/${SESSAO}/folha`, TSEC, 'secretaria'],
  ['/vereador', TVER, 'vereador'],
  ['/votar', TVER, 'vereador'],
  ['/notificacoes', TNOT, 'vereador'],
  [`/parecer/${PROP}/assinar`, TVER, 'vereador'],
];

const SONDA = () => {
  const t = document.body.innerText || '';
  // enum cru = token com underscore, minúsculo-com-underscore ou MAIÚSCULO_COM_UNDERSCORE
  const enums = [...new Set([
    ...(t.match(/\b[a-z]+(?:_[a-z]+)+\b/g) || []),
    ...(t.match(/\b[A-Z]+(?:_[A-Z]+)+\b/g) || []),
  ])].filter(s => !s.startsWith('urn_'));
  const uuidFrag = [...new Set(t.match(/\b[0-9a-f]{8}(?:-[0-9a-f]{4}){0,3}\b/g) || [])];
  const emBreve = (t.match(/Em breve/g) || []).length;
  const erros = [...new Set((t.match(/[^.\n]*(?:não foi possível|nao foi possivel|erro ao|falha ao|indisponível|Algo deu errado|tente novamente)[^.\n]*/gi) || []))].map(s=>s.trim().slice(0,110));
  const vazios = [...new Set((t.match(/[^.\n]*(?:Nenhum[ a-zç]*|Nada [a-zç]+|sem registro|vazio)[^.\n]*/gi) || []))].map(s=>s.trim().slice(0,90)).slice(0,8);
  return { enums, uuidFrag, emBreve, erros, vazios, chars: t.length };
};

const browser = await chromium.launch({ args: ['--no-sandbox','--disable-dev-shm-usage'] });
const ctx = await browser.newContext({ viewport: { width: 1440, height: 900 } });
const out = [];
for (const [rota, tok, publico] of rotas) {
  const page = await ctx.newPage();
  const consoleErr = [];
  page.on('console', m => { if (m.type() === 'error') consoleErr.push(m.text().slice(0,140)); });
  page.on('pageerror', e => consoleErr.push('PAGEERROR: ' + String(e).slice(0,140)));
  const url = B + rota + (tok ? `?token=${tok}` : '');
  let status = null;
  try {
    const r = await page.goto(url, { waitUntil: 'domcontentloaded', timeout: 30000 });
    status = r?.status();
    await page.waitForTimeout(2500);
  } catch (e) { consoleErr.push('NAV: ' + String(e).slice(0,120)); }
  let s = {};
  try { s = await page.evaluate(SONDA); } catch (e) { s = { erro: String(e).slice(0,120) }; }
  out.push({ rota, publico, status, ...s, consoleErr: [...new Set(consoleErr)].slice(0,4) });
  await page.close();
}
await browser.close();
console.log('###JSON###');
console.log(JSON.stringify(out, null, 1));
