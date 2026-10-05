// View-model puro da Ficha da Matéria (Onda B Slice 3) — traduz FichaMateriaOut (wire, camelizado) para o
// que o cabeçalho/rail/abas mostram. Reaproveita `derivarTramitacao` (mesmo `estado` livre/template-driven
// já tratado em tramitacao-vista.ts) e `categorizarSituacao` (mesmo chip `.chip-tram/aguarda/aprovada/
// arquivada` já usado em proposicoes-vista.ts) — nenhum vocabulário novo de estado de PROPOSIÇÃO é
// inventado aqui.
//
// Emendas têm enum FIXO em código (apps/backend logic.clj `estados-emenda`: apresentada/admitida/
// {aprovada|rejeitada|prejudicada|retirada}) — mas o mapeamento de rótulo aqui trata qualquer valor fora
// do mapa do MESMO jeito fail-closed que o `estado` livre de tramitação/parecer: nunca lança, degrada pro
// valor cru. Pareceres (`estado`) são TEMPLATE-DRIVEN (model Parecer, apps/backend) com só os 4 desfechos
// terminais cravados em código (aprovado/rejeitado/prejudicado/prazo_vencido) — mesmo tratamento.
//
// Decisão da fatia (spec §"Decisões técnicas"): pareceres/emendas mostram TODOS os estados (chip de status
// por linha), não só os ativos — estas funções não filtram nada, só rotulam.

import { rotularComissao } from "./comissao-vista";
import { derivarTramitacao, rotularSituacaoPeloRito } from "./tramitacao-vista";
import { categorizarSituacao, type CategoriaSituacao } from "./proposicoes-vista";
import { textoRecebimento } from "./recebimento-vista";
import { dataDoPrazo, formatarNumeroAutografo, formatarNumeroNorma } from "./pos-aprovacao-vista";
import type {
  FichaMateriaOut,
  HistoricoTramitacaoItemOut,
  EmendaResumoOut,
  ParecerResumoOut,
  PosAprovacaoOut,
} from "./contrato-legislativo.gen";

// ---------------------------------------------------------------------------
// Rail: "Dados da matéria"
// ---------------------------------------------------------------------------

export type DadosMateriaVista = {
  situacao: string;
  apensadosTotal: number;
  // Fatia "truncamento-familia": `apensadosTotal` acima é `ficha.apensadas.length` — o tamanho da lista
  // que O SERVIDOR já cortou no teto (50), nunca uma contagem independente. Quando `apensadasTruncado`
  // vem `true`, `apensadosTotal` deixa de ser "quantas existem" e passa a significar "pelo menos estas
  // tantas" — o servidor manda o BOOLEANO (mesma forma de `historico-truncado` na rota irmã
  // /tramitacao), nunca um segundo número, então a UI não pode dizer "de quantas" — só que há mais.
  apensadasTruncado: boolean;
  apresentadaEm: string;
  // Fatia "truncamento-familia" (achado IMPORTANTE da revisão adversarial): o corte de `tramitacao`
  // mantém as N MAIS RECENTES — sob truncamento, `ordenado[0]` é a transição mais antiga SOBREVIVENTE,
  // nunca a primeira de verdade. `apresentadaEm` continua sendo essa data (é o melhor limite superior que
  // temos — a apresentação real é ANTERIOR a ela), mas o card não pode afirmá-la como fato sem este
  // marcador: quando `true`, o rótulo vira "anterior a <data>", nunca a data nua.
  apresentadaEmIncerta: boolean;
  ultimaAcaoEm: string;
};

/** `desfecho`: o último ato depois do plenário (`desfechoDaPosAprovacao`). A situação é a mesma do chip do cabeçalho:
 * o desfecho a partir do autógrafo, senão o nome da Casa para a etapa atual, senão o rótulo fixo. */
