// Reducer PURO do painel ao vivo: dobra os 7 eventos do canal plenário no estado de view.
// É o núcleo lógico do HERO (onde mora bug) — testado em plenario-reducer.test.ts. Sem IO: o hook
// (use-plenario) faz o EventSource/fetch e delega a este reducer. O cronômetro NÃO vive aqui: o servidor
// emite só os MARCOS (iniciada/pausada/retomada/tempo_adicional); o display por segundo é recomputado na UI
// a partir de `iniciouEm` + marcos (decisão de §22.6 eixo G — ticks por segundo são descartados no fio).

import type { EventoPlenario, SessaoOut } from "./contrato";
import type { ComposicaoSessaoOut, QuorumSessaoOut, TribunaOut } from "./contrato-sessoes.gen";

/** Tipos de evento de presença que marcam PRESENTE (logic/tipos-presenca-positiva); "saida" remove. */
const PRESENCA_POSITIVA = new Set(["entrada", "retorno", "mudanca_modalidade"]);

/** Os 3 tipos de `EventoPlenario` que tocam `oradorAtual`/`marcosCronometro` — FONTE ÚNICA da
 * precedência de `oradorAtual`/`marcosCronometro` em `hidratarTribuna`. Fix round 1 (I2): antes havia
 * um Set único para os 5 tipos, e o descarte por precedência era do SNAPSHOT INTEIRO — um
 * `inscricao.registrada` alheio em voo jogava fora o orador junto, apagando quem está com a palavra
 * por até 30s. Agora cada CAMPO do snapshot tem seu próprio contador e é aplicado/descartado
 * independente do outro. Conferido contra `TIPOS_PLENARIO` em `contrato.ts` e contra os `case`s de
 * `aplicarEvento` abaixo — não redigitar esta lista em outro lugar. */
const TIPOS_EVENTO_FALA = new Set(["fala.iniciada", "fala.cronometro", "fala.encerrada"]);

/** Os 2 tipos de `EventoPlenario` que tocam `inscritos` — FONTE ÚNICA da precedência de `inscritos`
 * em `hidratarTribuna`. Ver a docstring de `TIPOS_EVENTO_FALA` sobre por que são dois Sets, não um. */
const TIPOS_EVENTO_INSCRICAO = new Set(["inscricao.registrada", "inscricao.desistida"]);

/** Os 3 tipos de `EventoPlenario` que tocam `placar` — FONTE ÚNICA da precedência de `hidratarVotacao`
 * (fatia "demo-tres-consertos" #2b). Mesmo racional de `TIPOS_EVENTO_FALA`/`TIPOS_EVENTO_INSCRICAO`: um
 * evento vivo chegado DEPOIS do disparo do snapshot de recuperação não pode ser sobrescrito por uma
 * resposta HTTP atrasada — o snapshot só se aplica se `votacaoEventoSeq` não avançou nesse meio-tempo. */
const TIPOS_EVENTO_VOTACAO = new Set(["votacao.aberta", "voto.registrado", "votacao.encerrada"]);

export interface MarcoCronometro {
  tipo: string; // pausada | retomada | aparte_concedido | tempo_adicional_concedido
  ocorridoEm: string;
  segundosAdicionais?: number | null;
}

export interface OradorAtual {
  falaId: string;
  oradorId: string;
  tipoFala: string;
  fase: string;
  iniciouEm: string; // âncora do cronômetro client-side
}

export interface Inscrito {
  inscricaoId: string;
  vereadorId: string;
  /** Fix round 2 (A1/N1): antes ausente aqui, o que forçava a hidratação a CONFIAR cegamente na ordem
   * do servidor e o `case "inscricao.registrada"` a reordenar só por `ordem` — que intercala fases (ver
   * `compararInscritos`). Com `fase` presente, a ordenação vira UMA função usada nos dois caminhos. */
  fase: string;
  ordem: number;
}

/** A ÚNICA ordenação da fila de inscritos — usada tanto por `lerInscritosTribuna` (hidratação HTTP) quanto
 * pelo `case "inscricao.registrada"` de `aplicarEvento` (SSE). Fix round 2 (A1/N1): o Fix round 1 corrigiu
 * só o caminho HTTP (parou de reordenar, confiando na ordem do servidor) e deixou o `case` do SSE ainda
 * ordenando por `ordem` sozinho — os dois caminhos discordavam, e a PRIMEIRA inscrição ao vivo depois da
 * hidratação reembaralhava a fila que acabara de chegar correta (a intercalação exata do defeito original,
 * publicada na transmissão). Replica `ORDER BY fase ASC, ordem ASC` de `listar-inscricoes`
 * (`db/tribuna.clj`): `fase` é coluna `text` (CHECK, não enum — migration 0032), então o `ASC` do Postgres
 * é ordem TEXTUAL simples, não a ordem semântica do fluxo da sessão — comparação de string aqui replica
 * fielmente esse comportamento (os 5 valores são ASCII minúsculo com `_`, sem acento; a mesma premissa que
 * já valia implicitamente quando o Fix round 1 confiou na ordem do servidor sem tocar nela). Definida UMA
 * vez, usada nos dois lugares — não redigitar este comparador em outro `case`/parser. */
function compararInscritos(a: Inscrito, b: Inscrito): number {
  if (a.fase !== b.fase) return a.fase < b.fase ? -1 : 1;
  return a.ordem - b.ordem;
}

export type VotoNominal = "sim" | "nao" | "abstencao";

/** O resumo MÍNIMO da matéria em votação (tipo/ano/sequencial/ementa) — o MESMO molde que
 * `ProposicaoResumoDetalheVotacao` (use-detalhe-votacao.ts) e que `ProposicaoResumoObjetoVotacaoOut`
 * (backend, wire/out/votacao.clj) já validam; redeclarado aqui (mão-tipado, mesmo precedente do resto
 * deste arquivo) para não criar um import cruzado só por um tipo estrutural. */
export type ProposicaoResumoPlacar = { tipo: string; ano: number; sequencial: number; ementa: string };

/** Placar da votação corrente (uma por vez no plenário). §22.6 SIGILO: na SECRETA só existe o CONTADOR
 * (votosSecretos) — JAMAIS voto por vereador; o agregado do encerramento é público mesmo na secreta. */
