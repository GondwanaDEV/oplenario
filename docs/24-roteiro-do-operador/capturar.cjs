// Roteiro do Elvis (docs/23 Fatia 5): capturas das telas REAIS (next dev :3100, modo dev) com a API simulada
// por page.route. Um "mundo" só — a 15ª Sessão Ordinária de 24/09/2026 — atravessado do preparar ao fechar.
// Regerar as telas depois de mudar a UI: subir o frontend com NEXT_PUBLIC_APP_ENV=dev em :3100 e rodar
//   node docs/24-roteiro-do-operador/capturar.cjs [prefixo]
// (Playwright do harness e2e/; um prefixo, ex. "08", recaptura só aquela tela).
const { chromium } = require('/home/user/oplenario/e2e/node_modules/playwright');
const OUT = __dirname + '/telas';
const BASE = 'http://localhost:3100';
const TOKEN = encodeURIComponent(JSON.stringify({ sub: 'u', 'ente-id': '10000000-0000-0000-0000-000000000001', 'identidade-id': 'i1', papeis: ['secretario'] }));
const HOJE9 = '2026-09-24T12:00:00Z'; // 09:00 em Fortaleza
const iso = (min) => new Date(Date.parse(HOJE9) + min * 60000).toISOString();

const baseS = { 'sessao-legislativa-id': 'sl26', 'tipo-sessao': 'ordinaria', modalidade: 'presencial', delibera: true, 'transmite-publica': true, 'gera-ata-regimental': true, 'permite-voto-secreto': true, 'permite-modalidade-remota': false, 'aberta-em': null, 'encerrada-em': null, 'motivo-nao-realizada': null, 'lock-version': 2 };
const S15 = (estado) => ({ ...baseS, id: 's15', 'numero-sequencial': 15, estado, 'agendada-para': HOJE9,
  'aberta-em': ['aberta', 'suspensa', 'encerrada'].includes(estado) ? iso(6) : null,
  'encerrada-em': estado === 'encerrada' ? iso(178) : null });
const OUTRAS = [
  { ...baseS, id: 's16', 'numero-sequencial': 16, estado: 'agendada', 'agendada-para': '2026-09-29T12:00:00Z' },
  { ...baseS, id: 's14', 'numero-sequencial': 14, estado: 'encerrada', 'agendada-para': '2026-09-17T12:00:00Z', 'aberta-em': '2026-09-17T12:04:00Z', 'encerrada-em': '2026-09-17T15:10:00Z' },
];

const VER = ['Bruno Lima', 'Helena Past', 'Ana Castro', 'Carlos Tavares', 'Lúcia Brito', 'Fernando Dias', 'Beatriz Rocha', 'Marcos Lima', 'Sônia Alves', 'Paulo Nunes', 'Teresa Gomes', 'Rui Barros', 'Iara Melo', 'Diego Matos', 'Clara Pinto', 'Hugo Reis', 'Vera Luz', 'João Sá', 'Antônio Moraes', 'Carla Tavares', 'Otávio Reis'];
const PART = ['PDT', 'PSB', 'PT', 'MDB', 'PL', 'União', 'PSD'];
const CARGO = { 0: 'Presidente', 1: 'Vice-presidente', 4: '1º Secretário' };
const membros = VER.map((n, i) => ({ 'vereador-id': `v${i}`, 'nome-parlamentar': n, 'cargo-mesa': CARGO[i] ?? null, partido: PART[i % PART.length] }));
const AUSENTES = { 17: 'ausente', 18: 'ausente-justificativa-pendente', 20: 'licenciado' };

