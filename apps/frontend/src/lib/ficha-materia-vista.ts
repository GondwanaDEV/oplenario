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
  apresentadaEm: string;
  ultimaAcaoEm: string;
};

export function derivarDadosMateria(ficha: FichaMateriaOut): DadosMateriaVista {
  const { rotuloSituacao } = derivarTramitacao(ficha.proposicao.estado);
  const ordenado = [...ficha.tramitacao].sort((a, b) => a.ocorridoEm.localeCompare(b.ocorridoEm));
  return {
    situacao: rotuloSituacao,
    apensadosTotal: ficha.apensadas.length,
    // primeira/última transição registrada; sem histórico, cai honestamente pra atualizadoEm (nunca
    // inventa uma data de "apresentação" que não temos).
    apresentadaEm: ordenado[0]?.ocorridoEm ?? ficha.proposicao.atualizadoEm,
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

const PARECER_APROVADOS = new Set(["aprovado"]);
const PARECER_ARQUIVADOS = new Set(["rejeitado", "prejudicado", "prazo_vencido"]);

function categorizarParecer(estado: string): CategoriaSituacao {
  if (PARECER_APROVADOS.has(estado)) return "aprovada";
  if (PARECER_ARQUIVADOS.has(estado)) return "arquivada";
  return "tram"; // fail-closed: inclui os estados não-terminais template-driven (ex. "em_elaboracao")
}

export type ParecerVista = ParecerResumoOut & {
  rotuloEstado: string;
  categoria: CategoriaSituacao;
};

export function derivarPareceres(pareceres: ParecerResumoOut[]): ParecerVista[] {
  return pareceres.map((p) => ({
    ...p,
    rotuloEstado: PARECER_ROTULO_POR_ESTADO[p.estado] ?? p.estado,
    categoria: categorizarParecer(p.estado),
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