export interface PlacarVotacao {
  votacaoId: string;
  modalidade: string; // "nominal" | "secreta" (vazio se só vimos o encerramento, sem modalidade no payload)
  objetoTipo: string | null;
  /** `objeto-id` do payload de `votacao.aberta` (fatia "demo-tres-consertos" #2) — o elo que `useDetalheVotacao`
   * usa pra resolver O QUE está em votação (ementa/tipo/número da matéria) via GET /sessoes/:id/votacoes/:id.
   * `votacao.encerrada` NUNCA carrega objeto-id (EncerradaPayload não tem esse campo) — por isso, como
   * `objetoTipo`, só sobrevive por reconexão (`anterior?.objetoId`); reconectar vendo só o encerramento (sem
   * ter visto a abertura) deixa `null`, mesma honestidade de `objetoTipo`. */
  objetoId: string | null;
  /** O resumo da matéria (tipo/ano/sequencial/ementa), quando `objetoTipo` é `proposicao`/`redacao_final`
   * (carry telão, Daouda 12/09/2026 — fatia "demo-tres-consertos" #2b): `GET .../votacao-aberta` já devolve
   * `proposicao` pronta (a MESMA resolução da Fatia 2, `resolver-objeto-votacao` no backend) — este campo
   * só CONSOME o que a rota de recuperação já manda, sem uma segunda chamada a `/votacoes/:id`
   * (`useDetalheVotacao`, gate `papel-vereador`-only) que o telão não alcançaria de qualquer forma. Só
   * `hidratarVotacao` (a recuperação via HTTP) o PREENCHE — nenhum evento SSE carrega a ementa
   * (`AbertaPayload`/`EncerradaPayload` não têm esse campo, contrato imutável).
   *
   * Achado ao vivo (Daouda, 12/09/2026): o canal replaya TODOS os eventos da sessão desde `id: 1`, não só
   * os futuros — então um `votacao.aberta` da MESMA votação chega DEPOIS da hidratação ter preenchido
   * este campo, no fluxo normal do navegador (não é um caso raro de reconexão). Por isso `votacao.aberta`
   * e `votacao.encerrada` PRESERVAM `proposicao` quando o `votacao-id` do evento é o mesmo do placar
   * corrente (o evento não SABE que não há proposição; "não sei" não é "é nulo"). Só ZERA quando a
   * votação MUDOU (matéria nova, sem ementa ainda resolvida) — nunca vaza a proposição de uma votação
   * para outra. */
  proposicao: ProposicaoResumoPlacar | null;
  encerrada: boolean;
  votosNominais: Record<string, VotoNominal>; // só NOMINAL: vereadorId -> voto (mostra quem votou o quê)
  votosSecretos: number; // só SECRETA: contagem de votos registrados (anônimo)
  resultado: string | null; // "aprovada" | "rejeitada" (do encerramento)
  totais: { sim: number | null; nao: number | null; abstencao: number | null } | null; // do encerramento
  baseMembros: number | null; // do encerramento (denominador do quórum)
}

/** O QUÓRUM como o SERVIDOR o contou, num instante — copiado, nunca recalculado aqui.
 *
 * Todos os campos vêm de `ChamadaQuorumOut`/`QuorumSessaoOut` (`logic/contar-quorum`). O cliente não soma,
 * não subtrai e não deduz: `presentesTotal` já é o numerador pronto justamente porque somar
 * `presentesPlenario + presentesRemoto` aqui seria uma SEGUNDA aritmética do quórum, no ponto mais distante
 * possível da regra — e uma terceira categoria positiva no domínio a subcontaria em silêncio. */
export interface QuorumDaSessao {
  presentesTotal: number; // numerador (o servidor já somou)
  presentesPlenario: number;
  presentesRemoto: number;
  membrosDaCasa: number; // denominador — EXCLUI licenciados e linhas sem assento
  presencasForaDoRoster: number; // fail-loud: por que `presentesTotal` PODE passar de `membrosDaCasa`
  semRegistroDePresenca: boolean; // "ninguém registrou nada ainda" ≠ "a Casa faltou"
}

/** Tri-estado honesto da borda de quórum: a tela NUNCA deve afirmar "0 presentes" quando o que ela tem é
 * "não sei". `carregando` = fetch em voo (esqueleto); `indisponivel` = a borda falhou e nunca houve
 * snapshot (nenhum número); `ok` = há um snapshot do servidor (ainda que uma re-busca posterior falhe —
 * degradar, não zerar). */
export type QuorumStatus = "carregando" | "indisponivel" | "ok";

