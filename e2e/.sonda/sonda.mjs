import { chromium } from '@playwright/test';
import { readFileSync, existsSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, resolve } from 'node:path';

// ---------- Task 1.1 — os ids vêm da semente, nunca cravados a mão ----------
// `apps/backend/demo/casa.clj` (`gravar-artefato!`) grava `.artifacts/demo-ids.edn` com
// `{:ente :legislatura :vereadores [{:id :nome ...} ...] :identidades {:secretaria :presidente
// :vereador :cidadao}}`. É a ÚNICA fonte para ente/vereadores/identidades — nunca `random-uuid`,
// nunca copiado à mão daqui.
const AQUI = dirname(fileURLToPath(import.meta.url));
const ARTEFATO = resolve(AQUI, '..', '.artifacts', 'demo-ids.edn');

if (!existsSync(ARTEFATO)) {
  console.error(`ERRO: ${ARTEFATO} não existe — rode ./demo/semear-tudo.sh antes.`);
  process.exit(1);
}
const edn = readFileSync(ARTEFATO, 'utf8');

// Parser mínimo por regex (não é EDN genérico — não precisa ser: o formato é sempre o pr-str de
// um mapa raso conhecido, escrito por `casa.clj`. Uma dependência de parser EDN completo seria
// peso morto para ler 6 UUIDs).
const campo = (padrao, origem = edn) => {
  const m = origem.match(padrao);
  if (!m) throw new Error(`ERRO: não achei o campo esperado em ${ARTEFATO} (padrão: ${padrao}) — o formato do artefato mudou?`);
  return m[1];
};
const ENTE = campo(/:ente\s+#uuid\s+"([0-9a-fA-F-]{36})"/);
// O 1º `:id #uuid ...` do arquivo é sempre o do 1º vereador do vetor `:vereadores` (idx 0, o
// presidente da Mesa — `casa.clj:88`), porque nenhuma outra chave do topo do EDN se chama `:id`
// (mesma leitura que `demo/semear-tudo.sh` já faz para a barreira de projeção).
const VEREADOR_PORTAL = campo(/:id\s+#uuid\s+"([0-9a-fA-F-]{36})"/);
const blocoIdentidades = campo(/:identidades\s*\{([\s\S]*)\}\s*\}?\s*$/);
const IDENTIDADE_SECRETARIA = campo(/:secretaria\s+#uuid\s+"([0-9a-fA-F-]{36})"/, blocoIdentidades);
const IDENTIDADE_VEREADOR = campo(/:vereador\s+#uuid\s+"([0-9a-fA-F-]{36})"/, blocoIdentidades);

// Sessões: `apps/backend/demo/sessoes.clj` usa UUIDs FIXOS em constante (não `random-uuid`) —
// mesmo desenho do ente em `casa.clj`, é o que torna a semente re-executável sem trocar ids. Mas
// `casa/semear!` é quem grava `demo-ids.edn`, e as sessões nascem depois, numa semente separada
// (`sessoes/semear!`) que não escreve nesse artefato (ver `casa.clj:199-215` — só o resultado de
// `casa/semear!` é gravado). Não existe rota pública nem autenticada que LISTE sessões de um ente
// (`grep '"/sessoes"' apps/backend/src/oplenario/sessoes/**` só acha o `POST /sessoes` de
// agendar) — então não há como descobri-las em runtime. Usamos as constantes publicadas em
// `apps/backend/demo/sessoes.clj:72-74`, que são tão estáveis quanto o próprio ENTE.
const SESSAO_ABERTA = '10000000-0000-0000-0000-000000000211';
const SESSAO_ENCERRADA = '10000000-0000-0000-0000-000000000210';

const B = 'http://localhost:3000';
const BACKEND = 'http://localhost:8888';

const q = o => encodeURIComponent(JSON.stringify(o));
const TSEC = q({ 'identidade-id': IDENTIDADE_SECRETARIA, 'ente-id': ENTE, papeis: ['secretario'] });
const TVER = q({ 'identidade-id': IDENTIDADE_VEREADOR, 'ente-id': ENTE, papeis: ['vereador'] });

// Proposição: `acervo/semear!` usa `random-uuid` (não é estável) e não grava artefato — a ÚNICA
// forma de achar uma proposição real é perguntar à Casa em runtime, via rota pública
// (`GET /portal/casa/:ente/materias`, já provada de pé pela barreira de projeção do
// `semear-tudo.sh`). Preferimos uma em estado "aprovada" (melhor encaixe narrativo para
// `/pos-aprovacao`); qualquer proposição serve para as demais telas de matéria — a sonda mede
// renderização, não narrativa.
async function acharMaterias() {
  const r = await fetch(`${BACKEND}/portal/casa/${ENTE}/materias`);
  if (!r.ok) throw new Error(`ERRO: GET /portal/casa/${ENTE}/materias devolveu ${r.status} — a semente rodou e a projeção terminou? (rode ./demo/semear-tudo.sh)`);
  const materias = await r.json();
  if (!Array.isArray(materias) || materias.length === 0) {
    throw new Error(`ERRO: /portal/casa/${ENTE}/materias veio vazio — a semente rodou e a projeção terminou? (rode ./demo/semear-tudo.sh)`);
  }
  return materias;
}
const materias = await acharMaterias();
const aprovada = materias.find(m => m.estado === 'aprovada');
const PROP = (aprovada ?? materias[0])['proposicao-id'];

// Parecer: `/parecer/:id` e `/parecer/:id/assinar` NÃO recebem o id da PROPOSIÇÃO — o hook do FE
// (`use-parecer-editor.ts`/`use-meu-parecer.ts`) chama `GET /legislativo/pareceres/:id` e
// `GET /meu/pareceres/:id`, cujo `:id` é o do PARECER (achado lendo `apps/backend/src/oplenario/
// legislativo/diplomat/http/in.clj:140` e o hook do FE — não é assunção, é leitura de código).
// Só existe ligado a proposições em `em_comissoes`/`aguardando_pauta`/`em_pauta` (3 de 24, por
// `acervo/semear!`), sem rota pública — descobre-se autenticado, via a ficha agregada
// (`GET /legislativo/proposicoes/:id/ficha`, que embute `:pareceres`).
async function acharPareceres() {
  const candidatas = materias.filter(m => ['em_comissoes', 'aguardando_pauta', 'em_pauta'].includes(m.estado));
  const achados = [];
  for (const m of candidatas) {
    const r = await fetch(`${BACKEND}/legislativo/proposicoes/${m['proposicao-id']}/ficha`, {
      headers: { Authorization: `Bearer ${decodeURIComponent(TSEC)}` },
    });
    if (!r.ok) continue;
    const ficha = await r.json();
    for (const p of ficha.pareceres ?? []) achados.push(p);
  }
  return achados;
}
const pareceres = await acharPareceres();
if (pareceres.length === 0) {
  throw new Error('ERRO: nenhum parecer achado nas proposições em_comissoes/aguardando_pauta/em_pauta — a semente do acervo rodou? (rode ./demo/semear-tudo.sh)');
}
// para o editor (secretaria): qualquer parecer serve. Para a assinatura (vereador): o estado
// correto pra essa ação é 'aguardando_assinatura' — usamos esse quando existe, mesmo sabendo que
// pode reprovar por posse (ver nota no relatório: o relator sorteado round-robin raramente é a
// identidade 'vereador' fixa do artefato — gap real da semente, não bug da sonda).
const PARECER = pareceres[0].id;
const PARECER_ASSINATURA = (pareceres.find(p => p.estado === 'aguardando_assinatura') ?? pareceres[0]).id;

// ---------- Task 1.2 — a 27ª rota (/entrar/:ente) ----------
const rotas = [
  ['/', null, 'publico'],
  ['/entrar', null, 'publico'],
  [`/entrar/${ENTE}`, null, 'publico'],
  [`/portal/casa/${ENTE}`, null, 'cidadao'],
  [`/portal/casa/${ENTE}/materias/${PROP}`, null, 'cidadao'],
  [`/portal/casa/${ENTE}/vereadores/${VEREADOR_PORTAL}`, null, 'cidadao'],
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
  [`/parecer/${PARECER}`, TSEC, 'secretaria'],
  ['/paineis/mesa', TSEC, 'secretaria'],
  [`/sessoes/${SESSAO_ABERTA}/plenario`, TSEC, 'secretaria'],
  [`/sessoes/${SESSAO_ABERTA}/chamada`, TSEC, 'secretaria'],
  [`/sessoes/${SESSAO_ENCERRADA}/folha`, TSEC, 'secretaria'],
  ['/vereador', TVER, 'vereador'],
  ['/votar', TVER, 'vereador'],
  ['/notificacoes', TVER, 'vereador'],
  [`/parecer/${PARECER_ASSINATURA}/assinar`, TVER, 'vereador'],
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

// ---------- Task 1.2 — o veredicto ----------
// Reprova se, em QUALQUER rota: status >= 400 · fragmento de UUID no texto visível · chave de
// enum crua · erro de console · texto de erro genérico. `EmBreve` é AVISO (lacuna conhecida,
// classe B do plano), nunca reprova.
const falha = r =>
  (r.status ?? 0) >= 400 ||
  (r.uuidFrag?.length ?? 0) > 0 ||
  (r.enums?.length ?? 0) > 0 ||
  (r.consoleErr?.length ?? 0) > 0 ||
  (r.erros?.length ?? 0) > 0;

const reprovadas = out.filter(falha);
const comAviso = out.filter(r => !falha(r) && (r.emBreve ?? 0) > 0);

console.log('###JSON###');
console.log(JSON.stringify(out, null, 1));

console.log('\n###RELATORIO###');
console.log(`${out.length} rotas visitadas — ${reprovadas.length} reprovada(s), ${comAviso.length} com aviso (EmBreve)\n`);
for (const r of out) {
  const marca = falha(r) ? 'FALHA' : (comAviso.includes(r) ? 'AVISO' : 'ok');
  console.log(`[${marca}] ${r.status ?? '—'} ${r.rota} (${r.publico})`);
  if (falha(r)) {
    if ((r.status ?? 0) >= 400) console.log(`         status ${r.status} >= 400`);
    if (r.uuidFrag?.length) console.log(`         fragmento de UUID no texto: ${r.uuidFrag.join(', ')}`);
    if (r.enums?.length) console.log(`         enum cru no texto: ${r.enums.join(', ')}`);
    if (r.consoleErr?.length) console.log(`         erro de console: ${r.consoleErr.join(' | ')}`);
    if (r.erros?.length) console.log(`         texto de erro genérico: ${r.erros.join(' | ')}`);
  } else if (comAviso.includes(r)) {
    console.log(`         "Em breve" ${r.emBreve}x — lacuna conhecida, não é falha`);
  }
}

if (reprovadas.length > 0) {
  console.error(`\n###VEREDICTO### REPROVADO — ${reprovadas.length}/${out.length} rota(s) com defeito: ${reprovadas.map(r=>r.rota).join(', ')}`);
  process.exit(1);
}
console.log(`\n###VEREDICTO### OK — ${out.length}/${out.length} rotas limpas (${comAviso.length} aviso de EmBreve)`);