const PROPS = [
  { id: 'p48', tipo: 'projeto_lei', ano: 2026, sequencial: 48, 'urn-lex': 'u', ementa: 'Institui o calendário oficial de eventos culturais do Município', estado: 'aguardando_pauta', 'atualizado-em': iso(-1440) },
  { id: 'p241', tipo: 'requerimento', ano: 2026, sequencial: 241, 'urn-lex': 'u', ementa: 'Requer audiência pública sobre a mobilidade urbana na Regional II', estado: 'aguardando_pauta', 'atualizado-em': iso(-2880) },
];
const ITENS = () => [
  { id: 'i1', fase: 'expediente', 'tipo-item': 'leitura', 'texto-descricao': 'Leitura e aprovação da ata da 14ª Sessão Ordinária', ordem: 1, 'lock-version': 0 },
  { id: 'i2', fase: 'expediente', 'tipo-item': 'proposicao', 'proposicao-id': 'p118', ordem: 2, 'lock-version': 0, proposicao: { tipo: 'requerimento', ano: 2026, sequencial: 118, ementa: 'Requer informações sobre a manutenção das escolas municipais da Regional III', 'autor-texto': 'Ver. Carlos Tavares' } },
  { id: 'i3', fase: 'ordem_do_dia', 'tipo-item': 'proposicao', 'proposicao-id': 'p22', ordem: 3, 'lock-version': 0, proposicao: { tipo: 'projeto_lei', ano: 2026, sequencial: 22, ementa: 'Institui a política municipal de incentivo à energia solar em prédios públicos', 'autor-texto': 'Ver. Ana Castro' } },
  { id: 'i4', fase: 'ordem_do_dia', 'tipo-item': 'proposicao', 'proposicao-id': 'p31', ordem: 4, 'lock-version': 0, proposicao: { tipo: 'projeto_lei', ano: 2026, sequencial: 31, ementa: 'Dispõe sobre a gratuidade no transporte coletivo para doadores de sangue', 'autor-texto': 'Ver. Lúcia Brito' } },
  { id: 'i5', fase: 'ordem_do_dia', 'tipo-item': 'comunicado', 'texto-descricao': 'Convite para a sessão solene dos 300 anos de Fortaleza', ordem: 5, 'lock-version': 0 },
];

