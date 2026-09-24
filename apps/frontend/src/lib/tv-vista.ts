// View-model PURO do Modo TV da sessão (docs/22). Traduz o estado ao vivo (`usePlenario`) + a pauta no que a
// TV do plenário mostra. Sem React, sem fetch, sem relógio próprio (o `agora` entra por parâmetro): testado em
// tv-vista.test.ts. Reusa as regras que já valem para o painel — sigilo §22.6 (`derivarPlacar`), quórum
// tri-estado (`vistaDoQuorum`), nome público (`identidadeDe`), sigla da matéria (`formatarNumeroProposicao`)
// — em vez de reinventá-las: a TV não pode afirmar mais (nem outra coisa) do que o painel afirma.
//
// A regra da casa vale dobrado aqui, porque é a tela que o PÚBLICO lê: nunca um número que não se sabe. Onde
// o painel mostra "—", a TV mostra "—".

import type { PautaItemOut, PautaOut, SessaoOut } from "./contrato";
import { formatarTempo, segundosDecorridos } from "./cronometro";
import { formatarHora } from "./formatar-data";
import { iniciais } from "./iniciais";
import { derivarPlacar } from "./placar-vista";
import type { EstadoPlenario, PlacarVotacao, VotoNominal } from "./plenario-reducer";
import { identidadeDe } from "./plenario-reducer";
import { formatarNumeroProposicao } from "./proposicoes-vista";
import { nomeFase, nomeTipoFala, nomeTipoSessao } from "./rotulos-sessao";

// ---------------------------------------------------------------- fase

export type FaseTv = "abertura" | "em-curso" | "em-apreciacao" | "votacao" | "resultado" | "pausa" | "encerrada";

/** A fase é DERIVADA do estado ao vivo — nunca escolhida à mão. `exibindoResultado` é o único insumo que
 * não vem do servidor: o intervalo de ~8 s em que a TV segura o veredito em tela cheia (ver
 * `encerrouAoVivo`). `emApreciacao` (docs/23 Fatia 4b) = há matéria ANUNCIADA pela Mesa ainda não votada
 * (`vistaApreciacaoTv`): ela vira o herói da tela até a votação abrir — a votação aberta sempre vence. */
export function faseDaTv(
  estadoSessao: string,
  placar: PlacarVotacao | null,
  exibindoResultado: boolean,
  emApreciacao = false,
): FaseTv {
  if (estadoSessao === "agendada") return "abertura";
  if (estadoSessao === "suspensa") return "pausa";
  if (estadoSessao !== "aberta") return "encerrada";
  if (exibindoResultado && placar?.encerrada) return "resultado";
  if (placar && !placar.encerrada) return "votacao";
  if (emApreciacao) return "em-apreciacao";
  return "em-curso";
}

/** Janela inicial em que a TV NÃO dispara o veredito em tela cheia. O canal replaya a sessão desde `id: 1`
 * ao conectar (docstring de `PlacarVotacao.proposicao`): abrir a TV depois de uma votação já encerrada
 * reproduz `votacao.aberta` → `votacao.encerrada` em rajada, e sem esta janela a TV "anunciaria" como
 * novidade um resultado de uma hora atrás. O backlog chega no primeiro poll (1 s no servidor). */
export const AQUECIMENTO_MS = 4000;
/** Quanto tempo o veredito fica em tela cheia antes de a TV voltar à sessão. */
export const DURACAO_RESULTADO_MS = 8000;

/** True quando `atual` é o ENCERRAMENTO, visto ao vivo, de uma votação que esta TV viu ABERTA (mesmo
 * `votacaoId`), com resultado válido, e depois do aquecimento. */
export function encerrouAoVivo(
  anterior: PlacarVotacao | null,
  atual: PlacarVotacao | null,
  agoraMs: number,
  montadaEmMs: number,
): boolean {
  if (!atual || !atual.encerrada) return false;
  if (atual.resultado !== "aprovada" && atual.resultado !== "rejeitada") return false;
  if (agoraMs - montadaEmMs < AQUECIMENTO_MS) return false;
  return anterior !== null && anterior.votacaoId === atual.votacaoId && !anterior.encerrada;
}

// ---------------------------------------------------------------- moldura (topo)

export type SeloTv = { rotulo: string; tom: "vivo" | "pausa" | "fim" };

export function seloDaTv(estadoSessao: string, conexao: string): SeloTv {
  if (conexao === "reconectando") return { rotulo: "Reconectando…", tom: "fim" };
  if (estadoSessao === "aberta") return { rotulo: "Ao vivo", tom: "vivo" };
  if (estadoSessao === "suspensa") return { rotulo: "Suspensa", tom: "pausa" };
  if (estadoSessao === "agendada") return { rotulo: "Aguardando abertura", tom: "fim" };
  if (estadoSessao === "nao_realizada") return { rotulo: "Não realizada", tom: "fim" };
  return { rotulo: "Encerrada", tom: "fim" };
}

