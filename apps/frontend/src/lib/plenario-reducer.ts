// Reducer PURO do painel ao vivo: dobra os 7 eventos do canal plenário no estado de view.
// É o núcleo lógico do HERO (onde mora bug) — testado em plenario-reducer.test.ts. Sem IO: o hook
// (use-plenario) faz o EventSource/fetch e delega a este reducer. O cronômetro NÃO vive aqui: o servidor
// emite só os MARCOS (iniciada/pausada/retomada/tempo_adicional); o display por segundo é recomputado na UI
// a partir de `iniciouEm` + marcos (decisão de §22.6 eixo G — ticks por segundo são descartados no fio).

import type { EventoPlenario, SessaoOut } from "./contrato";
import type { ComposicaoSessaoOut, QuorumSessaoOut } from "./contrato-sessoes.gen";

/** Tipos de evento de presença que marcam PRESENTE (logic/tipos-presenca-positiva); "saida" remove. */
const PRESENCA_POSITIVA = new Set(["entrada", "retorno", "mudanca_modalidade"]);

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
  ordem: number;
}

export type VotoNominal = "sim" | "nao" | "abstencao";

/** Placar da votação corrente (uma por vez no plenário). §22.6 SIGILO: na SECRETA só existe o CONTADOR
 * (votosSecretos) — JAMAIS voto por vereador; o agregado do encerramento é público mesmo na secreta. */
export interface PlacarVotacao {
  votacaoId: string;
  modalidade: string; // "nominal" | "secreta" (vazio se só vimos o encerramento, sem modalidade no payload)
  objetoTipo: string | null;
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
  /** vereadorId -> identidade PÚBLICA (nome parlamentar, cargo na Mesa), de GET /sessoes/:id/composicao.
   * Existe porque o SSE carrega só o `orador-id`/`vereador-id` no evento: sem este mapa a tribuna não
   * tem como dizer QUEM está com a palavra, e o telão exibia o prefixo do UUID no lugar do nome.
   * `null` = ainda não chegou. Ausência de uma CHAVE não é erro: o orador pode legitimamente não ser
   * membro da Casa (fase `tribuna_livre_cidadao`; e `orador-id` não tem FK para vereador). */
  composicao: Map<string, IdentidadeParlamentar> | null;
  composicaoStatus: QuorumStatus;
  ultimoSeq: number; // maior seq visto — vira o Last-Event-ID no resume
}

/** A identidade PÚBLICA de um parlamentar — o subconjunto que `GET /sessoes/:id/composicao` serve, que é
 * por sua vez o subconjunto que a rota pública de perfil de vereador já serve sem autenticação nenhuma.
 * Nunca nome civil, nunca estado de presença: esses são da chamada nominal, atrás do papel 'secretario'. */
export type IdentidadeParlamentar = { nomeParlamentar: string | null; cargoMesa: string | null };

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
    composicao: null,
    composicaoStatus: "carregando",
    ultimoSeq: 0,
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
      indice.set(m.vereadorId, { nomeParlamentar: m.nomeParlamentar ?? null, cargoMesa: m.cargoMesa ?? null });
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

export function aplicarEvento(estado: EstadoPlenario, evento: EventoPlenario): EstadoPlenario {
  const base = { ...estado, ultimoSeq: Math.max(estado.ultimoSeq, evento.seq) };

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
      const inscritos = [
        ...base.inscritos,
        { inscricaoId: id, vereadorId: evento.dados["vereador-id"], ordem: evento.dados.ordem },
      ].sort((a, b) => a.ordem - b.ordem);
      return { ...base, inscritos };
    }

    case "inscricao.desistida":
      return { ...base, inscritos: base.inscritos.filter((i) => i.inscricaoId !== evento.dados["inscricao-id"]) };

    case "votacao.aberta": {
      // uma votação por vez no plenário: a abertura SUBSTITUI o placar anterior (zera as contagens).
      const d = evento.dados;
      return {
        ...base,
        placar: {
          votacaoId: d["votacao-id"],
          modalidade: d.modalidade,
          objetoTipo: d["objeto-tipo"],
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
          encerrada: true,
          votosNominais: anterior?.votosNominais ?? {},
          votosSecretos: anterior?.votosSecretos ?? 0,
          resultado: d.resultado,
          totais: { sim: d["total-sim"] ?? null, nao: d["total-nao"] ?? null, abstencao: d["total-abstencao"] ?? null },
          baseMembros: d["base-membros"] ?? null,
        },
      };
    }

    default:
      return base;
  }
}
