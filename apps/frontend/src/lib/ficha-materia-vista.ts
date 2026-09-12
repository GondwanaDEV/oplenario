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
import { derivarTramitacao } from "./tramitacao-vista";
import { categorizarSituacao, type CategoriaSituacao } from "./proposicoes-vista";
import type {
  FichaMateriaOut,
  HistoricoTramitacaoItemOut,
  EmendaResumoOut,
  ParecerResumoOut,
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

export function derivarDadosMateria(ficha: FichaMateriaOut): DadosMateriaVista {
  const { rotuloSituacao } = derivarTramitacao(ficha.proposicao.estado);
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
      rotuloDe: derivarTramitacao(item.deEstado).rotuloSituacao,
      rotuloPara: derivarTramitacao(item.paraEstado).rotuloSituacao,
    }));
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

export type ParecerVista = ParecerResumoOut & {
  rotuloEstado: string;
  categoria: CategoriaSituacao;
  // A aba imprimia `comissaoId` — um UUID por linha (defeito #11 do ledger, `MATA`). O rótulo vem
  // pronto daqui: o nome de verdade quando o backend resolve, o rótulo honesto quando não. O id
  // continua no objeto porque `key`/navegação precisam dele, mas nada o exibe.
  comissaoRotulo: string;
};

export function derivarPareceres(pareceres: ParecerResumoOut[]): ParecerVista[] {
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