/** "15ª Sessão Ordinária". O tipo vem do enum do backend (`ordinaria`) via `nomeTipoSessao`. */
export function tituloDaSessao(sessao: Pick<SessaoOut, "tipo-sessao" | "numero-sequencial">): string {
  const tipo = nomeTipoSessao(sessao["tipo-sessao"]);
  const Tipo = tipo ? tipo[0].toUpperCase() + tipo.slice(1) : "";
  return `${sessao["numero-sequencial"]}ª Sessão ${Tipo}`.trim();
}

/** HH:MM do relógio da TV — no fuso da máquina, que é a da sala do plenário. */
export function relogioDaTv(agoraMs: number): string {
  return formatarHora(new Date(agoraMs).toISOString());
}

const FORMATO_DATA_TV = new Intl.DateTimeFormat("pt-BR", { weekday: "long", day: "numeric", month: "long" });

/** "terça-feira, 23 de setembro". */
export function dataDaTv(agoraMs: number): string {
  return FORMATO_DATA_TV.format(new Date(agoraMs));
}

/** A linha sob o relógio: há quanto tempo a sessão está aberta, ou quando ela está prevista. `null` quando
 * não se sabe nenhum dos dois (nunca "sessão há 00:00:00"). */
export function subRelogioDaTv(
  sessao: Pick<SessaoOut, "aberta-em" | "agendada-para">,
  estadoSessao: string,
  agoraMs: number,
): string | null {
  if (estadoSessao === "agendada") {
    return sessao["agendada-para"] ? `início previsto ${formatarHora(sessao["agendada-para"])}` : null;
  }
  if (!sessao["aberta-em"]) return null;
  const s = Math.max(0, Math.floor((agoraMs - Date.parse(sessao["aberta-em"])) / 1000));
  return Number.isFinite(s) ? `sessão há ${formatarTempo(s)}` : null;
}

// ---------------------------------------------------------------- pauta

export interface ItemPautaTv {
  id: string;
  ordem: number;
  sigla: string; // "PL 22/2026" | "Leitura" | "Comunicado" | …
  descricao: string;
  fase: string; // "Ordem do Dia"
  emVotacao: boolean;
  /** O item que a Mesa anunciou e está em apreciação (docs/23 Fatia 4b). */
  emApreciacao: boolean;
}

const NOME_TIPO_ITEM: Record<string, string> = {
  proposicao: "Proposição",
  leitura: "Leitura",
  comunicado: "Comunicado",
  homenagem: "Homenagem",
};

/** Sigla + descrição de um item da pauta. Item de proposição usa o resumo que a pauta traz (sigla/número/
 * ementa); sem ele (a leitura em legislativo não respondeu), cai no rótulo honesto — nunca no UUID. */
function siglaEDescricao(it: PautaItemOut): { sigla: string; descricao: string } {
  const tipo = it["tipo-item"];
  const r = tipo === "proposicao" ? it.proposicao ?? null : null;
  const sigla = r ? formatarNumeroProposicao(r.tipo, r.sequencial, r.ano) : NOME_TIPO_ITEM[tipo] ?? tipo;
  const descricao = r ? r.ementa : tipo === "proposicao" ? "Matéria da ordem do dia" : it["texto-descricao"] ?? sigla;
  return { sigla, descricao };
}

/** Os itens da pauta como a TV os mostra. `emVotacao` casa o item com a votação ABERTA pelo `proposicao-id`
 * (o `objeto-id` do placar); `emApreciacao` marca o item anunciado (`vistaApreciacaoTv(...).itemId`). */
export function itensDaPautaTv(
  pauta: PautaOut | null,
  placar: PlacarVotacao | null,
  itemEmApreciacao: string | null = null,
): ItemPautaTv[] {
  if (!pauta) return [];
  const votando = placar && !placar.encerrada ? placar.objetoId : null;
  return pauta.itens.map((it) => {
    const emVotacao = votando !== null && it["proposicao-id"] === votando;
    return {
      id: it.id,
      ordem: it.ordem,
      ...siglaEDescricao(it),
      fase: nomeFase(it.fase),
      emVotacao,
      emApreciacao: !emVotacao && itemEmApreciacao !== null && it.id === itemEmApreciacao,
    };
  });
}

// ---------------------------------------------------------------- em apreciação (docs/23 Fatia 4b)

export interface AnuncioCorrente {
  itemId: string;
  anunciadoEm: string;
  /** `undefined` quando o anúncio veio da PAUTA (HTTP), não do SSE: aí não se sabe qual votação era a corrente
   * no anúncio, e uma votação encerrada da mesma matéria conta como fim da apreciação (conservador). */
  votacaoNoAnuncio: string | null | undefined;
}

