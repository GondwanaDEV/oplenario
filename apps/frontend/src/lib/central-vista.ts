// View-model PURO da CENTRAL DA CASA (docs/23 Fatia 3) — o /inicio do operador da Casa (papel `secretario`:
// quem prepara, conduz e fecha a sessão). Porta `telas/central-da-casa.html`. Sem IO: o hook (use-central.ts)
// busca; aqui só se deriva.
//
// Três blocos, em ordem de altitude:
//   1. SESSÃO EM FOCO — a trilha Agendada → Pauta → Em curso → Encerrada → Folha, com UMA ação principal: a da
//      etapa atual. A convocação não é etapa (é derivada da pauta — decisão 3 do docs/23).
//   2. FILA DE TRABALHO — só sinais que TÊM API (medição 3a do docs/23). Cada item sai sozinho quando o ato é
//      concluído (regra de fronteira do INVENTARIO §5.1): não há botão "feito".
//   3. PRÓXIMAS SESSÕES e a prontidão delas (pauta vazia vira alerta).
//
// Honestidade de carga (lição do defeito #16): em cada sinal, `undefined` = ainda carregando e `null` = a busca
// falhou. "Ainda não sei" nunca vira "não há": a fila lista o que não carregou em vez de fingir que está vazia.

import type { SessaoOut } from "./contrato-sessoes.gen";
import type { EstadoSessoes } from "./use-sessoes";
import { proximaSessaoFutura, sessaoAoVivoEm } from "./sessao-corrente";
import { rotuloSessao } from "./inicio-vista";
import { diaLocal, horaLocal, FUSO_DA_CASA } from "./calendario-vista";
import { formatarData } from "./formatar-data";
import { rotularObjetoPrazo } from "./mesa-vista";
import { ESTADOS_AGUARDANDO_PAUTA } from "./proposicoes-vista";

// ---------------------------------------------------------------- entrada

/** O que o hook sabe de uma sessão em detalhe. Cada campo: número = lido; `null` = a busca falhou;
 * `undefined` = ainda não chegou (ou não se aplica àquela sessão). */
export interface DetalheSessao {
  itensPauta?: number | null;
  folhas?: number | null;
  justificativasPendentes?: number | null;
}

export interface PrazoLegalIn {
  objetoTipo: string;
  objetoId: string;
  protocolo: string;
  venceEm: string;
}

export interface ObrigacaoIn {
  id: string;
  templateChave: string;
  venceEm: string;
}

export interface ComentarioIn {
  id: string;
  denunciado: boolean;
}

/** `undefined` = carregando; `null` = falhou. */
type Sinal<T> = T | null | undefined;

export interface EntradaCentral {
  agoraIso: string;
  nome: string | null;
  sessoes: SessaoOut[] | null;
  estadoSessoes: EstadoSessoes;
  detalhes: Record<string, DetalheSessao>;
  pendencias: Sinal<{ itens: PrazoLegalIn[]; total: number }>;
  compliance: Sinal<{ itens: ObrigacaoIn[]; total: number }>;
  moderacao: Sinal<ComentarioIn[]>;
  /** Totais por estado do quadro de tramitação (autoritativos do servidor — nunca `itens.length`). */
  tramitacao: Sinal<{ estado: string; total: number }[]>;
}

// ---------------------------------------------------------------- saída

export interface AcaoCentral {
  rotulo: string;
  href: string;
}

export type EstadoEtapa = "feita" | "atual" | "pendente";
export type ChaveEtapa = "agendada" | "pauta" | "em-curso" | "encerrada" | "folha";
/** A cor da peça de azulejo quando a etapa está feita (chassi `.az-*`). */
export type CorEtapa = "jade" | "cobalto" | "telha" | "jadeclaro" | "amarelo";

export interface EtapaTrilha {
  chave: ChaveEtapa;
  rotulo: string;
  detalhe: string | null;
  estado: EstadoEtapa;
  cor: CorEtapa;
}