function mundo(page, w) {
  // w: { estado, apreciacao, tribuna, votacao, folhas, sessoes, encerrarVotacaoAposMs }
  const st = { itens: ITENS(), apreciacao: w.apreciacao ?? null, estado: w.estado, decisoes: [], incidentes: [], votEncerrada: false };
  st.decisoes.push({ id: 'd1', 'presidente-id': 'v0', questao: 'O vereador pode usar o tempo de liderança após o encerramento da discussão?', decisao: 'Indeferida: encerrada a discussão, não cabe uso da palavra pela liderança.', fundamentacao: 'Art. 142 do Regimento Interno', 'decidido-em': iso(70) });
  st.incidentes.push({ id: 'n1', tipo: 'pedido_vista', resultado: 'deferido', descricao: 'Pedido de vista do PL 31/2026 pela vereadora Lúcia Brito', 'objeto-tipo': 'proposicao', 'objeto-id': 'p31', 'requerente-id': 'v4', deliberacao: 'Vista por uma sessão', 'ocorrido-em': iso(80) });
  const nao = [];
  const json = (route, body, status = 200) => route.fulfill({ status, contentType: 'application/json', body: JSON.stringify(body) });
  const linhas = () => membros.map((m, i) => ({ 'vereador-id': m['vereador-id'], nome: m['nome-parlamentar'], 'nome-parlamentar': m['nome-parlamentar'], partido: m.partido, 'cargo-mesa': m['cargo-mesa'],
    estado: st.estado === 'agendada' ? 'ausente' : (AUSENTES[i] ?? 'presente-plenario'), 'inconsistencia-cadastro': false, 'sem-assento': false,
    desde: st.estado === 'agendada' || AUSENTES[i] ? null : iso(4), fonte: st.estado === 'agendada' || AUSENTES[i] ? null : (i % 3 ? 'autoatendimento' : 'manual_secretaria'), 'registrado-em': st.estado === 'agendada' || AUSENTES[i] ? null : iso(4),
    justificativa: i === 18 ? { id: 'j1', estado: 'pendente', motivo: 'Consulta médica' } : null }));
  const presentes = st.estado === 'agendada' ? 0 : 18;
  const q = { 'presentes-plenario': presentes, 'presentes-remoto': 0, 'presentes-total': presentes, 'membros-da-casa': 21, 'presencas-fora-do-roster': 0 };
  const cab = () => ({ 'sessao-id': 's15', 'sessao-estado': st.estado, instante: iso(90), 'data-de-composicao': '2026-09-24', 'composicao-resolvida-em': iso(0), 'sem-registro-de-presenca': st.estado === 'agendada' });
  const votos = VER.slice(0, 16).map((_, i) => ({ 'vereador-id': `v${i}`, voto: [3, 9].includes(i) ? 'nao' : i === 12 ? 'abstencao' : 'sim' }));
  return page.route(/\/api\//, async (route) => {
    const req = route.request(); const url = new URL(req.url()); const p = url.pathname; const m = req.method();
    if (m !== 'GET') {
      const corpo = req.postData() ? (() => { try { return JSON.parse(req.postData()); } catch { return null; } })() : null;
      if (p.endsWith('/anuncio')) { st.apreciacao = p.split('/itens/')[1].split('/')[0]; return json(route, { id: 'a1', 'item-id': st.apreciacao, 'anunciado-em': iso(95) }, 201); }
      if (p.endsWith('/pauta/itens') && m === 'POST') {
        const pr = PROPS.find((x) => x.id === corpo['proposicao-id']);
        const novo = { id: 'n' + st.itens.length, fase: corpo.fase, 'tipo-item': corpo['tipo-item'], ordem: 90 + st.itens.length, 'lock-version': 0, ...(pr ? { 'proposicao-id': pr.id, proposicao: { tipo: pr.tipo, ano: pr.ano, sequencial: pr.sequencial, ementa: pr.ementa } } : {}), ...(corpo['texto-descricao'] ? { 'texto-descricao': corpo['texto-descricao'] } : {}) };
        st.itens.push(novo); return json(route, { id: novo.id, ordem: novo.ordem }, 201);
      }
      if (p.endsWith('/decisoes-mesa')) { st.decisoes.push({ id: 'd' + (st.decisoes.length + 1), ...corpo, 'decidido-em': iso(96) }); return json(route, { id: 'nd' }, 201); }
      if (p.endsWith('/incidentes')) { st.incidentes.push({ id: 'n' + (st.incidentes.length + 1), ...corpo, 'ocorrido-em': iso(96) }); return json(route, { id: 'ni' }, 201); }
      if (p === '/api/sessoes' && m === 'POST') return json(route, { ...baseS, id: 's17', 'numero-sequencial': 17, estado: 'agendada', 'agendada-para': corpo['agendada-para'] }, 201);
      return json(route, { id: 'ok' }, 201);
    }
    let mm;
    if (p === '/api/sessoes') return json(route, { sessoes: w.sessoes ?? [S15(st.estado), ...OUTRAS] });
    if (p === '/api/meu/identidade') return json(route, { nome: 'Elvis Nogueira', papeis: ['secretario'] });
    if (p === '/api/eu') return json(route, { papeis: ['secretario'] });
    if (p === '/api/paineis/pendencias') return json(route, { pendencias: [
      { 'objeto-tipo': 'pedido_esic', 'objeto-id': 'e1', protocolo: '2026/0112', 'vence-em': '2026-09-26T23:00:00Z', estado: 'aberta' },
      { 'objeto-tipo': 'manifestacao_ouvidoria', 'objeto-id': 'o7', protocolo: 'OUV-2026/0031', 'vence-em': '2026-09-30T23:00:00Z', estado: 'aberta' }], 'pendencias-total': 2 });
    if (p === '/api/compliance/painel') return json(route, { resumo: {}, 'em-aberto': [{ id: 'o1', 'template-chave': 'balancete-mensal', 'objeto-tipo': 'x', 'objeto-id': 'y', 'vence-em': '2026-09-29', estado: 'pendente' }], 'em-aberto-total': 1, 'remessas-recentes': [], 'remessas-recentes-total': 0 });
    if (p === '/api/moderacao/comentarios') return json(route, [{ id: 'c1', 'proposicao-id': 'p', 'autor-identidade-id': 'a', corpo: 'x', denunciado: true, 'criado-em': iso(-600) }]);
    if (p === '/api/paineis/tramitacao') return json(route, { itens: [], 'totais-por-estado': [{ estado: 'aguardando_pauta', total: 2 }] });
    if (p === '/api/paineis/sli/sessoes') return json(route, { sessoes: [{ 'sessao-id': 's15', 'estado-atual': st.estado, situacao: st.estado, 'agendada-para': HOJE9 }, { 'sessao-id': 's16', 'estado-atual': 'agendada', situacao: 'agendada', 'agendada-para': '2026-09-29T12:00:00Z' }], 'sessoes-total': 2 });
    if (p === '/api/legislativo/proposicoes') { const b = url.searchParams.get('busca'); const l = b ? PROPS.filter((x) => x.ementa.toLowerCase().includes(b.toLowerCase())) : PROPS; return json(route, { itens: l, total: l.length, pagina: 1, tamanho: 100 }); }
    if ((mm = p.match(/^\/api\/sessoes\/(s\d+)$/))) { const s = mm[1] === 's15' ? S15(st.estado) : OUTRAS.find((x) => x.id === mm[1]); return s ? json(route, s) : json(route, { erro: 'x' }, 404); }
    if ((mm = p.match(/^\/api\/sessoes\/(s\d+)\/pauta$/))) {
      if (mm[1] !== 's15') return json(route, { 'sessao-id': mm[1], itens: mm[1] === 's16' ? ITENS().slice(0, 3) : [] });
      const vivo = st.apreciacao && st.itens.some((i) => i.id === st.apreciacao);
      return json(route, { 'sessao-id': 's15', itens: st.itens, ...(vivo ? { 'em-apreciacao': { 'item-id': st.apreciacao, 'anunciado-em': iso(95) } } : {}) });
    }
    if ((mm = p.match(/^\/api\/sessoes\/(s\d+)\/folhas$/))) return json(route, { 'sessao-id': mm[1], folhas: mm[1] === 's15' ? (w.folhas ?? []) : [{ id: 'f14', versao: 1 }] });
    if ((mm = p.match(/^\/api\/sessoes\/(s\d+)\/justificativas$/))) return json(route, { 'sessao-id': mm[1], justificativas: mm[1] === 's15' && st.estado !== 'agendada' ? [{ id: 'j1', 'vereador-id': 'v18', estado: 'pendente', motivo: 'Consulta médica', 'decidido-por': null, 'decidido-em': null, 'lock-version': 0 }] : [] });
    if (p === '/api/sessoes/s15/folhas/1') return route.fulfill({ status: 200, contentType: 'text/html', body: FOLHA_HTML });
    if (p === '/api/sessoes/s15/chamada') return json(route, { ...cab(), linhas: linhas(), quorum: q, 'chamadas-conduzidas': st.estado === 'agendada' ? [] : [{ id: 'ch1', 'conduzida-por': 'i1', 'membros-da-casa': 21, 'ocorrido-em': iso(4), 'registrado-em': iso(4) }] });
    if (p === '/api/sessoes/s15/quorum') return json(route, { ...cab(), quorum: q });
    if (p === '/api/sessoes/s15/composicao') return json(route, { 'sessao-id': 's15', 'sessao-estado': st.estado, 'data-de-composicao': '2026-09-24', 'composicao-resolvida-em': iso(0), membros });
    if (p === '/api/sessoes/s15/tribuna') return json(route, w.tribuna ?? { 'sessao-id': 's15', 'orador-atual': null, 'marcos-cronometro': [], inscritos: [] });
    if (p === '/api/sessoes/s15/votacao-aberta') {
      if (!w.votacao || st.votEncerrada) return json(route, { erro: 'nenhuma' }, 404);
      return json(route, { 'votacao-id': 'vt1', 'sessao-id': 's15', modalidade: 'nominal', 'objeto-tipo': 'proposicao', 'objeto-id': 'p22', 'quorum-tipo': 'maioria_simples', 'pauta-item-id': 'i3',
        proposicao: { tipo: 'projeto_lei', ano: 2026, sequencial: 22, ementa: 'Institui a política municipal de incentivo à energia solar em prédios públicos' }, votos });
    }
    if (p === '/api/sessoes/s15/atos-mesa') return json(route, { 'sessao-id': 's15', decisoes: st.decisoes, incidentes: st.incidentes });
    if (p.endsWith('/plenario')) {
      if (w.encerrarVotacaoAposMs && !st.votEncerrada) {
        await new Promise((r) => setTimeout(r, w.encerrarVotacaoAposMs));
        st.votEncerrada = true;
        const d = { 'votacao-id': 'vt1', 'sessao-id': 's15', resultado: 'aprovada', modalidade: 'nominal', 'objeto-tipo': 'proposicao', 'objeto-id': 'p22', 'total-sim': 13, 'total-nao': 2, 'total-abstencao': 1, 'base-membros': 21 };
        return route.fulfill({ status: 200, contentType: 'text/event-stream', body: `event: votacao.encerrada\ndata: ${JSON.stringify(d)}\nid: 90\n\n` });
      }
      return; // pendura: canal ao vivo
    }
    nao.push(m + ' ' + p);
    return json(route, { erro: 'nao simulado' }, 404);
  }).then(() => nao);
}