export function derivarDadosMateria(ficha: FichaMateriaOut, desfecho?: string | null): DadosMateriaVista {
  const rotuloSituacao = rotularSituacaoPeloRito(ficha.proposicao.estado, ficha.rito, desfecho);
  const ordenado = [...ficha.tramitacao].sort((a, b) => a.ocorridoEm.localeCompare(b.ocorridoEm));
  return {
    situacao: rotuloSituacao,
    apensadosTotal: ficha.apensadas.length,
    apensadasTruncado: ficha.apensadasTruncado,
    // primeira/última transição registrada; sem histórico, cai honestamente pra atualizadoEm (nunca
    // inventa uma data de "apresentação" que não temos).
    apresentadaEm: ordenado[0]?.ocorridoEm ?? ficha.proposicao.atualizadoEm,
    // defensivo (regra 4, mesmo padrão de `apensadasTruncado`): só marca incerta se de fato sobrou item
    // na lista cortada — o servidor não deveria mandar truncado=true com lista vazia, mas se mandasse não
    // há data nenhuma pra marcar como incerta.
    apresentadaEmIncerta: ficha.tramitacaoTruncado && ordenado.length > 0,
    ultimaAcaoEm: ordenado[ordenado.length - 1]?.ocorridoEm ?? ficha.proposicao.atualizadoEm,
  };
}

// ---------------------------------------------------------------------------
// Aba "Tramitação" — linha do tempo completa (mais recente primeiro, mesma leitura do mockup
// ficha-materia.html .tempo)
// ---------------------------------------------------------------------------

export type ItemTimelineVista = HistoricoTramitacaoItemOut & {
  rotuloDe: string;
  rotuloPara: string;
  /** fatia 2b: "Recebida por X em … · assinada", ou null quando a movimentação não tem recibo de carga. */
  recebimentoTexto: string | null;
};

export function derivarTimelineTramitacao(
  tramitacao: HistoricoTramitacaoItemOut[],
): ItemTimelineVista[] {
  return [...tramitacao]
    .sort((a, b) => b.ocorridoEm.localeCompare(a.ocorridoEm))
    .map((item) => ({
      ...item,
      // fail-closed por construção: derivarTramitacao nunca lança, degrada pro estado cru quando fora do
      // vocabulário ilustrativo (ex. vocabulário template-driven de um tenant real).
      // o nome que o rito da Casa dá a cada estado (`deNome`/`paraNome`, o mesmo da faixa e da lista); sem ele, o fixo
      rotuloDe: item.deNome?.trim() || derivarTramitacao(item.deEstado).rotuloSituacao,
      rotuloPara: item.paraNome?.trim() || derivarTramitacao(item.paraEstado).rotuloSituacao,
      recebimentoTexto: textoRecebimento(item.recebimento),
    }));
}

// ---------------------------------------------------------------------------
// Aba "Tramitação" — os atos depois da aprovação (ledger docs/16, linha 30)
// ---------------------------------------------------------------------------
// O autógrafo, a resposta do Executivo, a apreciação do veto e a norma não são transições do rito: vivem
// em GET /proposicoes/:id/pos-aprovacao. Sem eles a linha do tempo parava na aprovação, e a matéria que já
// é lei parecia esquecida. Cada ato entra com a data em que ocorreu; ato sem data não entra.

export type AtoPosAprovacaoVista = { ocorridoEm: string; evento: string; quem: string };

const RESPOSTA_DO_EXECUTIVO: Record<string, string> = {
  sancionado: "Sancionada pelo Executivo",
  sancao_tacita: "Sanção tácita: o Executivo não respondeu no prazo",
  vetado: "Vetada pelo Executivo",
  veto_mantido: "Vetada pelo Executivo",
  veto_derrubado: "Vetada pelo Executivo",
};

const APRECIACAO_DO_VETO: Record<string, string> = {
  veto_mantido: "Veto mantido pela Câmara",
  veto_derrubado: "Veto derrubado pela Câmara",
};

const ESTADOS_COM_VETO = new Set(["vetado", "veto_mantido", "veto_derrubado"]);