export interface FocoSessao {
  sessaoId: string;
  /** "Ao vivo" | "Sessão de hoje" | "Próxima sessão". */
  chamada: string;
  aoVivo: boolean;
  titulo: string;
  quando: string;
  etapas: EtapaTrilha[];
  porque: string;
  principal: AcaoCentral;
  secundarias: AcaoCentral[];
}

export type BlocoFoco =
  | { situacao: "carregando" }
  | { situacao: "erro" }
  | { situacao: "nenhuma"; acao: AcaoCentral }
  | { situacao: "sessao"; foco: FocoSessao };

export type Gravidade = "legal" | "reg" | "adm";
export type IconeFila = "prazo" | "tce" | "folha" | "justificativa" | "moderacao";

export interface ItemFila {
  id: string;
  gravidade: Gravidade;
  icone: IconeFila;
  titulo: string;
  contexto: string | null;
  prazo: { texto: string; atrasado: boolean } | null;
  acao: AcaoCentral;
}

export interface FilaCentral {
  itens: ItemFila[];
  /** Prazos legais que não couberam na fila (vão para os Painéis da Mesa). */
  legaisOcultos: number;
  carregando: boolean;
  /** O que não carregou — dito na tela, nunca tratado como "vazio". */
  indisponiveis: string[];
}

export interface ProximaSessao {
  sessaoId: string;
  titulo: string;
  quando: string;
  pauta: { tom: "ok" | "alerta" | "neutro"; texto: string };
  acao: AcaoCentral;
}

export type TrechoLede = { texto: string; forte?: boolean };

export interface CentralVista {
  saudacao: string;
  hoje: string;
  lede: TrechoLede[];
  foco: BlocoFoco;
  fila: FilaCentral;
  proximas: ProximaSessao[];
  /** Matérias no estágio "Pronta p/ pauta"; `null` quando o quadro não carregou (ou ainda não). */
  prontasParaPauta: number | null;
}

// ---------------------------------------------------------------- regras

/** Quantos prazos legais entram na fila antes de mandar para os Painéis da Mesa. */
export const LIMITE_LEGAIS = 5;
/** Quantas sessões encerradas recentes a Central acompanha (folha e justificativas). */
export const ENCERRADAS_ACOMPANHADAS = 3;
/** Quantas próximas sessões aparecem no trilho de prontidão. */
export const PROXIMAS_VISIVEIS = 3;

const MODALIDADE: Record<string, string> = { presencial: "presencial", remota: "remota", hibrida: "híbrida" };

function plural(n: number, um: string, varios: string): string {
  return `${n} ${n === 1 ? um : varios}`;
}

const enc = encodeURIComponent;
const rotas = {
  conduzir: (id: string) => `/sessoes/${enc(id)}/conduzir`,
  chamada: (id: string) => `/sessoes/${enc(id)}/chamada`,
  tv: (id: string) => `/sessoes/${enc(id)}/tv`,
  telao: (id: string) => `/sessoes/${enc(id)}/plenario`,
  folha: (id: string) => `/sessoes/${enc(id)}/folha`,
  transcricao: (id: string) => `/sessoes/${enc(id)}/transcricao`,
  pauta: (id: string) => `/pauta-convocacao?sessao=${enc(id)}`,
};

/** A data que situa a sessão no calendário: quando abriu (se abriu), senão quando foi marcada. */
function dataDaSessao(s: SessaoOut): string | null {
  return s.abertaEm ?? s.agendadaPara ?? null;
}

/** A sessão que a Central põe em foco: a viva; senão a de HOJE (agendada antes de encerrada — ainda há o que
 * fazer nela); senão a próxima agendada. `nao_realizada`/`arquivada` nunca são foco. */