const FOLHA_HTML = `<!doctype html><html lang="pt-BR"><head><meta charset="utf-8"><style>body{font:14px/1.5 Georgia,serif;margin:32px;color:#1d1d1d}h1{font-size:18px;text-align:center;margin:0}h2{font-size:14px;text-align:center;font-weight:normal;margin:4px 0 20px}table{width:100%;border-collapse:collapse}td,th{border-bottom:1px solid #ccc;padding:5px 6px;text-align:left}th{font-size:12px;text-transform:uppercase;letter-spacing:.04em}</style></head><body>
<h1>CÂMARA MUNICIPAL DE FORTALEZA</h1><h2>Folha de presença — 15ª Sessão Ordinária · 24/09/2026</h2>
<table><thead><tr><th>Vereador(a)</th><th>Partido</th><th>Situação</th><th>Registro</th></tr></thead><tbody>
${VER.map((n, i) => `<tr><td>${n}</td><td>${PART[i % PART.length]}</td><td>${({ 17: 'Ausente', 18: 'Ausente — justificativa pendente', 20: 'Licenciado' })[i] ?? 'Presente'}</td><td>${AUSENTES[i] ? '—' : '09:04'}</td></tr>`).join('')}
</tbody></table></body></html>`;
const FOLHAS = [{ id: 'f1', versao: 1, 'spec-versao': 'folha-v1', 'html-hash': 'a3f9c2e81b7d4f06c5e2a9d18b3f7c4e2d9a6b1f8c3e7d2a5b9f4c1e6d8a3b7f', 'pdf-hash': '9e1b7c3d5f2a8e4b6c9d1f3a7e5b2c8d4f6a9e1b3c7d5f2a8e4b6c9d1f3a7e5b', 'gerada-por': 'i1', 'gerada-por-nome': 'Elvis Nogueira', 'gerada-em': iso(185) }];