export interface EstadoPlenario {
  estado: string; // estado da sessão (agendada|aberta|suspensa|encerrada|nao_realizada|arquivada)
  /** vereador-ids vistos AO VIVO pelo SSE (conjunto; ordem de inserção).
   *
   * CAMPO COMPARTILHADO, e por isso intocável pela hidratação de quórum: `usePlenario` alimenta DUAS telas,
   * e `meu-voto-vista.ts` lê este conjunto de forma NOMINAL (`presentes.includes(meuVereadorId)`) para
   * decidir se o cockpit do vereador oferece o botão de votar. A Etapa 4b redefiniu-o como "delta posterior
   * ao snapshot" e podava dele quem tivesse evento anterior ao `instante` — o botão de votar sumia no
   * celular do vereador, com votação nominal aberta. A semântica aqui é, e continua sendo, "quem o SSE
   * mostrou presente desde que esta página abriu". O numerador do telão NÃO sai daqui (sai de `quorum`). */
  presentes: string[];
  /** vereadorId -> epoch ms do `ocorrido-em` do último evento APLICADO a `presentes` para aquele vereador.
   * Serve só à ORDENAÇÃO nominal (um frame reentregue fora de ordem no resume por Last-Event-ID não pode
   * ressuscitar um estado já superado) — nunca ao quórum. Comparação NUMÉRICA, jamais textual: ver `instanteMs`. */
  presencaEm: Record<string, number>;
  quorum: QuorumDaSessao | null; // o snapshot do servidor; null = ainda não chegou (ou nunca chegou)
  quorumStatus: QuorumStatus;
  /** pedido de RE-HIDRATAÇÃO: houve movimento de presença, então o número do servidor pode ter mudado. O
   * hook observa este sinal e re-busca (debounced — a rajada da chamada vira um punhado de requests);
   * `hidratarQuorum` o baixa. É o ÚNICO caminho pelo qual o numerador do telão se move. */
  precisaRehidratar: boolean;
  oradorAtual: OradorAtual | null;
  marcosCronometro: MarcoCronometro[]; // marcos da fala EM CURSO (zerados a cada fala.iniciada)
  ultimaFalaEncerrada: { falaId: string; tempoSegundos: number } | null;
  inscritos: Inscrito[]; // fila ordenada por `ordem`
  placar: PlacarVotacao | null; // votação corrente/última (null = nenhuma votação vista)
  /** O último item anunciado visto pelo SSE (docs/23 Fatia 4b); `null` = nenhum anúncio ao vivo. O estado
   * inicial vem da pauta (`em-apreciacao`) — quem junta os dois é `tv-vista/anuncioCorrente`. */
  anuncio: AnuncioItem | null;
  /** vereadorId -> identidade PÚBLICA (nome parlamentar, cargo na Mesa), de GET /sessoes/:id/composicao.
   * Existe porque o SSE carrega só o `orador-id`/`vereador-id` no evento: sem este mapa a tribuna não
   * tem como dizer QUEM está com a palavra, e o telão exibia o prefixo do UUID no lugar do nome.
   * `null` = ainda não chegou. Ausência de uma CHAVE não é erro: o orador pode legitimamente não ser
   * membro da Casa (fase `tribuna_livre_cidadao`; e `orador-id` não tem FK para vereador). */
  composicao: Map<string, IdentidadeParlamentar> | null;
  composicaoStatus: QuorumStatus;
  ultimoSeq: number; // maior seq visto — vira o Last-Event-ID no resume
  /** Contador monotônico dos 3 eventos de `TIPOS_EVENTO_FALA` já aplicados. Existe só para a
   * PRECEDÊNCIA de `oradorAtual`/`marcosCronometro` em `hidratarTribuna`: o hook captura este valor
   * antes de disparar `GET .../tribuna` e o repassa; se o contador tiver avançado quando a resposta
   * chega, um evento de FALA ao vivo já é mais novo que o snapshot em voo, e a hidratação descarta em
   * vez de ressuscitar quem já desceu da tribuna. Fix round 1 (I2): separado de `inscricaoEventoSeq`
   * para que um `inscricao.registrada` alheio não jogue fora o orador junto — cada campo do snapshot
   * tem seu próprio relógio. */
  falaEventoSeq: number;
  /** Contador monotônico dos 2 eventos de `TIPOS_EVENTO_INSCRICAO` já aplicados — a mesma PRECEDÊNCIA
   * de `falaEventoSeq`, mas só para o campo `inscritos`. Ver a docstring de `falaEventoSeq`. */
  inscricaoEventoSeq: number;
  /** Contador monotônico dos 3 eventos de `TIPOS_EVENTO_VOTACAO` já aplicados — a mesma PRECEDÊNCIA de
   * `falaEventoSeq`/`inscricaoEventoSeq`, agora para `placar` (fatia "demo-tres-consertos" #2b). O hook
   * captura este valor antes de disparar `GET .../votacao-aberta`; se tiver avançado quando a resposta
   * chega, um evento de votação ao vivo já é mais novo que o snapshot em voo, e `hidratarVotacao`
   * descarta em vez de sobrescrever um placar que o próprio SSE já atualizou/encerrou nesse meio-tempo. */
  votacaoEventoSeq: number;
  /** `true` desde o primeiro `tempo-real.lacuna` visto nesta conexão (frente 'truncamento-familia',
   * sítio d): o backplane encontrou uma entrada corrompida no replay e não tem como dizer QUAL campo
   * ela afetava. STICKY de propósito — nunca volta a `false` sozinho: para quórum/tribuna o próprio
   * sinal já pede re-hidratação (`precisaRehidratar`, que os corrige em segundos), mas o placar de
   * votação não tem nenhum caminho de re-busca (é só o agregado de eventos SSE), então o aviso
   * permanece visível pelo resto da sessão em vez de fingir que o risco passou. (Fatia "demo-tres-
   * consertos" #2b: o placar ganhou UM caminho de re-busca — `hidratarVotacao`, para o cliente frio que
   * nunca viu `votacao.aberta` — mas ele não desarma este aviso; um self-heal completo do aviso a partir
   * da recuperação é decisão maior, não tomada aqui.) */
  avisoLacuna: boolean;
}

/** A identidade PÚBLICA de um parlamentar — o subconjunto que `GET /sessoes/:id/composicao` serve, que é
 * por sua vez o subconjunto que a rota pública de perfil de vereador já serve sem autenticação nenhuma.
 * Nunca nome civil, nunca estado de presença: esses são da chamada nominal, atrás do papel 'secretario'. */
export type IdentidadeParlamentar = { nomeParlamentar: string | null; cargoMesa: string | null; partido?: string | null };

/** O último item ANUNCIADO pela Mesa visto AO VIVO (docs/23 Fatia 4b). `votacaoNoAnuncio` é o `votacaoId` do
 * placar no momento do anúncio: a matéria deixa de estar "em apreciação" quando uma votação DELA encerra
 * DEPOIS do anúncio — e só o id distingue "a votação que encerrou antes de a Mesa voltar à matéria" (segundo
 * turno, matéria adiada) de "a votação que encerrou a apreciação", já que o placar não carrega horário. */
export interface AnuncioItem {
  itemId: string;
  anunciadoEm: string;
  proposicaoId: string | null;
  votacaoNoAnuncio: string | null;
}

export function estadoInicial(sessao: SessaoOut): EstadoPlenario {
  return {
    estado: sessao.estado,
    presentes: [],
    presencaEm: {},
    quorum: null,
    quorumStatus: "carregando",
    precisaRehidratar: false,
    oradorAtual: null,
    marcosCronometro: [],
    ultimaFalaEncerrada: null,
    inscritos: [],
    placar: null,
    anuncio: null,
    composicao: null,
    composicaoStatus: "carregando",
    ultimoSeq: 0,
    falaEventoSeq: 0,
    votacaoEventoSeq: 0,
    inscricaoEventoSeq: 0,
    avisoLacuna: false,
  };
}

/** Chegou a composição. Constrói o índice por `vereadorId` de uma vez — a tribuna resolve nome por
 * evento, e varrer uma lista a cada frame seria trabalho por tick para um dado que não muda na sessão. */