export function escolherFoco(sessoes: SessaoOut[], agoraIso: string): SessaoOut | null {
  const viva = sessaoAoVivoEm(sessoes);
  if (viva) return viva;
  const hoje = diaLocal(agoraIso);
  const deHoje = (estado: string) =>
    sessoes.find((s) => s.estado === estado && (() => {
      const d = dataDaSessao(s);
      return d != null && diaLocal(d) === hoje;
    })());
  return deHoje("agendada") ?? deHoje("encerrada") ?? proximaSessaoFutura(sessoes, agoraIso);
}

function proximasAgendadas(sessoes: SessaoOut[], agoraIso: string, focoId: string | null): SessaoOut[] {
  return sessoes
    .filter((s): s is SessaoOut & { agendadaPara: string } =>
      s.estado === "agendada" && s.agendadaPara != null && s.agendadaPara > agoraIso && s.id !== focoId)
    .sort((a, b) => a.agendadaPara.localeCompare(b.agendadaPara))
    .slice(0, PROXIMAS_VISIVEIS);
}

function encerradasRecentes(sessoes: SessaoOut[], focoId: string | null): SessaoOut[] {
  return sessoes
    .filter((s) => s.estado === "encerrada" && s.id !== focoId)
    .sort((a, b) => (b.encerradaEm ?? b.abertaEm ?? "").localeCompare(a.encerradaEm ?? a.abertaEm ?? ""))
    .slice(0, ENCERRADAS_ACOMPANHADAS);
}

export interface AlvoDetalhe {
  sessaoId: string;
  pauta: boolean;
  folhas: boolean;
  justificativas: boolean;
}

/** Quais sessões o hook precisa detalhar, e o quê de cada uma — a MESMA seleção que a vista usa, para o hook
 * nunca buscar o que a tela não mostra (nem deixar de buscar o que ela mostra). */
export function sessoesParaDetalhar(sessoes: SessaoOut[], agoraIso: string): AlvoDetalhe[] {
  const foco = escolherFoco(sessoes, agoraIso);
  const alvos = new Map<string, AlvoDetalhe>();
  const juntar = (id: string, o: Partial<AlvoDetalhe>) => {
    const a = alvos.get(id) ?? { sessaoId: id, pauta: false, folhas: false, justificativas: false };
    alvos.set(id, { ...a, pauta: a.pauta || !!o.pauta, folhas: a.folhas || !!o.folhas, justificativas: a.justificativas || !!o.justificativas });
  };
  if (foco) {
    const encerrada = foco.estado === "encerrada";
    juntar(foco.id, { pauta: true, folhas: encerrada, justificativas: foco.estado !== "agendada" });
  }
  for (const s of proximasAgendadas(sessoes, agoraIso, foco?.id ?? null)) juntar(s.id, { pauta: true });
  for (const s of encerradasRecentes(sessoes, foco?.id ?? null)) juntar(s.id, { folhas: true, justificativas: true });
  return [...alvos.values()];
}

function quandoDa(s: SessaoOut): string {
  const d = dataDaSessao(s);
  const partes: string[] = [];
  if (d) {
    partes.push(formatarData(d));
    const h = horaLocal(d);
    if (h) partes.push(h);
  }
  partes.push(MODALIDADE[s.modalidade] ?? s.modalidade);
  return partes.join(" · ");
}

function diaCurto(iso: string | null | undefined): string | null {
  if (!iso) return null;
  const d = diaLocal(iso);
  return d ? `${d.slice(8, 10)}/${d.slice(5, 7)}` : null;
}