/** O anúncio que vale AGORA: o visto ao vivo (SSE) ou o que a pauta trouxe (`em-apreciacao`, o estado inicial
 * — o replay do canal só retém 5 min). Vale o mais recente; empate de item, o do SSE (sabe a votação). */
export function anuncioCorrente(estado: EstadoPlenario, pauta: PautaOut | null): AnuncioCorrente | null {
  const vivo = estado.anuncio;
  const inicial = pauta?.["em-apreciacao"] ?? null;
  const doVivo = vivo ? { itemId: vivo.itemId, anunciadoEm: vivo.anunciadoEm, votacaoNoAnuncio: vivo.votacaoNoAnuncio } : null;
  const daPauta = inicial ? { itemId: inicial["item-id"], anunciadoEm: inicial["anunciado-em"], votacaoNoAnuncio: undefined } : null;
  if (!doVivo || !daPauta) return doVivo ?? daPauta;
  if (doVivo.itemId === daPauta.itemId) return doVivo;
  const tv = Date.parse(doVivo.anunciadoEm);
  const tp = Date.parse(daPauta.anunciadoEm);
  if (Number.isNaN(tp)) return doVivo;
  if (Number.isNaN(tv)) return daPauta;
  return tv >= tp ? doVivo : daPauta;
}

export interface VistaApreciacaoTv {
  itemId: string;
  sigla: string;
  descricao: string;
  fase: string;
  /** Texto de autoria da matéria; `null` em item de texto ou matéria sem autoria textual. */
  autor: string | null;
}

/** A matéria em apreciação como a TV mostra, ou `null` quando não há: nada anunciado, item fora da pauta
 * carregada (retirado, ou incluído depois — a TV rebusca a pauta a cada anúncio), ou já VOTADA — uma votação
 * da mesma matéria encerrou depois do anúncio (`votacaoId` diferente do que estava no placar ao anunciar). */
export function vistaApreciacaoTv(estado: EstadoPlenario, pauta: PautaOut | null): VistaApreciacaoTv | null {
  const a = anuncioCorrente(estado, pauta);
  if (!a || !pauta) return null;
  const it = pauta.itens.find((i) => i.id === a.itemId);
  if (!it) return null;
  const placar = estado.placar;
  const propId = it["proposicao-id"] ?? null;
  if (placar?.encerrada && propId !== null && placar.objetoId === propId && placar.votacaoId !== a.votacaoNoAnuncio) {
    return null;
  }
  const autor = it["tipo-item"] === "proposicao" ? it.proposicao?.["autor-texto"]?.trim() || null : null;
  return { itemId: it.id, ...siglaEDescricao(it), fase: nomeFase(it.fase), autor };
}

// ---------------------------------------------------------------- matéria em votação (título)

const ROTULO_OBJETO: Record<string, string> = {
  emenda: "Emenda",
  parecer: "Parecer",
  requerimento: "Requerimento",
  proposicao: "Proposição",
  redacao_final: "Redação final",
};

/** Número + ementa da matéria do placar. Sem o resumo, o número cai no TIPO do objeto e a ementa fica
 * vazia (a tela a omite) — honesto, nunca inventado. */
export function materiaDoPlacar(placar: PlacarVotacao): { numero: string; ementa: string } {
  const p = placar.proposicao;
  if (p) return { numero: formatarNumeroProposicao(p.tipo, p.sequencial, p.ano), ementa: p.ementa };
  return { numero: placar.objetoTipo ? ROTULO_OBJETO[placar.objetoTipo] ?? placar.objetoTipo : "Matéria", ementa: "" };
}

// ---------------------------------------------------------------- votação em curso

export interface VotoNominalTv {
  nome: string;
  voto: VotoNominal;
}

export interface VistaVotacaoTv {
  numero: string;
  ementa: string;
  modalidade: "nominal" | "secreta";
  /** Presentes (quórum oficial do servidor) — quem pode votar. `null` = o quórum não respondeu. */
  podemVotar: number | null;
  membrosDaCasa: number | null;
  votaram: number;
  /** podemVotar − votaram. `null` quando não se sabe `podemVotar` (nunca inventado a partir de outro número). */
  faltam: number | null;
  /** Contagem por voto. Na SECRETA em curso é `null` (§22.6: só o contador anônimo é público até o fim). */
  sim: number | null;
  nao: number | null;
  abstencao: number | null;
  nominais: VotoNominalTv[];
  avisoLacuna: boolean;
}

const VEREADOR_SEM_NOME = "Vereador(a)";