export function hidratarComposicao(estado: EstadoPlenario, cru: ComposicaoSessaoOut): EstadoPlenario {
  const membros = Array.isArray(cru?.membros) ? cru.membros : null;
  if (membros === null) {
    // corpo de forma inesperada: mesma postura TOTAL de `hidratarQuorum` — degrada, nunca lança (este
    // updater pode ser avaliado na fase de RENDER do React).
    return { ...estado, composicaoStatus: estado.composicao ? "ok" : "indisponivel" };
  }
  const indice = new Map<string, IdentidadeParlamentar>();
  for (const m of membros) {
    if (m && typeof m.vereadorId === "string") {
      indice.set(m.vereadorId, { nomeParlamentar: m.nomeParlamentar ?? null, cargoMesa: m.cargoMesa ?? null, partido: m.partido ?? null });
    }
  }
  return { ...estado, composicao: indice, composicaoStatus: "ok" };
}

/** A borda da composição falhou (rede/403/500/parse). DEGRADA, não zera: um índice já obtido continua
 * valendo (a composição de uma sessão não muda no meio dela). Sem índice, a tribuna cai no rótulo
 * neutro — nunca no UUID, que era justamente o defeito. */
export function falharComposicao(estado: EstadoPlenario): EstadoPlenario {
  return { ...estado, composicaoStatus: estado.composicao ? "ok" : "indisponivel" };
}

/** A identidade de quem o evento só identificou por id, ou `null` quando não há nome a exibir — seja
 * porque a composição ainda não chegou, seja porque o id não é de um membro da Casa (tribuna livre do
 * cidadão, presença sem assento). Os dois casos colapsam de propósito: a tela não deve AFIRMAR
 * "não identificado" para quem é legitimamente um cidadão na tribuna. Quem chama decide o rótulo neutro. */
export function identidadeDe(estado: EstadoPlenario, vereadorId: string | null | undefined): IdentidadeParlamentar | null {
  if (!vereadorId || !estado.composicao) return null;
  const id = estado.composicao.get(vereadorId);
  if (!id || !id.nomeParlamentar) return null;
  return id;
}

/** Instante ISO-8601 -> epoch ms, ou NaN se não for um instante.
 *
 * NUNCA comparar ISO-8601 como TEXTO. A largura da fração NÃO é fixa: `Instant.toString()`
 * (= `DateTimeFormatter.ISO_INSTANT`, dos dois lados do fio no backend) emite 0, 3, 6 ou 9 dígitos conforme
 * o valor, e o navegador (`toISOString`) sempre emite 3. Em ASCII '.'(0x2E) < 'Z'(0x5A) < dígito nenhum:
 * '...07.412Z' comparado com '...07.412683Z' resolve 'Z' > '4' e conclui que o instante MENOR é o MAIOR.
 * Dentro do mesmo segundo a comparação textual simplesmente inverte a ordem temporal. */
function instanteMs(iso: unknown): number {
  return typeof iso === "string" ? Date.parse(iso) : NaN;
}

const finito = (n: unknown): n is number => typeof n === "number" && Number.isFinite(n);

/** Valida o snapshot CRU da borda (`camelizarChaves(await resp.json())`, um cast puro sem garantia nenhuma)
 * e o converte no formato de estado. Devolve null se qualquer campo numérico faltar/derivar.
 *
 * Existe porque o cast mentia: um campo ausente virava `undefined` (não `null`), atravessava a guarda
 * `!== null` da página, e `undefined + undefined = NaN` se propagava até o telão imprimir "NaN de
 * undefined" — sem exceção nenhuma, porque aritmética com undefined não lança. E `quorum` ausente LANÇAVA,
 * de dentro de um updater de `setEstado`, que o React pode avaliar na fase de RENDER (derrubando a árvore
 * em vez de cair no `.catch` do hook). */
function lerSnapshot(cru: QuorumSessaoOut): QuorumDaSessao | null {
  const q = cru?.quorum as Partial<QuorumSessaoOut["quorum"]> | undefined;
  if (!q) return null;
  if (!finito(q.presentesTotal) || !finito(q.membrosDaCasa)) return null;
  if (!finito(q.presentesPlenario) || !finito(q.presentesRemoto) || !finito(q.presencasForaDoRoster)) return null;
  return {
    presentesTotal: q.presentesTotal,
    presentesPlenario: q.presentesPlenario,
    presentesRemoto: q.presentesRemoto,
    membrosDaCasa: q.membrosDaCasa,
    presencasForaDoRoster: q.presencasForaDoRoster,
    semRegistroDePresenca: cru.semRegistroDePresenca === true,
  };
}

/** O número que o telão exibe, como um TRI-ESTADO explícito — nunca um `number` que a tela tenha de
 * interpretar. `indisponivel` não carrega número de propósito: afirmar "0 presentes" quando o que se tem é
 * "não sei" é a mentira mais cara desta tela (plenário cheio, imprensa na galeria, hemiciclo vazio). */
export type VistaQuorum =
  | { status: "carregando" }
  | { status: "indisponivel" }
  | { status: "ok"; presentes: number; membrosDaCasa: number; foraDoRoster: number; semRegistro: boolean };

export function vistaDoQuorum(estado: EstadoPlenario): VistaQuorum {
  if (estado.quorum === null) return { status: estado.quorumStatus === "indisponivel" ? "indisponivel" : "carregando" };
  const q = estado.quorum;
  return {
    status: "ok",
    presentes: q.presentesTotal,
    membrosDaCasa: q.membrosDaCasa,
    foraDoRoster: q.presencasForaDoRoster,
    semRegistro: q.semRegistroDePresenca,
  };
}

/** Atalho para os testes e para a página: o numerador exibido, ou null quando não se sabe. */
export function numeroDoTelao(estado: EstadoPlenario): number | null {
  return estado.quorum?.presentesTotal ?? null;
}