function montarFoco(s: SessaoOut, det: DetalheSessao, agoraIso: string): FocoSessao {
  const id = s.id;
  const vivo = s.estado === "aberta" || s.estado === "suspensa";
  const encerrada = s.estado === "encerrada";
  const n = det.itensPauta;
  const pautaVazia = s.estado === "agendada" && n === 0;
  const folhas = det.folhas;

  const pauta: EtapaTrilha = {
    chave: "pauta", rotulo: "Pauta", cor: "cobalto",
    detalhe: n == null ? null : n === 0 ? (s.estado === "agendada" ? "vazia" : "sem itens") : plural(n, "item", "itens"),
    estado: pautaVazia ? "atual" : n == null && s.estado === "agendada" ? "pendente" : "feita",
  };
  const emCurso: EtapaTrilha = {
    chave: "em-curso", rotulo: "Em curso", cor: "telha",
    detalhe: s.estado === "aberta" ? "ao vivo" : s.estado === "suspensa" ? "suspensa" : encerrada ? diaCurto(s.abertaEm) : pautaVazia ? null : "a abrir",
    estado: encerrada ? "feita" : vivo || !pautaVazia ? "atual" : "pendente",
  };
  const etapas: EtapaTrilha[] = [
    { chave: "agendada", rotulo: "Agendada", cor: "jade", detalhe: diaCurto(s.agendadaPara), estado: "feita" },
    pauta,
    emCurso,
    { chave: "encerrada", rotulo: "Encerrada", cor: "jadeclaro", detalhe: encerrada ? diaCurto(s.encerradaEm) : null, estado: encerrada ? "feita" : "pendente" },
    {
      chave: "folha", rotulo: "Folha", cor: "amarelo",
      detalhe: encerrada ? (folhas == null ? null : folhas > 0 ? plural(folhas, "versão", "versões") : "a gerar") : null,
      estado: !encerrada ? "pendente" : folhas != null && folhas > 0 ? "feita" : "atual",
    },
  ];

  const tv = { rotulo: "Modo TV", href: rotas.tv(id) };
  let porque: string;
  let principal: AcaoCentral;
  let secundarias: AcaoCentral[];
  if (vivo) {
    porque = s.estado === "suspensa"
      ? "A sessão está suspensa. Reabra pelo Comando da Mesa quando o presidente retomar."
      : "A sessão está aberta. Conduza a tribuna, as votações e os atos da Mesa pelo Comando.";
    principal = { rotulo: "Conduzir a sessão", href: rotas.conduzir(id) };
    secundarias = [{ rotulo: "Chamada de presença", href: rotas.chamada(id) }, tv, { rotulo: "Telão", href: rotas.telao(id) }];
  } else if (encerrada) {
    const temFolha = folhas != null && folhas > 0;
    porque = temFolha
      ? "Sessão encerrada e folha gerada."
      : folhas === null
        ? "A sessão foi encerrada. Não foi possível conferir se a folha já foi gerada."
        : "A sessão foi encerrada. Gere a folha da sessão para o registro.";
    principal = { rotulo: temFolha ? "Ver a folha" : "Gerar a folha", href: rotas.folha(id) };
    // Faixa A / A.3: a transcrição da gravação (rascunho da IA) — o insumo da ata.
    secundarias = [{ rotulo: "Chamada de presença", href: rotas.chamada(id) },
                   { rotulo: "Transcrição", href: rotas.transcricao(id) }];
  } else if (pautaVazia) {
    porque = "A pauta está vazia. Monte-a antes da sessão: é dela que sai a convocação.";
    principal = { rotulo: "Montar a pauta", href: rotas.pauta(id) };
    secundarias = [];
  } else {
    porque = n == null
      ? "Não foi possível conferir a pauta. Confira-a antes de abrir a sessão."
      : "A pauta está montada. Abra a sessão no Comando da Mesa quando o presidente der início.";
    principal = { rotulo: "Abrir no Comando da Mesa", href: rotas.conduzir(id) };
    secundarias = [{ rotulo: "Ver pauta e convocação", href: rotas.pauta(id) }, tv];
  }

  const hoje = diaLocal(agoraIso);
  const d = dataDaSessao(s);
  const chamada = vivo ? "Ao vivo" : d != null && diaLocal(d) === hoje ? "Sessão de hoje" : "Próxima sessão";
  return { sessaoId: id, chamada, aoVivo: vivo, titulo: rotuloSessao(s), quando: quandoDa(s), etapas, porque, principal, secundarias };
}

/** Dias corridos de hoje (no fuso da Casa) até o vencimento — negativo = já venceu. Conta por DIA de
 * calendário, não por 24h: "vence hoje" às 23h ainda é hoje. */