const TRIB = { 'sessao-id': 's15', 'orador-atual': { 'fala-id': 'f1', 'orador-id': 'v2', 'tipo-fala': 'principal', fase: 'ordem_do_dia', 'iniciou-em': iso(88), 'inscricao-id': 'ins1', 'lock-version': 0 }, 'marcos-cronometro': [],
  inscritos: [{ 'inscricao-id': 'ins2', 'vereador-id': 'v3', fase: 'ordem_do_dia', ordem: 2, 'tipo-fala': 'principal' }, { 'inscricao-id': 'ins3', 'vereador-id': 'v6', fase: 'ordem_do_dia', ordem: 3, 'tipo-fala': 'principal' }] };

// agora do relógio da página, por captura
const CAPS = [
  { n: '01-central-manha', rota: '/inicio', agora: '2026-09-24T11:10:00Z', w: { estado: 'agendada' }, espera: '.cc-foco', alvo: 'page' },
  { n: '02-agendar', rota: '/agendar-sessao', agora: '2026-09-24T11:10:00Z', w: { estado: 'agendada' }, espera: 'form', alvo: 'page',
    passo: async (pg) => { await pg.waitForTimeout(800); const dt = pg.locator('input[type="datetime-local"]'); if (await dt.count()) await dt.fill('2026-10-06T09:00'); await pg.locator('h1, h2').first().click(); }, clip: { x: 0, y: 0, width: 1280, height: 640 } },
  { n: '03-pauta', rota: '/pauta-convocacao?sessao=s15', agora: '2026-09-24T11:10:00Z', w: { estado: 'agendada' }, espera: 'text=Leitura e aprovação da ata da 14ª Sessão Ordinária', alvo: 'page' },
  { n: '04-pauta-incluir', rota: '/pauta-convocacao?sessao=s15', agora: '2026-09-24T11:10:00Z', w: { estado: 'agendada' }, espera: 'text=Leitura e aprovação da ata da 14ª Sessão Ordinária', alvo: 'page',
    passo: async (pg) => { await pg.getByRole('button', { name: /Incluir item na pauta/ }).click(); await pg.getByLabel('Buscar matéria').fill('mobilidade'); await pg.getByRole('button', { name: 'Buscar', exact: true }).click(); await pg.getByRole('button', { name: 'Escolher REQ 241/2026' }).waitFor({ timeout: 8000 }).catch(() => {}); } },
  { n: '05-conduzir-abrir', rota: '/sessoes/s15/conduzir', agora: '2026-09-24T12:03:00Z', w: { estado: 'agendada' }, espera: 'text=O que a Mesa pode fazer agora', alvo: 'page' },
  { n: '06-chamada', rota: '/sessoes/s15/chamada', agora: '2026-09-24T12:10:00Z', w: { estado: 'aberta' }, espera: 'text=Bruno Lima', alvo: 'viewport' },
  { n: '06b-central-ao-vivo', rota: '/inicio', agora: '2026-09-24T12:12:00Z', w: { estado: 'aberta' }, espera: '.cc-foco', alvo: '.cc-foco' },
  { n: '08-anunciar', rota: '/sessoes/s15/conduzir', agora: '2026-09-24T13:30:00Z', w: { estado: 'aberta', tribuna: TRIB }, espera: 'text=Pauta desta sessão', alvo: 'section.pauta-resumo',
    passo: async (pg) => { await pg.getByRole('button', { name: 'Anunciar PL 22/2026' }).click(); await pg.getByText(/PL 22\/2026 em apreciação/).first().waitFor(); } },
  { n: '09-tribuna', rota: '/sessoes/s15/conduzir', agora: '2026-09-24T13:30:00Z', w: { estado: 'aberta', tribuna: TRIB, apreciacao: 'i3' }, espera: 'text=Pauta desta sessão', alvo: 'section.tribuna' },
  { n: '10-votacao-abrir', rota: '/sessoes/s15/conduzir', agora: '2026-09-24T13:40:00Z', w: { estado: 'aberta', apreciacao: 'i3' }, espera: 'text=Pauta desta sessão', alvo: 'section.votacao' },
  { n: '11-votacao-aberta', rota: '/sessoes/s15/conduzir', agora: '2026-09-24T13:45:00Z', w: { estado: 'aberta', votacao: true, apreciacao: 'i3' }, espera: 'text=Pauta desta sessão', alvo: 'section.votacao' },
  { n: '12-atos', rota: '/sessoes/s15/conduzir', agora: '2026-09-24T13:50:00Z', w: { estado: 'aberta', apreciacao: 'i3' }, espera: 'text=Pedido de vista do PL 31/2026 pela vereadora Lúcia Brito', alvo: 'section.atos-mesa',
    passo: async (pg) => { await pg.getByRole('button', { name: 'Registrar questão de ordem' }).click(); await pg.getByLabel('Questão levantada').fill('Cabe aparte durante a fala do relator?'); } },
  { n: '13-encerrar', rota: '/sessoes/s15/conduzir', agora: '2026-09-24T14:58:00Z', w: { estado: 'aberta' }, espera: 'text=O que a Mesa pode fazer agora', alvo: 'section.atos',
    passo: async (pg) => { await pg.getByRole('button', { name: 'Encerrar a sessão' }).first().click(); } },
  { n: '14-folha', rota: '/sessoes/s15/folha', agora: '2026-09-24T15:10:00Z', w: { estado: 'encerrada', folhas: FOLHAS }, espera: 'text=A folha de presença', alvo: 'page',
    passo: async (pg) => { await pg.frameLocator('iframe').first().locator('text=Folha de presença').waitFor({ timeout: 15000 }); } },
  { n: '15-central-fim', rota: '/inicio', agora: '2026-09-24T15:12:00Z', w: { estado: 'encerrada' }, espera: '.cc-foco', alvo: 'page' },
  { n: '16-tv-apreciacao', rota: '/sessoes/s15/tv', agora: '2026-09-24T13:30:00Z', w: { estado: 'aberta', tribuna: TRIB, apreciacao: 'i3' }, tv: true },
  { n: '17-tv-votacao', rota: '/sessoes/s15/tv', agora: '2026-09-24T13:45:00Z', w: { estado: 'aberta', votacao: true, apreciacao: 'i3' }, tv: true },
  { n: '18-tv-resultado', rota: '/sessoes/s15/tv', agora: '2026-09-24T13:47:00Z', w: { estado: 'aberta', votacao: true, apreciacao: 'i3', encerrarVotacaoAposMs: 6000 }, tv: true, esperaMs: 8000 },
];

