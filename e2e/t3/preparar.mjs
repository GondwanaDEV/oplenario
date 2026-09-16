// e2e/t3/preparar.mjs — precondicoes da TRILHA 3 (exercitar as 24 escritas PELA INTERFACE).
//
// Node puro (fetch nativo), zero dependencia nova. Roda DENTRO do container de Playwright com
// --network host (mandato Docker: nada de node no host). Ver e2e/t3/preparar.sh.
//
// NAO E DESTRUTIVO. So faz POST pelas rotas que existem (agendar sessao, transicionar, presenca,
// abrir votacao, criar vereador). Nenhum DELETE/DROP/TRUNCATE, nenhum `docker compose down`,
// nenhuma escrita em /demo-scratch/demo-ids.edn (o artefato da demo e SO LIDO, nunca reescrito).
//
// O que ele NAO faz (nao tem rota HTTP): documento_modelo e notificacao_caixa. Essas duas vem de
// e2e/t3/fixtures.sql, rodado ANTES por preparar.sh. Este script CONFERE que entraram e falha
// nomeando o bloqueio se nao entraram — nunca finge que preparou.
//
// Saida: e2e/t3/.artifacts/t3-ids.json

import { readFileSync, writeFileSync, mkdirSync } from "node:fs";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const AQUI = dirname(fileURLToPath(import.meta.url));
const RAIZ_E2E = resolve(AQUI, "..");
const ARQ_DEMO = resolve(RAIZ_E2E, ".artifacts/demo-ids.edn");
const ARQ_SAIDA = resolve(AQUI, ".artifacts/t3-ids.json");

const BACK = process.env.T3_BACKEND ?? "http://localhost:8888";
const FRONT = process.env.T3_FRONTEND ?? "http://localhost:3000";

const bloqueios = [];
const passos = [];
function bloqueio(chave, texto) {
  bloqueios.push({ chave, texto });
  console.log(`  [BLOQUEIO] ${chave}: ${texto}`);
}
function passo(txt) { console.log(txt); passos.push(txt); }