export function diasAte(venceEm: string, agoraIso: string): number | null {
  const alvo = diaLocal(venceEm);
  const hoje = diaLocal(agoraIso);
  if (!alvo || !hoje) return null;
  return Math.round((Date.parse(`${alvo}T00:00:00Z`) - Date.parse(`${hoje}T00:00:00Z`)) / 86_400_000);
}

function textoPrazo(dias: number | null): { texto: string; atrasado: boolean } | null {
  if (dias == null) return null;
  if (dias < 0) return { texto: `venceu há ${plural(-dias, "dia", "dias")}`, atrasado: true };
  if (dias === 0) return { texto: "vence hoje", atrasado: false };
  return { texto: `vence em ${plural(dias, "dia", "dias")}`, atrasado: false };
}

function montarFila(e: EntradaCentral, focoId: string | null): FilaCentral {
  const indisponiveis: string[] = [];
  let carregando = false;
  const itens: ItemFila[] = [];

  // --- prazos legais: e-SIC/LGPD/ouvidoria + obrigações do TCE, juntos por vencimento
  const legais: { item: ItemFila; vence: string }[] = [];
  let legaisTotal = 0;
  if (e.pendencias === undefined) carregando = true;
  else if (e.pendencias === null) indisponiveis.push("prazos de e-SIC, LGPD e ouvidoria");
  else {
    legaisTotal += Math.max(e.pendencias.total, e.pendencias.itens.length);
    for (const p of e.pendencias.itens) {
      legais.push({
        vence: p.venceEm,
        item: {
          id: `pend-${p.objetoTipo}-${p.objetoId}`, gravidade: "legal", icone: "prazo",
          titulo: `${rotularObjetoPrazo(p.objetoTipo)} ${p.protocolo}`, contexto: null,
          prazo: textoPrazo(diasAte(p.venceEm, e.agoraIso)),
          acao: { rotulo: "Ver prazos", href: "/paineis/mesa" },
        },
      });
    }
  }
  if (e.compliance === undefined) carregando = true;
  else if (e.compliance === null) indisponiveis.push("obrigações do TCE");
  else {
    legaisTotal += Math.max(e.compliance.total, e.compliance.itens.length);
    for (const o of e.compliance.itens) {
      legais.push({
        vence: o.venceEm,
        item: {
          id: `tce-${o.id}`, gravidade: "legal", icone: "tce",
          titulo: `Obrigação TCE em aberto · ${o.templateChave}`, contexto: null,
          prazo: textoPrazo(diasAte(o.venceEm, e.agoraIso)),
          acao: { rotulo: "Ver compliance", href: "/paineis/mesa" },
        },
      });
    }
  }
  legais.sort((a, b) => (diaLocal(a.vence) ?? a.vence).localeCompare(diaLocal(b.vence) ?? b.vence) || a.item.id.localeCompare(b.item.id));
  const legaisVisiveis = legais.slice(0, LIMITE_LEGAIS);
  itens.push(...legaisVisiveis.map((l) => l.item));
  const legaisOcultos = Math.max(0, legaisTotal - legaisVisiveis.length);

  // --- sessões: folha a gerar (encerradas recentes fora do foco — a do foco já tem a ação lá em cima) e
  // justificativas de ausência a decidir (de qualquer sessão acompanhada, inclusive a do foco)
  const sessoes = e.sessoes ?? [];
  for (const s of encerradasRecentes(sessoes, focoId)) {
    const f = e.detalhes[s.id]?.folhas;
    if (f === undefined) carregando = true;
    else if (f === 0) {
      const quando = diaLocal(s.encerradaEm ?? "") ? formatarData(s.encerradaEm!) : null;
      itens.push({
        id: `folha-${s.id}`, gravidade: "reg", icone: "folha",
        titulo: `Gerar a folha da ${rotuloSessao(s)}`,
        contexto: quando ? `encerrada em ${quando}, ainda sem folha` : "encerrada, ainda sem folha",
        prazo: null, acao: { rotulo: "Gerar folha", href: rotas.folha(s.id) },
      });
    }
  }
  const comJustificativa = [
    ...(focoId ? sessoes.filter((s) => s.id === focoId && s.estado !== "agendada") : []),
    ...encerradasRecentes(sessoes, focoId),
  ];
  for (const s of comJustificativa) {
    const j = e.detalhes[s.id]?.justificativasPendentes;
    if (j === undefined) carregando = true;
    else if (j !== null && j > 0) {
      itens.push({
        id: `just-${s.id}`, gravidade: "reg", icone: "justificativa",
        titulo: `${plural(j, "justificativa", "justificativas")} de ausência a decidir`,
        contexto: rotuloSessao(s), prazo: null,
        acao: { rotulo: "Decidir", href: rotas.chamada(s.id) },
      });
    }
  }

  // --- portal: comentários para moderar
  if (e.moderacao === undefined) carregando = true;
  else if (e.moderacao === null) indisponiveis.push("comentários para moderar");
  else if (e.moderacao.length > 0) {
    const denunciados = e.moderacao.filter((c) => c.denunciado).length;
    itens.push({
      id: "moderacao", gravidade: "adm", icone: "moderacao",
      titulo: `${plural(e.moderacao.length, "comentário", "comentários")} do portal para moderar`,
      contexto: denunciados > 0 ? plural(denunciados, "denunciado", "denunciados") : null,
      prazo: null, acao: { rotulo: "Moderar", href: "/moderacao" },
    });
  }

  return { itens, legaisOcultos, carregando, indisponiveis };
}