/** Hidrata o quórum a partir do snapshot de `GET /sessoes/:id/quorum`. PURA e TOTAL: nunca lança, nunca
 * produz NaN, e um snapshot de forma inválida devolve o estado praticamente inalterado (só o status cai
 * para `indisponivel` se ainda não havia snapshot bom).
 *
 * O QUE ELA **NÃO** FAZ, E É O PONTO: não mexe em `presentes` e não funde nada. O numerador do telão é o do
 * SERVIDOR, e só ele. A Etapa 4b tentou o contrário — somar o delta do SSE sobre uma base OPACA (a rota é
 * magra, não há ids) — e a revisão adversarial mostrou que essa fusão é indefensável sem identidade:
 *   - `mudanca_modalidade` move a pessoa de coluna e o servidor mantém o total; o cliente somava +1;
 *   - uma `saida` re-entregue (at-least-once, com `seq` distinta) decrementava duas vezes um contador cego;
 *   - um registro RETROATIVO (o domínio o permite: `ocorrido-em` vem do cliente e só o futuro é recusado)
 *     era descartado para sempre pelo corte por instante;
 *   - e o próprio corte comparava ISO-8601 como texto, com precisão fracionária variável (ver `instanteMs`).
 * Sem fusão, os quatro somem por construção: só existe UMA aritmética de quórum, a do servidor, e o telão a
 * copia. O preço é uma re-busca (barata em payload, debounced no hook); o benefício é que o número do telão
 * e o número da policy nunca podem divergir — que é a regra que as Etapas 1 e 2 já haviam cravado no
 * servidor e que a 4b reabriu no cliente. */
export function hidratarQuorum(estado: EstadoPlenario, cru: QuorumSessaoOut): EstadoPlenario {
  const snapshot = lerSnapshot(cru);
  if (snapshot === null) {
    return { ...estado, quorumStatus: estado.quorum ? "ok" : "indisponivel" };
  }
  return { ...estado, quorum: snapshot, quorumStatus: "ok", precisaRehidratar: false };
}

/** A borda de quórum falhou (rede/403/500/parse). DEGRADA, não zera: se já havia um snapshot bom, ele
 * continua na tela (um número velho de segundos é melhor que nenhum); se nunca houve, o tri-estado vai para
 * `indisponivel` e a página deixa de imprimir número. */
export function falharQuorum(estado: EstadoPlenario): EstadoPlenario {
  return { ...estado, quorumStatus: estado.quorum ? "ok" : "indisponivel" };
}

/** Lê `oradorAtual` do snapshot cru de `GET /sessoes/:id/tribuna`. `null` explícito é um estado válido
 * ("ninguém com a palavra"); `undefined` sinaliza forma inesperada — quem chama mantém o que já havia
 * (mesma postura TOTAL do resto deste arquivo: nunca lança, nunca inventa). */
function lerOradorAtualTribuna(o: unknown): OradorAtual | null | undefined {
  if (o === null) return null;
  if (!o || typeof o !== "object") return undefined;
  const x = o as Record<string, unknown>;
  if (
    typeof x.falaId !== "string" ||
    typeof x.oradorId !== "string" ||
    typeof x.tipoFala !== "string" ||
    typeof x.fase !== "string" ||
    typeof x.iniciouEm !== "string"
  ) {
    return undefined;
  }
  return { falaId: x.falaId, oradorId: x.oradorId, tipoFala: x.tipoFala, fase: x.fase, iniciouEm: x.iniciouEm };
}

/** Lê `marcosCronometro`. `undefined` (forma inesperada) preserva os marcos já vividos pelo SSE; uma
 * lista presente mas com itens tortos apenas PULA o item torto (não descarta a lista inteira por causa
 * de um vizinho malformado). */
function lerMarcosTribuna(m: unknown): MarcoCronometro[] | undefined {
  if (!Array.isArray(m)) return undefined;
  const marcos: MarcoCronometro[] = [];
  for (const item of m) {
    if (!item || typeof item !== "object") continue;
    const x = item as Record<string, unknown>;
    if (typeof x.tipo !== "string" || typeof x.ocorridoEm !== "string") continue;
    marcos.push({
      tipo: x.tipo,
      ocorridoEm: x.ocorridoEm,
      segundosAdicionais: finito(x.segundosAdicionais) ? x.segundosAdicionais : null,
    });
  }
  return marcos;
}

/** Lê `inscritos`. Fix round 1 (I1) parou de reordenar aqui (confiava que o servidor já manda em
 * `(fase, ordem)`); Fix round 2 (A1/N1) volta a ordenar explicitamente, agora que `Inscrito` carrega
 * `fase` — com `compararInscritos`, a MESMA função que o `case "inscricao.registrada"` usa do lado do
 * SSE. Ordenar aqui explicitamente (em vez de só confiar na ordem HTTP) é defesa em profundidade: os
 * dois caminhos de escrita deste campo agora produzem o resultado do MESMO critério, nunca dois
 * critérios que podem discordar. Mesma tolerância a item torto que `lerMarcosTribuna`: um item pulado
 * não descarta os vizinhos válidos, só sai da ordenação final. */
function lerInscritosTribuna(lst: unknown): Inscrito[] | undefined {
  if (!Array.isArray(lst)) return undefined;
  const inscritos: Inscrito[] = [];
  for (const item of lst) {
    if (!item || typeof item !== "object") continue;
    const x = item as Record<string, unknown>;
    if (typeof x.inscricaoId !== "string" || typeof x.vereadorId !== "string" || typeof x.fase !== "string" || !finito(x.ordem)) continue;
    inscritos.push({ inscricaoId: x.inscricaoId, vereadorId: x.vereadorId, fase: x.fase, ordem: x.ordem });
  }
  return inscritos.sort(compararInscritos);
}

/** O contador de precedência que `hidratarTribuna` compara contra cada CAMPO do snapshot — ver a
 * docstring de `falaEventoSeq`/`inscricaoEventoSeq` em `EstadoPlenario`. */
export interface TribunaEventoSeqNoDisparo {
  fala: number;
  inscricao: number;
}