export function derivarAtosPosAprovacao(pos: PosAprovacaoOut | null | undefined): AtoPosAprovacaoVista[] {
  const atos: AtoPosAprovacaoVista[] = [];
  const autografo = pos?.autografo;
  if (!autografo) return atos;
  atos.push({
    ocorridoEm: autografo.enviadoEm,
    evento: `Autógrafo nº ${formatarNumeroAutografo(autografo.numero, autografo.ano)} enviado ao Executivo`,
    quem: autografo.prazoRespostaEm
      ? `${autografo.destinatarioTexto} · resposta até ${dataDoPrazo(autografo.prazoRespostaEm)}`
      : autografo.destinatarioTexto,
  });
  const executivo = pos?.tramitacaoExecutiva;
  const resposta = executivo ? RESPOSTA_DO_EXECUTIVO[executivo.estado] : undefined;
  if (executivo && resposta && executivo.respondidoEm) {
    const tipoVeto =
      ESTADOS_COM_VETO.has(executivo.estado) && (executivo.vetoTipo === "total" || executivo.vetoTipo === "parcial")
        ? ` (veto ${executivo.vetoTipo})`
        : "";
    atos.push({ ocorridoEm: executivo.respondidoEm, evento: `${resposta}${tipoVeto}`, quem: "Executivo" });
  }
  const apreciacao = executivo ? APRECIACAO_DO_VETO[executivo.estado] : undefined;
  if (executivo && apreciacao && executivo.apreciadoEm) {
    atos.push({ ocorridoEm: executivo.apreciadoEm, evento: apreciacao, quem: "Plenário" });
  }
  const norma = pos?.norma;
  if (norma) {
    const numero = formatarNumeroNorma(norma.tipoNorma, norma.numero, norma.ano);
    atos.push({ ocorridoEm: norma.promulgadoEm, evento: `Promulgada como ${numero}`, quem: "Câmara" });
    if (norma.publicadoEm) {
      atos.push({
        ocorridoEm: norma.publicadoEm,
        evento: `${numero} publicada`,
        quem: norma.veiculoPublicacao ? `Em ${norma.veiculoPublicacao}` : "Veículo não informado",
      });
    }
  }
  return atos;
}

export type ItemLinhaDoTempoVista =
  | ({ tipo: "transicao" } & ItemTimelineVista)
  | ({ tipo: "ato" } & AtoPosAprovacaoVista);

function instante(iso: string): number {
  const t = Date.parse(iso);
  return Number.isNaN(t) ? 0 : t;
}

/** As transições do rito e os atos depois da aprovação numa só lista, do mais recente ao mais antigo. Compara o
 *  instante, não o texto: as duas fontes não escrevem a data do mesmo jeito (com e sem fuso). */
export function derivarLinhaDoTempo(
  tramitacao: HistoricoTramitacaoItemOut[],
  pos: PosAprovacaoOut | null | undefined,
): ItemLinhaDoTempoVista[] {
  const itens: ItemLinhaDoTempoVista[] = [
    ...derivarTimelineTramitacao(tramitacao).map((i) => ({ tipo: "transicao" as const, ...i })),
    ...derivarAtosPosAprovacao(pos).map((a) => ({ tipo: "ato" as const, ...a })),
  ];
  return itens.sort((a, b) => instante(b.ocorridoEm) - instante(a.ocorridoEm));
}

// ---------------------------------------------------------------------------
// Aba "Pareceres"
// ---------------------------------------------------------------------------

// Os 4 desfechos terminais cravados no eixo F (logic.clj `estados-parecer-terminais`) — o resto do
// vocabulário de `estado` é template-driven (aberto), então qualquer outro valor cai no fallback neutro.
const PARECER_ROTULO_POR_ESTADO: Record<string, string> = {
  aprovado: "Aprovado",
  rejeitado: "Rejeitado",
  prejudicado: "Prejudicado",
  prazo_vencido: "Prazo vencido",
};

// Exportados (Onda B Slice 5, parecer-vista.ts): os 4 desfechos terminais do parecer, reusados pelo
// editor de parecer pra saber quando travar a escrita — mesmo conjunto, nunca duplicado.
export const PARECER_APROVADOS = new Set(["aprovado"]);
export const PARECER_ARQUIVADOS = new Set(["rejeitado", "prejudicado", "prazo_vencido"]);