function montarProximas(e: EntradaCentral, focoId: string | null): ProximaSessao[] {
  return proximasAgendadas(e.sessoes ?? [], e.agoraIso, focoId).map((s) => {
    const n = e.detalhes[s.id]?.itensPauta;
    const pauta: ProximaSessao["pauta"] =
      n === undefined ? { tom: "neutro", texto: "Pauta: …" }
      : n === null ? { tom: "neutro", texto: "Pauta: não carregou" }
      : n === 0 ? { tom: "alerta", texto: "Pauta vazia" }
      : { tom: "ok", texto: `Pauta: ${plural(n, "item", "itens")}` };
    return {
      sessaoId: s.id, titulo: rotuloSessao(s), quando: quandoDa(s), pauta,
      acao: { rotulo: n === 0 ? "Montar a pauta" : "Ver pauta", href: rotas.pauta(s.id) },
    };
  });
}

const HORA_DA_CASA = new Intl.DateTimeFormat("pt-BR", { hour: "2-digit", hourCycle: "h23", timeZone: FUSO_DA_CASA });
const HOJE_DA_CASA = new Intl.DateTimeFormat("pt-BR", { weekday: "long", day: "numeric", month: "long", timeZone: FUSO_DA_CASA });

function saudacaoDe(agoraIso: string, nome: string | null): string {
  const h = Number(HORA_DA_CASA.format(new Date(agoraIso)));
  const cumprimento = h < 12 ? "Bom dia" : h < 18 ? "Boa tarde" : "Boa noite";
  const primeiro = nome?.trim().split(/\s+/)[0];
  return primeiro ? `${cumprimento}, ${primeiro}.` : `${cumprimento}.`;
}