/** Hidrata a tribuna a partir do snapshot de `GET /sessoes/:id/tribuna`. PURA e TOTAL: nunca lança, e
 * um corpo de forma inesperada devolve o estado praticamente inalterado (este updater pode ser avaliado
 * na fase de RENDER do React, como `hidratarQuorum`/`hidratarComposicao`).
 *
 * `seqNoDisparo` são os valores de `estado.falaEventoSeq`/`estado.inscricaoEventoSeq` que o HOOK
 * capturou no instante em que disparou o request — antes de saber se o SSE traria algo novo enquanto a
 * resposta estava em voo. Fix round 1 (I2): a PRECEDÊNCIA é avaliada **por campo**, não mais em bloco.
 * Antes, um único contador cobria os 5 tipos e um `inscricao.registrada` alheio chegado em voo jogava
 * fora o snapshot INTEIRO — inclusive `oradorAtual`, apagando quem está com a palavra por até 30s (o
 * defeito exato que esta frente existe para matar). Agora `oradorAtual`/`marcosCronometro` só são
 * descartados se um evento de FALA chegou no meio, e `inscritos` só se um evento de INSCRIÇÃO chegou —
 * cada metade do snapshot é aplicada ou descartada independente da outra. O RULING em si não mudou: a
 * alternativa óbvia ("servidor sempre vence", o molde de `hidratarQuorum`) ressuscitaria em produção um
 * orador que a Mesa já havia encerrado, na transmissão pública, só porque a resposta HTTP chegou
 * atrasada. Como `rehidratar()` roda periodicamente (e a cada reconexão) e o hook pede retentativa
 * quando detecta um descarte, um campo descartado aqui não trava a tela por 30s: o relógio de 500ms do
 * hook observa o pedido e dispara assim que o piso `REBUSCA_MIN_MS` permitir — até ~3s, não 500ms (a
 * docstring já teve essa imprecisão registrada como achado; não redigitar "500ms" sem o piso ao lado). */
export function hidratarTribuna(
  estado: EstadoPlenario,
  cru: TribunaOut,
  seqNoDisparo: TribunaEventoSeqNoDisparo,
): EstadoPlenario {
  if (!cru || typeof cru !== "object") return estado;

  const c = cru as Partial<TribunaOut>;
  const falaEmDia = estado.falaEventoSeq === seqNoDisparo.fala;
  const inscricaoEmDia = estado.inscricaoEventoSeq === seqNoDisparo.inscricao;

  const oradorAtual = falaEmDia ? lerOradorAtualTribuna(c.oradorAtual) : undefined;
  const marcosCronometro = falaEmDia ? lerMarcosTribuna(c.marcosCronometro) : undefined;
  const inscritos = inscricaoEmDia ? lerInscritosTribuna(c.inscritos) : undefined;

  return {
    ...estado,
    oradorAtual: oradorAtual === undefined ? estado.oradorAtual : oradorAtual,
    marcosCronometro: marcosCronometro === undefined ? estado.marcosCronometro : marcosCronometro,
    inscritos: inscritos === undefined ? estado.inscritos : inscritos,
  };
}

/** A borda da tribuna falhou (rede/403/500/parse). Ao contrário de quórum/composição, a tribuna não tem
 * um status próprio para degradar: `oradorAtual`/`marcosCronometro`/`inscritos` JÁ são os campos ao vivo
 * do SSE, com defaults sãos (`null`/`[]`/`[]`) desde `estadoInicial`. Uma falha da borda simplesmente NÃO
 * muda nada — o SSE segue sendo a única fonte até o próximo `rehidratar()` bem-sucedido. */
export function falharTribuna(estado: EstadoPlenario): EstadoPlenario {
  return estado;
}

/** O snapshot de GET /sessoes/:id/votacao-aberta (fatia "demo-tres-consertos" #2b — RECUPERAÇÃO de
 * estado: o canal Valkey tem retenção MINID de ~5min, e um cliente que conecta depois disso nunca vê
 * `votacao.aberta`, mesmo com uma votação de verdade aberta no servidor). Mão-tipado: `legislativo` ainda
 * não participa do codegen Malli->TS nesta vertical (mesmo precedente de use-detalhe-votacao.ts).
 * `votos`/`votosRegistrados` são MUTUAMENTE EXCLUSIVOS — o backend crava isso num `:multi` por
 * modalidade (sigilo §22.6): `votos` só quando `modalidade === "nominal"`. */
export type VotacaoAbertaSnapshot = {
  votacaoId: string;
  modalidade: string;
  objetoTipo: string;
  objetoId: string;
  /** `proposicao` (carry telão, Daouda 12/09/2026): o backend (`VotacaoAbertaOut`, todo ramo) já manda este
   * campo — `?` aqui é só a mesma tolerância mão-tipada do resto do tipo (um corpo antigo/torto não deve
   * quebrar o parse), não uma afirmação de que o servidor às vezes o omite. */
  proposicao?: ProposicaoResumoPlacar | null;
  votos?: { vereadorId: string; voto: VotoNominal }[];
  votosRegistrados?: number;
};

/** `cru.proposicao` -> `ProposicaoResumoPlacar | null`, fail-closed (mesmo racional de
 * `comoProposicaoResumo` em use-detalhe-votacao.ts): um objeto com QUALQUER campo de forma errada vira
 * `null` — nunca um título inventado/truncado no telão. */
function comoProposicaoResumoPlacar(p: unknown): ProposicaoResumoPlacar | null {
  if (p === null || typeof p !== "object") return null;
  const { tipo, ano, sequencial, ementa } = p as Record<string, unknown>;
  if (typeof tipo !== "string" || typeof ano !== "number" || typeof sequencial !== "number" || typeof ementa !== "string") {
    return null;
  }
  return { tipo, ano, sequencial, ementa };
}

/** Hidrata `placar` a partir do snapshot de recuperação. PURA e TOTAL: nunca lança, um corpo de forma
 * inesperada devolve o estado inalterado.
 *
 * `seqNoDisparo` é o `estado.votacaoEventoSeq` que o HOOK capturou no instante em que disparou o
 * request — mesmo padrão de `hidratarTribuna`/`TribunaEventoSeqNoDisparo` (fix I2b): se um evento de
 * VOTAÇÃO ao vivo chegar enquanto esta resposta está em voo, a hidratação DESCARTA o snapshot inteiro em
 * vez de sobrescrever um placar que o próprio SSE já construiu/encerrou nesse meio-tempo — a alternativa
 * ("servidor sempre vence") ressuscitaria em produção uma votação que a Mesa já havia encerrado, só
 * porque a resposta HTTP chegou atrasada.
 *
 * `cru === null` é o estado LEGÍTIMO "nenhuma votação aberta" (o backend responde 404 pra isso, nunca
 * erro) — nada a hidratar; `placar` já nasce `null` em `estadoInicial` e só sai daí por um evento ao vivo
 * ou por este mesmo caminho. */