/** A votação ABERTA como a TV mostra. `null` quando não há votação aberta.
 *
 * Por que "podem votar" vem do QUÓRUM e não de `baseMembros`: `baseMembros` só chega no ENCERRAMENTO
 * (payload de `votacao.encerrada`) — durante a votação, que é quando a galeria quer saber "quantos faltam",
 * ele é nulo. O quórum é o número oficial de presentes que o servidor já publica ao vivo (`/quorum`). */
export function vistaVotacaoTv(estado: EstadoPlenario): VistaVotacaoTv | null {
  const placar = estado.placar;
  if (!placar || placar.encerrada) return null;
  const v = derivarPlacar(placar, estado.avisoLacuna);
  if (v.kind === "nenhuma") return null;
  const { numero, ementa } = materiaDoPlacar(placar);
  const podemVotar = estado.quorum?.presentesTotal ?? null;
  const membrosDaCasa = estado.quorum?.membrosDaCasa ?? null;

  if (v.kind === "nominal") {
    const votaram = v.sim + v.nao + v.abstencao;
    const nominais = v.votos
      .map(({ vereadorId, voto }) => ({ nome: identidadeDe(estado, vereadorId)?.nomeParlamentar ?? VEREADOR_SEM_NOME, voto }))
      .sort((a, b) => a.nome.localeCompare(b.nome, "pt-BR"));
    return {
      numero, ementa, modalidade: "nominal", podemVotar, membrosDaCasa, votaram,
      faltam: podemVotar !== null ? Math.max(0, podemVotar - votaram) : null,
      sim: v.sim, nao: v.nao, abstencao: v.abstencao, nominais, avisoLacuna: v.avisoLacuna,
    };
  }
  return {
    numero, ementa, modalidade: "secreta", podemVotar, membrosDaCasa, votaram: v.registrados,
    faltam: podemVotar !== null ? Math.max(0, podemVotar - v.registrados) : null,
    sim: null, nao: null, abstencao: null, nominais: [], avisoLacuna: v.avisoLacuna,
  };
}

// ---------------------------------------------------------------- resultado

export interface VistaResultadoTv {
  resultado: "aprovada" | "rejeitada";
  numero: string;
  ementa: string;
  modalidade: "nominal" | "secreta";
  sim: number;
  nao: number;
  abstencao: number;
}

/** O veredito da última votação encerrada, com o placar final (o agregado do encerramento é público mesmo
 * na secreta). `null` sem votação encerrada com resultado válido. */
export function vistaResultadoTv(estado: EstadoPlenario): VistaResultadoTv | null {
  const placar = estado.placar;
  if (!placar || !placar.encerrada) return null;
  const v = derivarPlacar(placar, estado.avisoLacuna);
  if (v.kind === "nenhuma" || (v.resultado !== "aprovada" && v.resultado !== "rejeitada")) return null;
  const { numero, ementa } = materiaDoPlacar(placar);
  const t = v.kind === "nominal" ? { sim: v.sim, nao: v.nao, abstencao: v.abstencao } : v.totais ?? { sim: 0, nao: 0, abstencao: 0 };
  return { resultado: v.resultado, numero, ementa, modalidade: v.kind, ...t };
}

// ---------------------------------------------------------------- tribuna

export interface VistaTribunaTv {
  nome: string;
  iniciais: string;
  detalhe: string; // "Fala principal · Vice-presidente"
  fase: string;
  decorrido: string; // "06:24"
  pausado: boolean;
}

const ORADOR_SEM_NOME = "Orador com a palavra";

/** Quem está com a palavra. Sem nome (cidadão na tribuna livre, cadastro incompleto) → rótulo neutro e
 * avatar "—": nunca caracteres do UUID, que leriam como identidade. Só o tempo DECORRIDO: o painel não
 * conhece o tempo-limite da fala (o servidor só emite os marcos), então a TV não inventa um. */
export function vistaTribunaTv(estado: EstadoPlenario, agoraMs: number): VistaTribunaTv | null {
  const o = estado.oradorAtual;
  if (!o) return null;
  const id = identidadeDe(estado, o.oradorId);
  const nome = id?.nomeParlamentar ?? null;
  const marcos = estado.marcosCronometro;
  const pausado = marcos.length > 0 && marcos[marcos.length - 1].tipo === "pausada";
  return {
    nome: nome ?? ORADOR_SEM_NOME,
    iniciais: nome ? iniciais(nome) : "—",
    // docs/23 Fatia 4a: o partido (do mandato na data da sessão) entre o tipo de fala e o cargo na Mesa.
    detalhe: [nomeTipoFala(o.tipoFala), id?.partido, id?.cargoMesa].filter(Boolean).join(" · "),
    fase: nomeFase(o.fase),
    decorrido: formatarTempo(segundosDecorridos(o.iniciouEm, marcos, agoraMs)),
    pausado,
  };
}