// ---------------------------------------------------------------- demo-ids.edn (SO LEITURA)
function lerDemoIds() {
  const edn = readFileSync(ARQ_DEMO, "utf8");
  const um = (re) => { const m = edn.match(re); if (!m) throw new Error(`demo-ids.edn sem ${re}`); return m[1]; };
  const bloco = um(/:identidades\s*\{([\s\S]*?)\}/);
  const daBloco = (k) => {
    const m = bloco.match(new RegExp(`:${k}\\s+#uuid\\s+"([0-9a-f-]{36})"`));
    if (!m) throw new Error(`demo-ids.edn sem identidade :${k}`);
    return m[1];
  };
  return {
    ente: um(/:ente\s+#uuid\s+"([0-9a-f-]{36})"/),
    legislatura: um(/:legislatura\s+#uuid\s+"([0-9a-f-]{36})"/),
    identidades: {
      secretaria: daBloco("secretaria"),
      presidente: daBloco("presidente"),
      vereador: daBloco("vereador"),
      cidadao: daBloco("cidadao"),
    },
  };
}

// ---------------------------------------------------------------- HTTP
const jsonToken = (o) => JSON.stringify(o);
async function api(token, metodo, rota, corpo) {
  const r = await fetch(`${BACK}${rota}`, {
    method: metodo,
    headers: {
      Authorization: `Bearer ${token}`,
      ...(corpo === undefined ? {} : { "Content-Type": "application/json" }),
    },
    body: corpo === undefined ? undefined : JSON.stringify(corpo),
  });
  const txt = await r.text();
  let dados = null;
  try { dados = txt ? JSON.parse(txt) : null; } catch { dados = { _bruto: txt.slice(0, 400) }; }
  return { status: r.status, ok: r.ok, dados };
}
function exigir(res, oque) {
  if (!res.ok) throw new Error(`${oque} falhou: HTTP ${res.status} ${JSON.stringify(res.dados).slice(0, 300)}`);
  return res.dados;
}

// ---------------------------------------------------------------- main
const demo = lerDemoIds();
const ENTE = demo.ente;
const TOK = {
  secretaria: jsonToken({ "identidade-id": demo.identidades.secretaria, "ente-id": ENTE, papeis: ["secretario"] }),
  presidente: jsonToken({ "identidade-id": demo.identidades.presidente, "ente-id": ENTE, papeis: ["vereador", "admin_ente"] }),
  vereador: jsonToken({ "identidade-id": demo.identidades.vereador, "ente-id": ENTE, papeis: ["vereador"] }),
};
const urlTok = (papel) => encodeURIComponent(TOK[papel]);
const comToken = (rota, papel) => `${FRONT}${rota}${rota.includes("?") ? "&" : "?"}token=${urlTok(papel)}`;

console.log(`\n=== T3 preparar — ente ${ENTE} — backend ${BACK} ===\n`);

// ---- 0. pre-voo: a stack esta em MODO DEV e os 3 tokens autenticam? ----------------------
passo("0) pre-voo de autenticacao (GET /eu com os 3 tokens)");
const papeisReais = {};
for (const papel of ["secretaria", "presidente", "vereador"]) {
  const r = await api(TOK[papel], "GET", "/eu");
  if (!r.ok) {
    throw new Error(
      `GET /eu como ${papel} devolveu ${r.status}. A stack provavelmente NAO esta em modo dev ` +
      `(APP_ENV != dev/test) — o dev-token so vale com o idp-dev. Nada foi preparado.`);
  }
  papeisReais[papel] = r.dados.ator.papeis;
  console.log(`   ${papel.padEnd(10)} papeis-do-BANCO = ${JSON.stringify(r.dados.ator.papeis)}`);
}

// ---- 0b. identidade -> vereador (o cockpit deriva isso de GET /meu/painel) ----------------
async function vereadorDaIdentidade(papel) {
  const r = await api(TOK[papel], "GET", "/meu/painel");
  if (!r.ok) return null;
  return r.dados["vereador-id"] ?? r.dados.vereadorId ?? null;
}
const vereadorIdDoVereador = await vereadorDaIdentidade("vereador");
const vereadorIdDoPresidente = await vereadorDaIdentidade("presidente");
console.log(`   vereador-id de :vereador   = ${vereadorIdDoVereador}`);
console.log(`   vereador-id de :presidente = ${vereadorIdDoPresidente}`);
if (!vereadorIdDoVereador) bloqueio("meu-painel", "GET /meu/painel nao devolveu vereador-id para :vereador — o cockpit /votar nao resolve meuVereadorId e cai em 'sem-presenca' permanente.");

// ---- 1. sessoes novas (E5) ---------------------------------------------------------------
async function criarSessao(rotulo, agendadaPara) {
  const d = exigir(
    await api(TOK.secretaria, "POST", "/sessoes", {
      "sessao-legislativa-id": demo.legislatura,
      "tipo-sessao": "ordinaria",
      modalidade: "presencial",
      "agendada-para": agendadaPara,
    }),
    `POST /sessoes (${rotulo})`);
  const id = d.id ?? d["sessao-id"];
  if (!id) throw new Error(`POST /sessoes (${rotulo}) sem id no corpo: ${JSON.stringify(d)}`);
  return id;
}
async function lockVersion(id) {
  const d = exigir(await api(TOK.secretaria, "GET", `/sessoes/${id}`), `GET /sessoes/${id}`);
  return { estado: d.estado, lock: d["lock-version"] };
}
async function transicionar(id, para) {
  const { estado, lock } = await lockVersion(id);
  if (estado === para) return { estado, lock };
  exigir(await api(TOK.secretaria, "POST", `/sessoes/${id}/transicao`, { para, "lock-version": lock }),
         `POST /sessoes/${id}/transicao -> ${para}`);
  return await lockVersion(id);
}

const agora = new Date();
passo("\n1) sessao LIMPA para as escritas da tela de chamada (E5)");
const sessaoChamada = await criarSessao("E5-chamada", agora.toISOString());
let est = await transicionar(sessaoChamada, "aberta");
console.log(`   sessao de chamada ${sessaoChamada} -> estado=${est.estado} lock=${est.lock}`);
const chamadaNova = exigir(await api(TOK.secretaria, "GET", `/sessoes/${sessaoChamada}/chamada`), "GET /chamada da sessao nova");
const presentesNaNova = chamadaNova.linhas.filter((l) => l.estado !== "ausente" && l.estado !== "sem-registro").length;
console.log(`   roster=${chamadaNova.linhas.length} linhas · ja marcados=${presentesNaNova} · chamadas conduzidas=${chamadaNova["chamadas-conduzidas"].length}`);
if (presentesNaNova !== 0) bloqueio("sessao-chamada-suja", `a sessao nova ja nasceu com ${presentesNaNova} marcacoes — nao deveria.`);

passo("\n1b) sessao ENCERRADA para a escrita 'gerar a folha' (E5) — a folha so existe em sessao FECHADA");
const sessaoFolha = await criarSessao("E5-folha", new Date(agora.getTime() - 3600e3).toISOString());
await transicionar(sessaoFolha, "aberta");
est = await transicionar(sessaoFolha, "encerrada");
console.log(`   sessao de folha ${sessaoFolha} -> estado=${est.estado} lock=${est.lock}`);

// ---- 2. a sessao do /votar (E5-confirmar-presenca + E6-votar) -----------------------------
passo("\n2) a sessao que /votar de fato abre — /votar NAO aceita parametro de sessao");
const atual = exigir(await api(TOK.vereador, "GET", "/meu/sessao-atual"), "GET /meu/sessao-atual (vereador)");
const sessaoVotar = atual["sessao-id"];
console.log(`   GET /meu/sessao-atual (:vereador) => ${sessaoVotar} (situacao=${atual.situacao})`);
if (!sessaoVotar) {
  bloqueio("meu-sessao-atual-vazio", "GET /meu/sessao-atual devolveu sessao-id nulo — o cockpit /votar mostra 'Nenhuma sessao em curso agora' e E5-confirmar/E6-votar sao inalcancaveis.");
} else if (sessaoVotar !== sessaoChamada) {
  console.log(`   ATENCAO: NAO e uma das sessoes novas. sli_sessoes ordena "aberta ha mais tempo primeiro"`);
  console.log(`   (paineis/db/sli_sessao.clj:111-132), entao uma sessao aberta AGORA sempre entra ATRAS.`);
}

let votacaoAberta = null;
let presencaVereadorZerada = false;
if (sessaoVotar) {
  const ch = exigir(await api(TOK.secretaria, "GET", `/sessoes/${sessaoVotar}/chamada`), "GET /chamada da sessao do /votar");
  const linhaDe = (vid) => ch.linhas.find((l) => l["vereador-id"] === vid) ?? null;
  const lv = linhaDe(vereadorIdDoVereador);
  const lp = linhaDe(vereadorIdDoPresidente);
  console.log(`   estado na chamada: :vereador=${lv?.estado ?? "fora-do-roster"} · :presidente=${lp?.estado ?? "fora-do-roster"}`);

  // (a) :vereador tem de estar AUSENTE, senao o CTA "Confirmar presenca" nunca renderiza
  //     (meu-voto-vista.ts:36 — ciclo 'sem-presenca' exige NAO estar em estado.presentes).
  if (lv && lv.estado !== "ausente") {
    exigir(await api(TOK.secretaria, "POST", `/sessoes/${sessaoVotar}/presenca`, {
      "vereador-id": vereadorIdDoVereador, tipo: "saida", modalidade: "plenario",
      "ocorrido-em": new Date().toISOString(),
    }), "POST /presenca (saida do :vereador)");
    presencaVereadorZerada = true;
    console.log(`   -> :vereador marcado 'saida' (append-only): o ciclo volta a 'sem-presenca'`);
  } else if (lv) {
    presencaVereadorZerada = true;
    console.log(`   -> :vereador ja estava ausente`);
  } else {
    bloqueio("vereador-fora-do-roster", `o vereador de :vereador (${vereadorIdDoVereador}) nao esta no roster da sessao ${sessaoVotar}.`);
  }

  // (b) :presidente tem de estar PRESENTE para poder VOTAR sem depender de E5 rodar antes.
  //     ACHADO ESTRUTURAL (medido em browser, nao lido no codigo): `estado.presentes` do cockpit NAO vem
  //     de snapshot nenhum. `hidratarQuorum` (plenario-reducer.ts:289) so preenche os NUMEROS do quorum;
  //     `presentes` so cresce com evento `presenca.registrada` vindo do SSE. Como o replay da CanalStore e
  //     de 5 min, um vereador presente no BANCO ha horas entra na tela como AUSENTE e cai em 'sem-presenca'.
  //     Por isso o evento e emitido SEMPRE (append-only, o mesmo que o clique 'Presente' da chamada faz),
  //     e por ULTIMO, para ser o mais fresco possivel dentro da janela.
  if (lp) {
    exigir(await api(TOK.secretaria, "POST", `/sessoes/${sessaoVotar}/presenca`, {
      "vereador-id": vereadorIdDoPresidente, tipo: "entrada", modalidade: "plenario",
      "ocorrido-em": new Date().toISOString(),
    }), "POST /presenca (entrada do :presidente)");
    console.log(`   -> :presidente re-marcado PRESENTE agora (o SSE precisa do evento fresco, nao do banco)`);
  } else {
    bloqueio("presidente-fora-do-roster", `o vereador de :presidente (${vereadorIdDoPresidente}) nao esta no roster da sessao ${sessaoVotar}.`);
  }

  // (c) a votacao NOMINAL aberta (E6 nao tem no que votar sem ela; E5-confirmar tambem depende
  //     dela, porque o CTA de presenca so aparece dentro do bloco de votacao do cockpit).
  const lista = exigir(await api(TOK.secretaria, "GET", "/legislativo/proposicoes"), "GET /legislativo/proposicoes");
  const objeto = lista.itens.find((p) => p.estado === "em_pauta") ?? lista.itens[0];
  const rec = exigir(await api(TOK.secretaria, "POST", `/sessoes/${sessaoVotar}/votacoes`, {
    "objeto-tipo": "proposicao", "objeto-id": objeto.id,
    modalidade: "nominal", "quorum-tipo": "maioria_simples",
  }), "POST /sessoes/:id/votacoes");
  votacaoAberta = {
    votacaoId: rec.id, lockVersion: rec["lock-version"] ?? null,
    objetoId: objeto.id, objetoEmenta: objeto.ementa, abertaEm: new Date().toISOString(),
    janelaReplaySse: "PT5M",
  };
  console.log(`   votacao NOMINAL aberta: ${rec.id} sobre ${objeto.id} (${objeto.tipo} ${objeto.sequencial}/${objeto.ano})`);
  bloqueio("janela-sse-5min",
    "DUAS coisas do cockpit /votar nao vem de snapshot nenhum, so do SSE: (a) o PLACAR — `estadoInicial()` " +
    "(plenario-reducer.ts:167) nasce com placar=null; (b) a PRESENCA — `hidratarQuorum` " +
    "(plenario-reducer.ts:289) so preenche os NUMEROS do quorum, nunca `presentes`. A CanalStore Valkey " +
    "guarda 5 min (tempo_real/components.clj:39). Consequencia medida em browser: passada a janela, " +
    "/votar mostra 'Nenhuma votacao aberta' e/ou trata como AUSENTE quem esta presente no banco ha horas. " +
    "Logo E5-confirmar/E6-votar tem de abrir a pagina DENTRO DE ~5 MIN desta preparacao; fora disso, " +
    "re-rode e2e/t3/preparar.sh (ou o proprio spec reabre a votacao: corpo em e6.reabrirVotacao) e, para " +
    "votar, clique 'Confirmar presenca' antes (e6.preambuloObrigatorio).");
}

// ---- 3. E2: os modelos de documento (fixtures.sql) ---------------------------------------
passo("\n3) E2 — legislativo.documento_modelo (vem de fixtures.sql; aqui so a PROVA por HTTP)");
const modelos = exigir(await api(TOK.secretaria, "GET", "/legislativo/documento-modelos"), "GET /legislativo/documento-modelos");
console.log(`   GET /legislativo/documento-modelos => ${modelos.itens.length} modelo(s)`);
for (const m of modelos.itens) console.log(`     - ${m.id} ${m.chave ?? ""} "${m.nome}" (${m["tipo-documento"]})`);
if (modelos.itens.length === 0) {
  bloqueio("documento-modelo-vazio",
    "nenhum modelo ativo. Rode e2e/t3/fixtures.sql antes (preparar.sh faz isso). Sem modelo, " +
    "'Gerar documento' fica disabled para sempre e as 3 escritas de E2 sao inalcancaveis pela interface.");
}

// ---- 4. E8: notificacoes nao-lidas (fixtures.sql) -----------------------------------------
passo("\n4) E8 — inbox de :vereador (vem de fixtures.sql; aqui so a PROVA por HTTP)");
const inbox = await api(TOK.vereador, "GET", "/meu/notificacoes");
let naoLidas = [];
if (inbox.ok) {
  // a chave do wire e `notificacoes` (paineis/wire/out), NAO `itens` — conferido contra a resposta viva.
  const itens = inbox.dados.notificacoes ?? inbox.dados.itens ?? [];
  naoLidas = itens.filter((n) => !n["lida-em"]).map((n) => ({ id: n.id, assunto: n.assunto, categoria: n.categoria }));
  console.log(`   GET /meu/notificacoes => ${itens.length} item(ns), ${naoLidas.length} nao-lida(s)`);
  for (const n of naoLidas) console.log(`     - ${n.id} "${n.assunto}"`);
} else {
  console.log(`   GET /meu/notificacoes => HTTP ${inbox.status}`);
}
if (naoLidas.length === 0) {
  bloqueio("inbox-vazia",
    "a identidade :vereador nao tem notificacao NAO-LIDA. Rode e2e/t3/fixtures.sql. Nao existe rota HTTP " +
    "que gere notificacao (publicar-norma! nao tem chamador em nenhum diplomat) e seed-demo/notificacoes " +
    "cria identidade NOVA e sobrescreve demo-ids.edn — por isso a fixture e SQL.");
}

// ---- 5. varredura das proposicoes: pareceres (E4), autografo (E7), editavel (E3) ----------
passo("\n5) varredura das proposicoes (ficha + pos-aprovacao) — E4/E7/E3");
// [CONSERTO DO INSTRUMENTO] GET /legislativo/proposicoes e' PAGINADO (tamanho-default=20,
// adapters/in/proposicao.clj:30). Sem `?tamanho=100` esta varredura via 20 das 48 proposicoes e
// perdia os pareceres cujas proposicoes cairam da 1a pagina — foi o que deixou
// e4.parecerEditavelId=null (o parecer 50a690c2, em_elaboracao, existe no banco desde a semente).
// tamanho-max e' 100 (idem:29); com 48 proposicoes uma pagina basta. AVISO medido: a ordem que a
// rota devolve NAO e' estavel entre corridas (comparei dois artefatos seguidos e os mesmos ids
// trocaram de posicao), entao e3.proposicaoEditavelId / e7.proposicaoParaAutografoId ja variavam de
// preparacao pra preparacao ANTES desta mudanca — os specs leem o artefato em runtime e se adaptam.
const listaProps = exigir(await api(TOK.secretaria, "GET", "/legislativo/proposicoes?tamanho=100"), "GET /legislativo/proposicoes");
const TERMINAIS = new Set(["arquivada", "aprovada", "rejeitada", "prejudicada", "retirada", "transformada_em_norma"]);
const pareceres = [];
const semAutografo = [];
const editaveis = [];
for (const p of listaProps.itens) {
  const f = await api(TOK.secretaria, "GET", `/legislativo/proposicoes/${p.id}/ficha`);
  if (!f.ok) continue;
  const prop = f.dados.proposicao;
  for (const pa of f.dados.pareceres ?? []) {
    pareceres.push({ ...pa, proposicaoId: p.id, proposicaoEstado: p.estado });
  }
  const temTexto = typeof prop.texto === "string" && prop.texto.trim().length > 0;
  if (!TERMINAIS.has(p.estado)) {
    editaveis.push({ id: p.id, tipo: p.tipo, ano: p.ano, sequencial: p.sequencial, estado: p.estado, lockVersion: prop["lock-version"], ementa: p.ementa, temTexto, autorTipo: prop["autor-tipo"] ?? null, autorId: prop["autor-id"] ?? null });
  }
  if (temTexto) {
    const pa = await api(TOK.secretaria, "GET", `/legislativo/proposicoes/${p.id}/pos-aprovacao`);
    if (pa.ok && !pa.dados.autografo) {
      // `aprovada` (guarda-autografo-votacao, T3-A) vem do MESMO campo que o backend usa pra guardar o
      // autografo (ProposicaoDetalheOut.aprovada, computado do ATO — nunca do rotulo `p.estado`). A ficha
      // reusa o wire de detalhe (commit 86b64fa), entao ja' vem aqui sem consulta extra.
      semAutografo.push({ id: p.id, tipo: p.tipo, ano: p.ano, sequencial: p.sequencial, estado: p.estado, aprovada: prop.aprovada === true, autorId: prop["autor-id"], ementa: p.ementa });
    }
  }
}
console.log(`   ${listaProps.itens.length} proposicoes · ${pareceres.length} parecer(es) · ${semAutografo.length} com texto vigente e SEM autografo · ${editaveis.length} editavel(is)`);

// E4: casar relator com identidade logavel
const relatorDe = (vid) => (vid === vereadorIdDoVereador ? "vereador" : vid === vereadorIdDoPresidente ? "presidente" : null);
for (const pa of pareceres) pa.identidadeLogavelDoRelator = relatorDe(pa["relator-id"]);
const ESTADOS_TERMINAIS_PARECER = new Set(["aprovado", "rejeitado", "prejudicado", "prazo_vencido"]);
const parecerAguardando = pareceres.find((p) => p.estado === "aguardando_assinatura" && p.identidadeLogavelDoRelator)
  ?? pareceres.find((p) => p.estado === "aguardando_assinatura") ?? null;
const parecerEditavel = pareceres.find((p) => !ESTADOS_TERMINAIS_PARECER.has(p.estado) && p.id !== parecerAguardando?.id) ?? null;
for (const pa of pareceres) console.log(`     parecer ${pa.id} estado=${pa.estado} relator=${pa["relator-id"]} logavel=${pa.identidadeLogavelDoRelator ?? "-"}`);
if (!parecerAguardando) bloqueio("parecer-aguardando-assinatura", "nenhum parecer em 'aguardando_assinatura' — 'emitir parecer' nao mostra a transicao real.");
else if (!parecerAguardando.identidadeLogavelDoRelator) bloqueio("parecer-relator-nao-logavel", `o parecer ${parecerAguardando.id} tem relator ${parecerAguardando["relator-id"]}, que nao e nenhuma das identidades logaveis — /parecer/:id/assinar vai 404 por posse.`);
if (!parecerEditavel) bloqueio("parecer-editavel", "nenhum parecer em estado editavel separado do de assinatura.");

// E7 — guarda-autografo-votacao (254a768/86b64fa/d93b077): a pre-condicao do autografo deixou de ser o
// rotulo `proposicao.estado` e passou a ser o ATO (votacao encerrada com resultado 'aprovada'). Dois
// alvos DISTINTOS a partir daqui:
//   - autografoAlvo (preferencia: NAO-terminal e, dentro disso, de autoria da identidade :vereador, para
//     o spec poder cruzar autor <-> quem ve o resultado) — NUNCA aprovado, serve a recusa 409.
//   - candidataAprovar / proposicaoAprovada — aprovada DE VERDADE, pelo rito real (abrir votacao +
//     registrar voto + encerrar: as MESMAS 3 rotas que a sessao do E6, duas secoes acima, ja usa). O
//     bloqueio antigo "e7-aprovada-sem-autografo" dizia que nao havia caminho HTTP ate' 'aprovada' —
//     isso seguia verdade para o ROTULO (nenhuma rota transiciona legislativo.proposicoes.estado), mas
//     deixou de importar: a pre-condicao real do autografo nunca foi o rotulo, sempre foi o ato, e o ato
//     ja tinha rota.
const naoTerminalSemAutografo = semAutografo.filter((p) => !TERMINAIS.has(p.estado));
const autografoAlvo =
  naoTerminalSemAutografo.find((p) => p.autorId === vereadorIdDoVereador)
  ?? naoTerminalSemAutografo.find((p) => p.tipo === "projeto_lei")
  ?? naoTerminalSemAutografo[0] ?? semAutografo[0] ?? null;

// candidataAprovar: nao-terminal, sem autografo, livre de qualquer id que OUTRO grupo desta MESMA corrida
// ja reservou (o objeto da votacao do E6, a proposicao editavel do E3, a proposicao de cada parecer do
// E4) — e nunca a mesma de autografoAlvo (que fica reservada para a recusa).
const reservadosNestaCorrida = new Set(
  [autografoAlvo?.id, votacaoAberta?.objetoId, editaveis[0]?.id, ...pareceres.map((p) => p.proposicaoId)]
    .filter(Boolean),
);
const candidataAprovar = naoTerminalSemAutografo.find((p) => !reservadosNestaCorrida.has(p.id)) ?? null;

// aprovarDeVerdade — o RITO REAL (abrir votacao + registrar voto + encerrar com resultado='aprovada'),
// fatorado pra servir os DOIS alvos que passam por ele: proposicaoAprovada (E7 caminho feliz) e
// textoTrocado (E7-A2, achado T3-A2 — abaixo). Mesmas 3 rotas, mesma prova pos-encerramento (reconsulta
// GET /legislativo/proposicoes/:id .aprovada, a MESMA leitura que o guard do backend usa — nunca confia
// so' no corpo do encerramento). Lanca alto se o resultado nao fechar 'aprovada' ou se .aprovada divergir
// — nada aqui e' presumido.
async function aprovarDeVerdade(candidata, rotulo) {
  passo(`\n5b) ${rotulo} — aprovando DE VERDADE ${candidata.id} (estava '${candidata.estado}') pelo rito real`);
  const abertura = exigir(
    await api(TOK.secretaria, "POST", `/sessoes/${sessaoChamada}/votacoes`, {
      "objeto-tipo": "proposicao", "objeto-id": candidata.id,
      modalidade: "nominal", "quorum-tipo": "maioria_simples",
    }),
    `POST /sessoes/:id/votacoes (${rotulo} aprovar de verdade)`);
  const votanteId = vereadorIdDoVereador ?? vereadorIdDoPresidente;
  exigir(
    await api(TOK.secretaria, "POST", `/sessoes/${sessaoChamada}/votacoes/${abertura.id}/votos`, {
      voto: "sim", "vereador-id": votanteId,
    }),
    `POST .../votos (${rotulo} aprovar de verdade)`);
  const encerramento = exigir(
    // sec MEDIUM-1: `base-membros` NAO vai mais no corpo (o backend o computa server-side da composicao real
    // da Casa; mandar no corpo agora e' 400). So o lock-version do CAS.
    await api(TOK.secretaria, "POST", `/sessoes/${sessaoChamada}/votacoes/${abertura.id}/encerramento`, {
      "lock-version": abertura["lock-version"] ?? 0,
    }),
    `POST .../encerramento (${rotulo} aprovar de verdade)`);
  if (encerramento.resultado !== "aprovada") {
    throw new Error(
      `${rotulo}: a votacao de aprovacao (maioria_simples, 1 sim x 0 nao) fechou com resultado='${encerramento.resultado}', ` +
      `nao 'aprovada' — logic/resultado-votacao deveria aprovar com sim>nao. Candidata ${candidata.id}, ` +
      `votacao ${abertura.id}. Nada foi assumido: pare e investigue antes de rodar o spec.`);
  }
  // PROVA REAL, nao suposta: reconsulta a proposicao pela MESMA leitura que o guard do backend usa
  // (Repo/proposicao-aprovada-em-votacao?, exposta em ProposicaoDetalheOut.aprovada) — nao confia so' no
  // corpo do encerramento.
  const detalhe = exigir(
    await api(TOK.secretaria, "GET", `/legislativo/proposicoes/${candidata.id}`),
    "GET /legislativo/proposicoes/:id (prova pos-encerramento)");
  if (detalhe.aprovada !== true) {
    throw new Error(
      `${rotulo}: votacao ${abertura.id} encerrou com resultado=aprovada, mas GET /legislativo/proposicoes/` +
      `${candidata.id} devolveu aprovada=${detalhe.aprovada}. proposicao-aprovada-em-votacao? ` +
      `diverge do encerramento — bug real, nao presuma que preparou.`);
  }
  console.log(`   votacao ${abertura.id} encerrada: resultado=aprovada · GET detalhe confirma aprovada=true`);
  return { ...candidata, votacaoId: abertura.id, textoNoMomentoDaAbertura: detalhe.texto };
}

let proposicaoAprovada = null;
if (!candidataAprovar) {
  bloqueio("e7-sem-candidata-para-aprovar",
    "todas as proposicoes nao-terminais com texto vigente e sem autografo desta corrida ja estao " +
    "reservadas por outro grupo (E3/E4/E6) ou sao a propria autografoAlvo. E7 fica sem alvo para o " +
    "caminho feliz real; rode de novo (a ordem da varredura nao e estavel entre corridas) ou amplie o pool.");
} else {
  proposicaoAprovada = await aprovarDeVerdade(candidataAprovar, "E7");
}

// ---- 5c. E7-A2 (T3-A2) — matéria aprovada, TEXTO TROCADO DEPOIS: o autografo tem de levar a versao
// VOTADA, nao a vigente na geracao (achado T3-A2, mig 0075 — apps/backend `abrir!` congela
// texto_versao_id NA ABERTURA da votacao). candidataTextoTrocado e' outra proposicao nao-terminal, sem
// autografo, livre de TUDO que ja foi reservado (inclusive candidataAprovar agora, que ja' consumiu seu
// lugar acima) — nunca a mesma da recusa (autografoAlvo) nem a do caminho feliz simples (candidataAprovar).
if (candidataAprovar) reservadosNestaCorrida.add(candidataAprovar.id);
const candidataTextoTrocado = naoTerminalSemAutografo.find((p) => !reservadosNestaCorrida.has(p.id)) ?? null;

let textoTrocado = null;
if (!candidataTextoTrocado) {
  bloqueio("e7a2-sem-candidata-para-texto-trocado",
    "todas as proposicoes nao-terminais com texto vigente e sem autografo desta corrida ja estao " +
    "reservadas (E3/E4/E6/autografoAlvo/candidataAprovar). O caso T3-A2 (texto trocado apos a aprovacao) " +
    "fica sem alvo; rode de novo (a ordem da varredura nao e estavel entre corridas) ou amplie o pool.");
} else {
  const aprovada = await aprovarDeVerdade(candidataTextoTrocado, "E7-A2");
  const textoVotado = aprovada.textoNoMomentoDaAbertura;
  if (typeof textoVotado !== "string" || textoVotado.trim().length === 0) {
    throw new Error(
      `E7-A2: GET /legislativo/proposicoes/${candidataTextoTrocado.id} nao devolveu .texto pos-aprovacao ` +
      `(veio ${JSON.stringify(textoVotado)}) — sem o texto VOTADO nao ha' o que comparar contra o texto ` +
      `trocado depois. Nada foi assumido.`);
  }
  passo(`\n5c) E7-A2 — PATCH promovendo um texto NOVO em ${candidataTextoTrocado.id}, DEPOIS da aprovacao`);
  const fresco = exigir(
    await api(TOK.secretaria, "GET", `/legislativo/proposicoes/${candidataTextoTrocado.id}`),
    "GET /legislativo/proposicoes/:id (lock-version fresco pro PATCH do E7-A2)");
  const textoNovo =
    `${textoVotado}\n\n[T3-A2 — texto promovido DEPOIS da aprovacao, ${new Date().toISOString()}: ` +
    `este paragrafo NAO foi deliberado pelo plenario. O autografo tem de ignorar esta versao.]`;
  exigir(
    await api(TOK.secretaria, "PATCH", `/legislativo/proposicoes/${candidataTextoTrocado.id}`, {
      "lock-version": fresco["lock-version"], texto: textoNovo,
    }),
    "PATCH /legislativo/proposicoes/:id (E7-A2 — promover texto novo pos-aprovacao)");
  // PROVA REAL: reconsulta e confirma que o vigente MUDOU — sem isto o PATCH podia ter sido um no-op
  // silencioso (ex.: campo dropado no adapters/in) e o teste estaria comparando o mesmo texto consigo.
  const posPatch = exigir(
    await api(TOK.secretaria, "GET", `/legislativo/proposicoes/${candidataTextoTrocado.id}`),
    "GET /legislativo/proposicoes/:id (prova pos-PATCH do E7-A2)");
  if (posPatch.texto !== textoNovo) {
    throw new Error(
      `E7-A2: PATCH em ${candidataTextoTrocado.id} devolveu 200, mas GET .texto pos-PATCH nao bate com o ` +
      `texto enviado (veio ${JSON.stringify(posPatch.texto)}) — o vigente pode nao ter mudado. Nao presuma.`);
  }
  if (posPatch.texto === textoVotado) {
    throw new Error(`E7-A2: o texto pos-PATCH ficou IGUAL ao texto votado — a fixture nao criou divergencia nenhuma.`);
  }
  console.log(`   texto vigente TROCOU: ${textoVotado.length} chars (votado) -> ${posPatch.texto.length} chars (atual)`);
  textoTrocado = {
    proposicaoId: candidataTextoTrocado.id,
    votacaoId: aprovada.votacaoId,
    textoVotado,
    textoAtualPosPromocao: posPatch.texto,
  };
}

// ---- 6. E1: um vereador NOVO, sem mandato (todos os 17 da demo tem mandato vigente) --------
passo("\n6) E1 — vereador novo SEM mandato (os 17 da demo ja tem mandato vigente -> 409 de sobreposicao)");
const carimbo = new Date().toISOString().replace(/[-:.TZ]/g, "").slice(0, 14);
const novoVereador = exigir(await api(TOK.secretaria, "POST", "/cadastros/vereadores", {
  nome: `T3 Alvo de Mandato ${carimbo}`, "nome-parlamentar": `T3 Mandato ${carimbo}`,
}), "POST /cadastros/vereadores");
console.log(`   vereador sem mandato: ${novoVereador.id} "T3 Alvo de Mandato ${carimbo}"`);

// [CONSERTO 16/09] alvos de 'editar' e 'registrar licenca' DINAMICOS, nunca UUID cravado. Ate' agora este
// arquivo gravava `vereadorParaEditarId: "45c0a7d4-..."` (id de uma Casa CONGELADA): num seed fresco de CI
// esse id nao existe, o ?v=<id> cai FORA da lista e a tela abre o PRIMEIRO vereador (selecaoInicial,
// cadastro-vereadores-vista.ts:94) — as escritas de 'editar'/'registrar mandato' disparam pro vereador
// ERRADO e o waitForResponse do spec (que casa a URL pelo id) nunca resolve. Mesma classe de bug que o
// fixtures.sql tinha (id da identidade :vereador). O roster de sessaoChamada JA e' o conjunto de vereadores
// com mandato VIGENTE — o unico estado que da o 409 de sobreposicao e deixa 'editar' com alvo real.
// Excluimos as 2 identidades logaveis: 'editar' RENOMEIA o alvo e licenciar mexe no roster/quorum — nao
// pode cair sobre quem :vereador/:presidente usam em E5/E6 (E5.spec.ts:114 tambem exclui estes ids).
const logaveis = new Set([vereadorIdDoVereador, vereadorIdDoPresidente].filter(Boolean));
const rosterEditavel = chamadaNova.linhas.map((l) => l["vereador-id"]).filter((vid) => vid && !logaveis.has(vid));
const vereadorParaEditarId = rosterEditavel[0] ?? null;
const vereadorParaLicencaId = rosterEditavel[1] ?? null;
if (!vereadorParaEditarId) {
  bloqueio("e1-sem-vereador-editavel",
    "o roster da sessao de chamada nao tem vereador com mandato vigente fora das 2 identidades logaveis — " +
    "'editar vereador' e 'mandato sobreposto (409)' ficam sem alvo. Semeie a Casa cheia (demo/semear-tudo.sh).");
}
console.log(`   E1 alvo editar/mandato = ${vereadorParaEditarId} · alvo licenca = ${vereadorParaLicencaId}`);

// [CONSERTO 16/09] E3 [ACHADO]: o par de alvos (um COM texto vigente, um SEM) precisa vir por PROPRIEDADE,
// nao por indice de uma lista cuja ordem nao e estavel. Classificamos os editaveis por `temTexto` (o sinal
// real que o spec exercita: com texto -> o form reenvia `texto` e o lock sobe 2; sem texto -> nao envia e
// sobe 1). Se o seed nao tiver nenhum editavel SEM texto, FABRICAMOS um (POST cria proposicao com lock 0 e
// sem texto) — defensivo: se a criacao falhar, so E3 fica sem alvo (test.skip no spec), nunca derruba a prep.
// Reserva os ids que OUTROS grupos (ou os OUTROS testes de E3) ja consomem, para o par do [ACHADO] nao
// colidir entre arquivos que rodam em paralelo (E6 abre votacao sobre e6.objetoId; E3 usa editaveis[0..2]).
const reservadosE3 = new Set(
  [editaveis[0]?.id, editaveis[1]?.id, editaveis[2]?.id, votacaoAberta?.objetoId, autografoAlvo?.id].filter(Boolean),
);
const alvoComTexto =
  editaveis.find((e) => e.temTexto && !reservadosE3.has(e.id)) ?? editaveis.find((e) => e.temTexto) ?? null;
let alvoSemTexto = editaveis.find((e) => !e.temTexto && !reservadosE3.has(e.id)) ?? null;
if (!alvoSemTexto && vereadorIdDoVereador) {
  try {
    const tipoValido = editaveis[0]?.tipo ?? listaProps.itens[0]?.tipo ?? "requerimento";
    const nova = exigir(await api(TOK.secretaria, "POST", "/legislativo/proposicoes", {
      tipo: tipoValido, ano: new Date().getFullYear(), ementa: `T3-E3 alvo sem-texto ${carimbo}`,
      "autor-tipo": "vereador", "autor-id": vereadorIdDoVereador,
    }), "POST /legislativo/proposicoes (E3 alvo sem-texto)");
    alvoSemTexto = { id: nova.id, tipo: tipoValido, ano: nova.ano ?? new Date().getFullYear(), estado: "em_elaboracao", lockVersion: 0, temTexto: false };
    console.log(`   E3 alvo sem-texto FABRICADO: ${nova.id}`);
  } catch (e) {
    bloqueio("e3-sem-alvo-sem-texto", `nao consegui fabricar uma proposicao sem texto para E3: ${e.message}`);
  }
}
if (!alvoComTexto) bloqueio("e3-sem-alvo-com-texto", "nenhum editavel COM texto vigente no seed — E3 [ACHADO] fica sem par.");
console.log(`   E3 alvoComTexto = ${alvoComTexto?.id ?? "-"} · alvoSemTexto = ${alvoSemTexto?.id ?? "-"}`);

// ---- 7. artefato -------------------------------------------------------------------------
const artefato = {
  geradoEm: new Date().toISOString(),
  aviso: "Gerado por e2e/t3/preparar.mjs. Nenhum id aqui foi inventado: todos vieram de uma resposta HTTP desta corrida.",
  base: { frontend: FRONT, backend: BACK },
  ente: ENTE,
  legislatura: demo.legislatura,
  identidades: demo.identidades,
  papeisReaisDoBanco: papeisReais,
  vereadorPorIdentidade: { vereador: vereadorIdDoVereador, presidente: vereadorIdDoPresidente },
  tokens: {
    secretaria: { json: TOK.secretaria, url: urlTok("secretaria") },
    presidente: { json: TOK.presidente, url: urlTok("presidente") },
    vereador: { json: TOK.vereador, url: urlTok("vereador") },
  },

  e1: {
    nota: "criar vereador e criar mandato sao criacoes do zero; so 'editar vereador' e 'registrar licenca' precisam de alvo. Os alvos sao DINAMICOS (roster da sessao de chamada, fora das identidades logaveis) — nunca UUID cravado, que num seed fresco cai fora da lista e a tela abre o 1o vereador.",
    urlLista: comToken("/cadastros/vereadores", "secretaria"),
    vereadorParaEditarId,
    urlVereadorParaEditar: vereadorParaEditarId ? comToken(`/cadastros/vereadores?v=${vereadorParaEditarId}`, "secretaria") : null,
    vereadorSemMandatoId: novoVereador.id,
    urlVereadorSemMandato: comToken(`/cadastros/vereadores?v=${novoVereador.id}`, "secretaria"),
    vereadorComMandatoVigenteParaLicencaId: vereadorParaLicencaId,
    urlVereadorParaLicenca: vereadorParaLicencaId ? comToken(`/cadastros/vereadores?v=${vereadorParaLicencaId}`, "secretaria") : null,
    avisoLicenca: "registrar licenca muda o estado do vereador para 'licenciado' e mexe no roster/quorum das sessoes — por isso o alvo e um vereador do roster que NAO e nenhuma das identidades logaveis (vereador/presidente).",
    avisoMandato: "os vereadores do roster tem mandato VIGENTE; registrar mandato em qualquer um deles da 409 de sobreposicao. Use vereadorSemMandatoId para o caminho feliz.",
  },

  e2: {
    url: comToken("/expediente", "secretaria"),
    modelos: modelos.itens.map((m) => ({ id: m.id, chave: m.chave ?? null, nome: m.nome, tipoDocumento: m["tipo-documento"] })),
    modeloComPlaceholderId: modelos.itens[0]?.id ?? null,
    nota: "os dois modelos tem campo {{ }} de proposito — o plano exige exercitar 'campo nao preenchido'. Fonte: e2e/t3/fixtures.sql (nao ha rota POST de modelo).",
  },

  e3: {
    urlEditor: comToken("/editor-proposicao", "secretaria"),
    proposicaoEditavelId: editaveis[0]?.id ?? null,
    proposicaoEditavel: editaveis[0] ?? null,
    urlProposicaoEditavel: editaveis[0] ? comToken(`/ficha-materia/${editaveis[0].id}`, "secretaria") : null,
    outrasEditaveis: editaveis.slice(1, 6),
    // Par de alvos do [ACHADO] "editar so a ementa cria versao de texto identica" — por PROPRIEDADE
    // (temTexto), nunca por indice. alvoSemTexto pode ter sido FABRICADO (lock 0, sem texto) quando o seed
    // nao trouxe nenhum. O spec pula (test.skip) se algum dos dois faltar, em vez de reprovar.
    alvoComTexto: alvoComTexto && { id: alvoComTexto.id, lockVersion: alvoComTexto.lockVersion, temTexto: true },
    alvoSemTexto: alvoSemTexto && { id: alvoSemTexto.id, lockVersion: alvoSemTexto.lockVersion, temTexto: false },
  },

  e4: {
    parecerEditavelId: parecerEditavel?.id ?? null,
    parecerEditavel: parecerEditavel,
    urlParecerEditavel: parecerEditavel ? comToken(`/parecer/${parecerEditavel.id}`, "secretaria") : null,
    parecerAguardandoAssinaturaId: parecerAguardando?.id ?? null,
    parecerAguardandoAssinatura: parecerAguardando,
    urlEmitirSecretaria: parecerAguardando ? comToken(`/parecer/${parecerAguardando.id}`, "secretaria") : null,
    urlAssinarVereador: parecerAguardando?.identidadeLogavelDoRelator
      ? comToken(`/parecer/${parecerAguardando.id}/assinar`, parecerAguardando.identidadeLogavelDoRelator) : null,
    identidadeDoRelator: parecerAguardando?.identidadeLogavelDoRelator ?? null,
    todosOsPareceres: pareceres,
    urlDarCiencia: comToken("/vereador", "vereador"),
    avisoCiencia: "legislativo.ciencia_vereador esta VAZIA e nao ha rota HTTP que crie um evento de ciencia pendente — 'dar ciencia' pode nao ter card na home do vereador.",
    avisoEmitirDuasVezes: "emitir um parecer que NAO transiciona para estado terminal deixa a tela oferecendo 'Emitir parecer' de novo, e o backend aceita — nao e 409.",
  },

  e5: {
    sessaoChamadaId: sessaoChamada,
    urlChamada: comToken(`/sessoes/${sessaoChamada}/chamada`, "secretaria"),
    urlPlenario: comToken(`/sessoes/${sessaoChamada}/plenario`, "secretaria"),
    rosterDaSessaoChamada: chamadaNova.linhas.map((l) => ({ vereadorId: l["vereador-id"], nome: l.nome, estado: l.estado })),
    sessaoFolhaId: sessaoFolha,
    urlFolha: comToken(`/sessoes/${sessaoFolha}/folha`, "secretaria"),
    confirmarPresenca: {
      url: comToken("/votar", "vereador"),
      sessaoQueOVotarAbre: sessaoVotar,
      vereadorId: vereadorIdDoVereador,
      presencaZerada: presencaVereadorZerada,
      nota: "/votar NAO aceita parametro de sessao — deriva de GET /meu/sessao-atual. Por isso confirmar-presenca NAO roda na sessaoChamadaId.",
    },
  },

  e6: {
    url: comToken("/votar", "presidente"),
    identidadeQueVota: "presidente",
    motivoDaIdentidade: "a identidade :presidente tem papel 'vereador' no banco E esta PRESENTE na sessao do /votar — vota sem depender de E5 rodar antes. A :vereador foi deliberadamente deixada AUSENTE para E5 exercitar 'confirmar a propria presenca'.",
    preambuloObrigatorio: "se os botoes Sim/Nao/Abster NAO aparecerem e a tela mostrar 'Confirme sua presenca para poder votar', clique 'Confirmar presenca' PRIMEIRO e so entao vote. Isso nao e defeito do spec: `estado.presentes` do cockpit so e' alimentado por evento SSE (hidratarQuorum nao preenche presentes), e o replay da CanalStore e de 5 min — passada a janela, quem esta presente no banco aparece como ausente na tela.",
    rotulosDosBotoes: { sim: "Sim", nao: "Não", abstencao: "Abster" },
    sessaoId: sessaoVotar,
    vereadorId: vereadorIdDoPresidente,
    votacao: votacaoAberta,
    reabrirVotacao: sessaoVotar ? {
      metodo: "POST", url: `${BACK}/sessoes/${sessaoVotar}/votacoes`,
      header: "Authorization: Bearer <tokens.secretaria.json>",
      corpo: { "objeto-tipo": "proposicao", "objeto-id": votacaoAberta?.objetoId ?? null, modalidade: "nominal", "quorum-tipo": "maioria_simples" },
      quando: "se passaram mais de ~5 min desde geradoEm — a janela de replay do SSE e de 5 min e o placar nao vem de snapshot.",
    } : null,
  },

  e7: {
    proposicaoParaAutografoId: autografoAlvo?.id ?? null,
    proposicaoParaAutografo: autografoAlvo,
    url: autografoAlvo ? comToken(`/pos-aprovacao/${autografoAlvo.id}`, "secretaria") : null,
    candidatosSemAutografo: semAutografo,
    candidatosNaoTerminais: naoTerminalSemAutografo,
    proposicaoAprovadaId: proposicaoAprovada?.id ?? null,
    proposicaoAprovada: proposicaoAprovada,
    urlAprovada: proposicaoAprovada ? comToken(`/pos-aprovacao/${proposicaoAprovada.id}`, "secretaria") : null,
    nota: "guarda-autografo-votacao (254a768/86b64fa/d93b077): proposicaoParaAutografoId NUNCA foi aprovada " +
      "(alvo da recusa 409 — gerar autografo sobre ele deve SEMPRE falhar). proposicaoAprovadaId FOI " +
      "aprovada DE VERDADE nesta corrida, pelo rito real (abrir votacao + registrar voto + encerrar, e a " +
      "prova e' GET /legislativo/proposicoes/:id .aprovada === true, nao o corpo do encerramento) — e' o " +
      "unico alvo do caminho feliz de gerar autografo. Depois que o spec gerar o autografo sobre " +
      "proposicaoAprovadaId, a tramitacao executiva nasce 'aguardando' na MESMA tx — so entao 'registrar " +
      "resposta do Executivo' fica exercitavel, na MESMA pagina.",

    textoTrocado: textoTrocado && {
      proposicaoTextoTrocadoId: textoTrocado.proposicaoId,
      votacaoTextoTrocadoId: textoTrocado.votacaoId,
      textoVotado: textoTrocado.textoVotado,
      textoAtualPosPromocao: textoTrocado.textoAtualPosPromocao,
      url: comToken(`/pos-aprovacao/${textoTrocado.proposicaoId}`, "secretaria"),
      nota: "T3-A2: proposicaoId foi aprovada DE VERDADE pelo rito real (aprovarDeVerdade, mesma prova de " +
        "proposicaoAprovada) e, DEPOIS do encerramento, levou um PATCH .../proposicoes/:id com :texto NOVO " +
        "(promove uma versao 'edicao' — permitido ate' estado terminal, e votar nao termina a proposicao). " +
        "textoVotado e' o texto que estava vigente QUANDO a votacao abriu (congelado server-side por " +
        "abrir!, mig 0075); textoAtualPosPromocao e' o vigente AGORA, depois do PATCH — os dois divergem de " +
        "proposito. Nenhum dos dois e' o texto_versao_id (a API nao expoe esse id em lugar nenhum fora do " +
        "wire do autografo ja gerado — so' AutografoOut carrega texto-versao-id); os UUIDs para comparar " +
        "ficam em e2e/t3/.artifacts/t3-versoes.json, escritos por preparar.sh via psql DIRETO (mesmo " +
        "precedente de fixtures.sql — nao ha' rota HTTP para isto). O autografo gerado sobre proposicaoId " +
        "tem de carregar textoVersaoVotadaId (t3-versoes.json), nunca textoVersaoAtualId.",
    },
  },

  e8: {
    url: comToken("/notificacoes", "vereador"),
    identidade: "vereador",
    naoLidas,
    naoLidaId: naoLidas[0]?.id ?? null,
    nota: "cada run de E8 CONSOME uma notificacao (marcar como lida e irreversivel). Sao 3 fixtures; depois disso, re-rode fixtures.sql com chaves novas.",
  },

  bloqueios,
};

mkdirSync(dirname(ARQ_SAIDA), { recursive: true });
writeFileSync(ARQ_SAIDA, JSON.stringify(artefato, null, 2) + "\n", "utf8");

console.log(`\n=== artefato: ${ARQ_SAIDA} ===`);
console.log(`bloqueios: ${bloqueios.length}`);
for (const b of bloqueios) console.log(`  - ${b.chave}`);
console.log("");