export function hidratarVotacao(
  estado: EstadoPlenario,
  cru: VotacaoAbertaSnapshot | null,
  seqNoDisparo: number,
): EstadoPlenario {
  if (estado.votacaoEventoSeq !== seqNoDisparo) return estado;
  if (cru === null) return estado;
  if (typeof cru !== "object" || typeof cru.votacaoId !== "string" || typeof cru.modalidade !== "string") {
    return estado;
  }

  const votosNominais: Record<string, VotoNominal> = {};
  if (Array.isArray(cru.votos)) {
    for (const v of cru.votos) {
      if (v && typeof v.vereadorId === "string" && typeof v.voto === "string") {
        votosNominais[v.vereadorId] = v.voto as VotoNominal;
      }
    }
  }

  return {
    ...estado,
    placar: {
      votacaoId: cru.votacaoId,
      modalidade: cru.modalidade,
      objetoTipo: typeof cru.objetoTipo === "string" ? cru.objetoTipo : null,
      objetoId: typeof cru.objetoId === "string" ? cru.objetoId : null,
      proposicao: comoProposicaoResumoPlacar(cru.proposicao),
      encerrada: false, // esta rota só devolve votação com estado 'aberta' no servidor
      votosNominais,
      votosSecretos: typeof cru.votosRegistrados === "number" ? cru.votosRegistrados : 0,
      resultado: null,
      totais: null,
      baseMembros: null,
    },
  };
}

/** A borda de recuperação da votação falhou (rede/403/500/parse) — DISTINTO de `cru === null` acima
 * (aquele é "não há votação", este é "não sei se há"). Mesma postura de `falharTribuna`: não muda nada,
 * o SSE segue sendo a única fonte até a próxima tentativa. */
export function falharVotacao(estado: EstadoPlenario): EstadoPlenario {
  return estado;
}