function categorizarParecer(estado: string): CategoriaSituacao {
  if (PARECER_APROVADOS.has(estado)) return "aprovada";
  if (PARECER_ARQUIVADOS.has(estado)) return "arquivada";
  return "tram"; // fail-closed: inclui os estados não-terminais template-driven (ex. "em_elaboracao")
}

/** A ficha passa a servir `relator-nome` (ADR-0019, Eixo 6). O contrato gerado ainda não o traz: o campo é opcional aqui, e
 *  some sozinho quando o contrato for regenerado. */
export type ParecerResumoComRelator = ParecerResumoOut & { relatorNome?: string | null };

export type ParecerVista = ParecerResumoComRelator & {
  rotuloEstado: string;
  categoria: CategoriaSituacao;
  // A aba imprimia `comissaoId` — um UUID por linha (defeito #11 do ledger, `MATA`). O rótulo vem
  // pronto daqui: o nome de verdade quando o backend resolve, o rótulo honesto quando não. O id
  // continua no objeto porque `key`/navegação precisam dele, mas nada o exibe.
  comissaoRotulo: string;
};

/** Quem relata, em palavras: o nome quando o servidor o resolve; "Relator designado" quando há relator sem nome resolvido (o id
 *  nunca vai à tela); null quando ainda não há relator (a secretaria pode designar). */
export function rotularRelator(p: { relatorId?: string | null; relatorNome?: string | null }): string | null {
  if (p.relatorNome) return p.relatorNome;
  return p.relatorId ? "Relator designado" : null;
}

export function derivarPareceres(pareceres: ParecerResumoComRelator[]): ParecerVista[] {
  return pareceres.map((p) => ({
    ...p,
    rotuloEstado: PARECER_ROTULO_POR_ESTADO[p.estado] ?? p.estado,
    categoria: categorizarParecer(p.estado),
    // `comissaoNome` vem resolvido pelo host (`resolver-comissoes`, §22.5.3) e é `null` quando o guard
    // ref não tem dono nesta Casa — o rótulo honesto cobre esse caso; o id nunca entra aqui.
    comissaoRotulo: rotularComissao(p.comissaoNome),
  }));
}

// ---------------------------------------------------------------------------
// Aba "Emendas"
// ---------------------------------------------------------------------------

// Espelha logic.clj `tipos-emenda` (§22.4 eixo D, enum fechado). Fora deste mapa: fallback pro valor cru.
const EMENDA_TIPO_ROTULO: Record<string, string> = {
  modificativa: "Modificativa",
  supressiva: "Supressiva",
  aditiva: "Aditiva",
  substitutiva_total: "Substitutiva total",
  substitutiva_parcial: "Substitutiva parcial",
  aglutinativa: "Aglutinativa",
  redacao: "Redação",
};

// Espelha logic.clj `estados-emenda` (enum fechado, ciclo universal — NÃO é template-driven, ao contrário
// de proposição/parecer). Ainda assim tratado fail-closed aqui (mesma disciplina do resto do view-model).
const EMENDA_ESTADO_ROTULO: Record<string, string> = {
  apresentada: "Apresentada",
  admitida: "Admitida",
  aprovada: "Aprovada",
  rejeitada: "Rejeitada",
  prejudicada: "Prejudicada",
  retirada: "Retirada",
};

export type EmendaVista = EmendaResumoOut & {
  rotuloTipo: string;
  rotuloEstado: string;
  categoria: CategoriaSituacao;
};

export function derivarEmendas(emendas: EmendaResumoOut[]): EmendaVista[] {
  return emendas.map((e) => ({
    ...e,
    rotuloTipo: EMENDA_TIPO_ROTULO[e.tipoEmenda] ?? e.tipoEmenda,
    rotuloEstado: EMENDA_ESTADO_ROTULO[e.estado] ?? e.estado,
    // categorizarSituacao já cobre "aprovada" e "rejeitada"/"prejudicada"/"retirada" nos seus sets
    // conhecidos (proposicoes-vista.ts) — o vocabulário de emenda coincide o bastante pra reusar sem
    // duplicar a categorização; "apresentada"/"admitida" caem no default neutro "tram".
    categoria: categorizarSituacao(e.estado),
  }));
}