function ledeDe(foco: BlocoFoco, fila: FilaCentral, sessoes: SessaoOut[], det: Record<string, DetalheSessao>): TrechoLede[] {
  const t: TrechoLede[] = [];
  if (foco.situacao === "sessao") {
    const s = sessoes.find((x) => x.id === foco.foco.sessaoId)!;
    const r = rotuloSessao(s);
    const n = det[s.id]?.itensPauta;
    if (s.estado === "aberta") t.push({ texto: "A " }, { texto: `${r} está aberta`, forte: true }, { texto: "." });
    else if (s.estado === "suspensa") t.push({ texto: "A " }, { texto: `${r} está suspensa`, forte: true }, { texto: "." });
    else if (s.estado === "encerrada") {
      t.push({ texto: "A " }, { texto: `${r} foi encerrada hoje`, forte: true });
      const f = det[s.id]?.folhas;
      t.push({ texto: f === 0 ? " — falta gerar a folha." : "." });
    } else if (foco.foco.chamada === "Sessão de hoje") {
      const h = s.agendadaPara ? horaLocal(s.agendadaPara) : null;
      t.push({ texto: "A " }, { texto: `${r} é hoje${h ? `, às ${h}` : ""}`, forte: true });
      t.push({ texto: n === 0 ? ", e a pauta está vazia." : n != null && n > 0 ? ", com a pauta montada." : "." });
    } else {
      t.push({ texto: "A próxima sessão é a " }, { texto: r, forte: true });
      t.push({ texto: s.agendadaPara ? `, em ${formatarData(s.agendadaPara)}.` : "." });
      if (n === 0) t.push({ texto: " A pauta ainda está vazia." });
    }
  } else if (foco.situacao === "nenhuma") {
    t.push({ texto: "A Casa não tem sessão marcada." });
  }

  const total = fila.itens.length + fila.legaisOcultos;
  if (!fila.carregando && fila.indisponiveis.length === 0 && total === 0) {
    t.push({ texto: `${t.length ? " " : ""}Nada pendente na fila.` });
  } else if (total > 0) {
    t.push({ texto: t.length ? " " : "" }, { texto: `${plural(total, "item pede", "itens pedem")} você`, forte: true });
    const atrasados = fila.itens.filter((i) => i.prazo?.atrasado).length;
    if (atrasados > 0) t.push({ texto: " — " }, { texto: `${plural(atrasados, "prazo legal vencido", "prazos legais vencidos")}`, forte: true });
    t.push({ texto: "." });
  }
  return t;
}

export function derivarCentral(e: EntradaCentral): CentralVista {
  const sessoes = e.sessoes ?? [];
  let foco: BlocoFoco;
  let focoId: string | null = null;
  if (e.estadoSessoes === "carregando") foco = { situacao: "carregando" };
  else if (e.estadoSessoes === "erro") foco = { situacao: "erro" };
  else {
    const s = escolherFoco(sessoes, e.agoraIso);
    if (!s) foco = { situacao: "nenhuma", acao: { rotulo: "Agendar sessão", href: "/agendar-sessao" } };
    else {
      focoId = s.id;
      const det = e.detalhes[s.id] ?? {};
      // Segura a trilha até os detalhes que ela usa chegarem — sem isso a ação principal piscaria entre
      // "Abrir no Comando da Mesa" e "Montar a pauta" no meio segundo da busca.
      const falta = det.itensPauta === undefined || (s.estado === "encerrada" && det.folhas === undefined);
      foco = falta ? { situacao: "carregando" } : { situacao: "sessao", foco: montarFoco(s, det, e.agoraIso) };
    }
  }

  // Sem a lista de sessões, a fila ainda mostra os sinais que não dependem dela (prazos, moderação).
  const filaBase = montarFila(e.estadoSessoes === "pronto" ? e : { ...e, sessoes: [] }, focoId);
  const fila = { ...filaBase, carregando: filaBase.carregando || e.estadoSessoes === "carregando" };

  const prontasParaPauta = e.tramitacao == null
    ? null
    : e.tramitacao.filter((t) => ESTADOS_AGUARDANDO_PAUTA.has(t.estado)).reduce((acc, t) => acc + t.total, 0);

  return {
    saudacao: saudacaoDe(e.agoraIso, e.nome),
    hoje: HOJE_DA_CASA.format(new Date(e.agoraIso)),
    lede: ledeDe(foco, fila, sessoes, e.detalhes),
    foco,
    fila,
    proximas: e.estadoSessoes === "pronto" ? montarProximas(e, focoId) : [],
    prontasParaPauta,
  };
}