(async () => {
  const so = process.argv[2];
  const b = await chromium.launch({ executablePath: '/opt/pw-browsers/chromium', args: ['--lang=pt-BR'] });
  for (const c of CAPS) {
    if (so && !c.n.startsWith(so)) continue;
    const vp = c.tv ? { width: 1920, height: 1080 } : { width: 1280, height: 860 };
    const ctx = await b.newContext({ viewport: vp, colorScheme: 'light', reducedMotion: 'reduce', locale: 'pt-BR', timezoneId: 'America/Fortaleza' });
    const pg = await ctx.newPage();
    if (c.tv) await pg.clock.install({ time: new Date(c.agora) }); else await pg.clock.setFixedTime(new Date(c.agora));
    await pg.addInitScript(() => { const st = document.createElement('style'); st.textContent = 'nextjs-portal{display:none!important}'; document.addEventListener('DOMContentLoaded', () => document.head.appendChild(st)); });
    const erros = []; pg.on('pageerror', (e) => erros.push(e.message));
    const nao = await mundo(pg, c.w);
    await pg.goto(`${BASE}${c.rota}${c.rota.includes('?') ? '&' : '?'}token=${TOKEN}`, { waitUntil: 'domcontentloaded' });
    try {
      if (c.tv) {
        const ov = pg.getByRole('button', { name: /Entrar em tela cheia/ });
        await ov.waitFor({ timeout: 90000 });
        for (let i = 0; i < 10 && (await ov.count()) > 0; i++) { await ov.click({ timeout: 3000 }).catch(() => {}); await pg.waitForTimeout(300); }
        await pg.waitForTimeout(c.esperaMs ?? 2500);
      } else {
        await pg.locator(c.espera).first().waitFor({ timeout: 90000 });
        await pg.waitForFunction(() => !document.querySelector('[aria-busy="true"]'), null, { timeout: 20000 }).catch(() => {});
        if (c.passo) await c.passo(pg);
        await pg.waitForTimeout(700);
      }
    } catch (e) { console.log(`${c.n}: PASSO FALHOU ${e.message.split('\n')[0]}`); }
    const arq = `${OUT}/${c.n}.png`;
    await pg.addStyleTag({ content: 'nextjs-portal{display:none!important}' });
    if (c.alvo && !['page', 'viewport'].includes(c.alvo)) await pg.addStyleTag({ content: 'header.topo{position:static!important}' });
    else await pg.evaluate(() => window.scrollTo(0, 0));
    await pg.waitForTimeout(200);
    if (c.clip) await pg.screenshot({ path: arq, clip: c.clip });
    else if (c.alvo && c.alvo !== 'page' && c.alvo !== 'viewport') await pg.locator(c.alvo).first().screenshot({ path: arq });
    else await pg.screenshot({ path: arq, fullPage: !c.tv && c.alvo !== 'viewport' });
    console.log(`${c.n}: ok${erros.length ? ' ERROS=' + erros.join('|').slice(0, 200) : ''}${nao.length ? ' NAO-SIMULADO=' + [...new Set(nao)].join(', ') : ''}`);
    await ctx.close();
  }
  await b.close();
})();