export function aplicarEvento(estado: EstadoPlenario, evento: EventoPlenario): EstadoPlenario {
  // Os contadores de precedência avançam aqui, na construção de `base`, e não em cada `case`: um `case`
  // que devolve `base` cedo (ex.: `fala.cronometro` para uma fala que não é a corrente) precisa do avanço
  // do MESMO jeito — o evento chegou e o snapshot em voo já é mais velho, ainda que o reducer não tenha
  // mudado nada visível a partir dele. `votacaoEventoSeq` (fatia "demo-tres-consertos" #2b) segue a MESMA
  // disciplina de `falaEventoSeq`/`inscricaoEventoSeq`.
  const base = {
    ...estado,
    ultimoSeq: Math.max(estado.ultimoSeq, evento.seq),
    falaEventoSeq: estado.falaEventoSeq + (TIPOS_EVENTO_FALA.has(evento.tipo) ? 1 : 0),
    inscricaoEventoSeq: estado.inscricaoEventoSeq + (TIPOS_EVENTO_INSCRICAO.has(evento.tipo) ? 1 : 0),
    votacaoEventoSeq: estado.votacaoEventoSeq + (TIPOS_EVENTO_VOTACAO.has(evento.tipo) ? 1 : 0),
  };

  switch (evento.tipo) {
    case "sessao.transicionou":
      return { ...base, estado: evento.dados.para };

    case "presenca.registrada": {
      const v = evento.dados["vereador-id"];
      const emMs = instanteMs(evento.dados["ocorrido-em"]);
      const aplicadoEm = base.presencaEm[v];
      // O quórum EXIBIDO nunca se move aqui — quem conta é o SERVIDOR. Todo movimento de presença (inclusive
      // um retroativo, que o domínio permite de propósito) apenas PEDE uma re-busca; o hook coalesce a
      // rajada da chamada num punhado de requests. Nenhum evento é descartado em silêncio, que era o defeito.
      const pedeRebusca = { ...base, precisaRehidratar: true };

      // Guarda de ORDEM (numérica, nunca textual): um frame reentregue fora de ordem no resume por
      // Last-Event-ID não pode ressuscitar um estado já superado por um evento MAIS NOVO do mesmo vereador.
      // `<` e não `<=`: `Date.parse` trunca em milissegundos, então dois instantes que só diferem na fração
      // sub-ms (o servidor emite 6 casas, o navegador 3) EMPATAM aqui — e no empate quem decide é a ordem de
      // CHEGADA no canal, que é a ordenação do próprio servidor. É exatamente o empate em que a comparação
      // de texto invertia o tempo ('...412Z' > '...412683Z' porque 'Z' > '4').
      if (aplicadoEm !== undefined && Number.isFinite(emMs) && emMs < aplicadoEm) return pedeRebusca;

      // O CONJUNTO nominal do SSE — idempotente por construção sob entrega at-least-once (add/remove de
      // conjunto), e a única coisa que o cockpit do vereador lê. Nenhum contador, nenhuma subtração.
      const presentes = PRESENCA_POSITIVA.has(evento.dados.tipo)
        ? base.presentes.includes(v) ? base.presentes : [...base.presentes, v]
        : base.presentes.filter((x) => x !== v);
      const presencaEm = Number.isFinite(emMs) ? { ...base.presencaEm, [v]: emMs } : base.presencaEm;
      return { ...pedeRebusca, presentes, presencaEm };
    }

    case "fala.iniciada":
      return {
        ...base,
        oradorAtual: {
          falaId: evento.dados["fala-id"],
          oradorId: evento.dados["orador-id"],
          tipoFala: evento.dados["tipo-fala"],
          fase: evento.dados.fase,
          iniciouEm: evento.dados["iniciou-em"],
        },
        marcosCronometro: [],
      };

    case "fala.cronometro":
      // só acumula marcos da fala em curso (um marco tardio de fala antiga é ignorado)
      if (!base.oradorAtual || base.oradorAtual.falaId !== evento.dados["fala-id"]) return base;
      return {
        ...base,
        marcosCronometro: [
          ...base.marcosCronometro,
          { tipo: evento.dados.tipo, ocorridoEm: evento.dados["ocorrido-em"], segundosAdicionais: evento.dados["segundos-adicionais"] },
        ],
      };

    case "fala.encerrada":
      return {
        ...base,
        oradorAtual: null,
        marcosCronometro: [],
        ultimaFalaEncerrada: { falaId: evento.dados["fala-id"], tempoSegundos: evento.dados["tempo-segundos"] },
      };

    case "inscricao.registrada": {
      const id = evento.dados["inscricao-id"];
      if (base.inscritos.some((i) => i.inscricaoId === id)) return base; // idempotente (at-least-once)
      // Fix round 2 (A1/N1): `compararInscritos` — a MESMA função que `lerInscritosTribuna` usa do
      // lado HTTP. Antes este `case` ordenava só por `ordem` (a mesma regressão que o Fix round 1
      // havia corrigido do outro lado): com fila multi-fase já hidratada corretamente, a chegada de
      // UM evento aqui reembaralhava tudo por `ordem` sozinha, intercalando as fases na tela pública.
      const inscritos = [
        ...base.inscritos,
        { inscricaoId: id, vereadorId: evento.dados["vereador-id"], fase: evento.dados.fase, ordem: evento.dados.ordem },
      ].sort(compararInscritos);
      return { ...base, inscritos };
    }

    case "inscricao.desistida":
      return { ...base, inscritos: base.inscritos.filter((i) => i.inscricaoId !== evento.dados["inscricao-id"]) };

    case "votacao.aberta": {
      // uma votação por vez no plenário: a abertura SUBSTITUI o placar anterior (zera as contagens).
      const d = evento.dados;
      // Achado ao vivo (Daouda, verificação em browser, 12/09/2026): o canal replaya TODOS os eventos da
      // sessão desde `id: 1` — não só os que chegam depois da conexão abrir. A SEQUÊNCIA REAL do
      // navegador é: `hidratarVotacao` (comVotacao) preenche `proposicao` a partir do snapshot HTTP, e
      // LOGO DEPOIS o replay do SSE entrega o `votacao.aberta` da MESMA votação (o evento que a abriu de
      // verdade, só que reproduzido) — e este `case`, ao reconstruir o placar do zero, apagava a ementa
      // que acabara de chegar. Não é "servidor sempre vence": o evento não SABE que não há proposição
      // (`AbertaPayload` não carrega esse campo — nunca carregou, contrato imutável), e "não sei" não é o
      // mesmo que "é nulo". Por isso, quando é a MESMA votação (mesmo `votacao-id`), preserva o que já
      // foi hidratado — mesmo padrão que `votacao.encerrada` já aplica a `objetoTipo`/`objetoId`/
      // `proposicao` (`anterior?.proposicao ?? null`, abaixo). Votação DIFERENTE (troca de matéria) ZERA
      // mesmo — a proposição da votação anterior não pode vazar para a nova (mostraria a matéria errada
      // sobre um placar real, pior que o rótulo honesto do tipo).
      const mesmaVotacao = base.placar?.votacaoId === d["votacao-id"];
      return {
        ...base,
        placar: {
          votacaoId: d["votacao-id"],
          modalidade: d.modalidade,
          objetoTipo: d["objeto-tipo"],
          objetoId: d["objeto-id"] ?? null,
          proposicao: mesmaVotacao ? base.placar!.proposicao : null,
          encerrada: false,
          votosNominais: {},
          votosSecretos: 0,
          resultado: null,
          totais: null,
          baseMembros: null,
        },
      };
    }

    case "voto.registrado": {
      const d = evento.dados;
      // só conta p/ a votação CORRENTE (votos de outra votação / fora de ordem são ignorados).
      if (!base.placar || base.placar.votacaoId !== d["votacao-id"]) return base;
      // §22.6 SIGILO (fail-closed): a modalidade que vale é a da ABERTURA (base.placar.modalidade), não a do
      // evento individual — que poderia chegar adulterado. Só uma votação aberta NOMINAL grava voto por
      // vereador; qualquer outra (secreta, ou desconhecida na reconexão) é tick anônimo, e a identidade que
      // por acaso tenha vindo no fio é DESCARTADA (nunca entra em votosNominais).
      if (base.placar.modalidade !== "nominal") {
        return { ...base, placar: { ...base.placar, votosSecretos: base.placar.votosSecretos + 1 } };
      }
      if (!("vereador-id" in d)) return base; // nominal sem identidade: nada a registrar (descarta)
      // nominal: voto por vereador (idempotente por chave; re-voto sobrescreve).
      return {
        ...base,
        placar: { ...base.placar, votosNominais: { ...base.placar.votosNominais, [d["vereador-id"]]: d.voto as VotoNominal } },
      };
    }

    case "votacao.encerrada": {
      const d = evento.dados;
      const mesma = base.placar !== null && base.placar.votacaoId === d["votacao-id"];
      // mesma votação: preserva o que se acumulou; senão (reconexão sem ter visto a abertura) constrói do agregado.
      const anterior = mesma ? base.placar! : null;
      return {
        ...base,
        placar: {
          votacaoId: d["votacao-id"],
          modalidade: anterior?.modalidade ?? d.modalidade ?? "",
          objetoTipo: anterior?.objetoTipo ?? null,
          objetoId: anterior?.objetoId ?? null,
          proposicao: anterior?.proposicao ?? null,
          encerrada: true,
          votosNominais: anterior?.votosNominais ?? {},
          votosSecretos: anterior?.votosSecretos ?? 0,
          resultado: d.resultado,
          totais: { sim: d["total-sim"] ?? null, nao: d["total-nao"] ?? null, abstencao: d["total-abstencao"] ?? null },
          baseMembros: d["base-membros"] ?? null,
        },
      };
    }

    case "pauta.item-anunciado": {
      const d = evento.dados;
      return {
        ...base,
        anuncio: {
          itemId: d["item-id"],
          anunciadoEm: d["anunciado-em"],
          proposicaoId: d["proposicao-id"] ?? null,
          votacaoNoAnuncio: base.placar?.votacaoId ?? null,
        },
      };
    }

    case "tempo-real.lacuna":
      // Sinal SINTÉTICO (frente 'truncamento-familia', sítio d): o backplane não sabe qual dado
      // corrompeu, então trata como se TUDO pudesse ter sido afetado — `precisaRehidratar` já é o
      // ÚNICO caminho de auto-cura para quórum/tribuna (ver a docstring de `avisoLacuna`); o placar não
      // tem caminho de re-busca nenhum, e por isso o aviso fica STICKY em vez de tentar "corrigir" algo
      // que não há como buscar de novo.
      return { ...base, avisoLacuna: true, precisaRehidratar: true };

    default:
      return base;
  }
}
