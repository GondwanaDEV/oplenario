// View-model puro do pós-aprovação (Onda B Slice 7, autógrafo + sanção/veto) — traduz AutografoOut/
// TramitacaoExecutivaOut (wire, camelizado) pro que o pipeline de 5 etapas e o anel de prazo mostram.
// Porte de produto/design-system/o-plenario/telas/pos-aprovacao.html (pipeline-stepper + anel).
//
// VOCABULÁRIO REAL (confirmado em apps/backend/src/oplenario/legislativo/logic.clj/estados-resposta-
// executivo/estados-apreciacao-veto): `tramitacao_executiva.estado` é ENUM FECHADO em código (ao
// contrário do `estado` livre de proposição/tramitacao-vista.ts) — aguardando -> {sancionado|
// sancao_tacita|vetado} -> (só p/ vetado) {veto_mantido|veto_derrubado}. Por isso este mapa PODE ser
// fechado (sem fallback fail-closed pro rótulo cru precisar cobrir vocabulário de template por câmara).
//
// Passos 4 (Promulgação) e 5 (Publicação) SEMPRE renderizam como etapas futuras estáticas — spec §1 "sem
// dado vivo nesta fatia" (legislativo.norma/artefato de publicação são domínios próprios, zero
// acoplamento além de "vêm depois").

import { formatarData } from "./formatar-data";
import type { AutografoOut, TramitacaoExecutivaOut } from "./contrato-legislativo.gen";

export type SituacaoEtapa = "feita" | "atual" | "futura";

export type EtapaPipeline = {
  rotulo: string;
  detalhe: string;
  situacao: SituacaoEtapa;
};

const ROTULO_DESFECHO_POR_ESTADO: Record<string, string> = {
  sancionado: "Sancionado",
  sancao_tacita: "Sanção tácita",
  vetado: "Vetado",
  veto_mantido: "Veto mantido pela Câmara",
  veto_derrubado: "Veto derrubado pela Câmara",
};

const ESTADOS_RESOLVIDOS = new Set(Object.keys(ROTULO_DESFECHO_POR_ESTADO));

// Estado da tramitação, mas nil-ável na leitura composta: `PosAprovacaoOut.tramitacaoExecutiva` só é nulo
// no instante teórico entre gerar! e iniciar! (nunca aparece por fora — Repo compõe os dois numa única
// tx, spec §3.1) — mesmo assim tratamos como "aguardando" por segurança (nunca lança).
export function derivarPipeline(
  autografo: AutografoOut,
  tramitacaoExecutiva: TramitacaoExecutivaOut | null,
): EtapaPipeline[] {
  const estado = tramitacaoExecutiva?.estado ?? "aguardando";
  const resolvida = ESTADOS_RESOLVIDOS.has(estado);
  const enviadoEm = formatarData(autografo.enviadoEm);

  const etapaAutografo: EtapaPipeline = {
    rotulo: "Autógrafo",
    detalhe: `${enviadoEm} · gerado`,
    situacao: "feita",
  };

  const etapaExecutivo: EtapaPipeline = {
    rotulo: "No Executivo",
    detalhe: resolvida ? `recebido em ${enviadoEm}` : `desde ${enviadoEm}`,
    situacao: resolvida ? "feita" : "atual",
  };

  const respondidoEm = tramitacaoExecutiva?.respondidoEm ? formatarData(tramitacaoExecutiva.respondidoEm) : null;
  const etapaSancao: EtapaPipeline = {
    rotulo: "Sanção ou veto",
    detalhe: resolvida
      ? `${ROTULO_DESFECHO_POR_ESTADO[estado]}${respondidoEm ? " · " + respondidoEm : ""}`
      : "aguarda",
    situacao: resolvida ? "feita" : "futura",
  };

  const etapaPromulgacao: EtapaPipeline = { rotulo: "Promulgação", detalhe: "—", situacao: "futura" };
  const etapaPublicacao: EtapaPipeline = { rotulo: "Publicação", detalhe: "vira lei", situacao: "futura" };

  return [etapaAutografo, etapaExecutivo, etapaSancao, etapaPromulgacao, etapaPublicacao];
}

// Autógrafo nº 022/2026 (padding de 3 dígitos — mesmo espírito de formatarNumeroProtocolo em
// expediente-vista.ts, largura própria do mockup-fonte).
export function formatarNumeroAutografo(numero: number, ano: number): string {
  return `${String(numero).padStart(3, "0")}/${ano}`;
}

export type CategoriaPrazo = "urgente" | "atencao" | "tranquilo";

export type PrazoExecutivo =
  | { estado: "sem-prazo" }
  | { estado: "vencido"; diasVencidos: number }
  | { estado: "em-curso"; diasRestantes: number; diasTotal: number; categoria: CategoriaPrazo };

const DIA_MS = 24 * 60 * 60 * 1000;

// Anel de prazo calculado 100% client-side a partir de dado JÁ REAL (`autografo.enviadoEm` = início da
// contagem, mesma tx que abre a tramitação executiva; `autografo.prazoRespostaEm` = fim) — SEM tabela
// nova, SEM worker (spec §2, correção da pesquisa: a generalização polimórfica de `prazo_dominio_ativo`
// não é gatilho desta fatia). `agora` injetável só para teste determinístico.
export function derivarPrazoExecutivo(autografo: AutografoOut, agora: Date = new Date()): PrazoExecutivo {
  if (!autografo.prazoRespostaEm) return { estado: "sem-prazo" };

  const inicioMs = new Date(autografo.enviadoEm).getTime();
  const fimMs = new Date(autografo.prazoRespostaEm).getTime();
  const agoraMs = agora.getTime();

  if (agoraMs >= fimMs) {
    return { estado: "vencido", diasVencidos: Math.max(0, Math.round((agoraMs - fimMs) / DIA_MS)) };
  }

  const diasRestantes = Math.max(0, Math.ceil((fimMs - agoraMs) / DIA_MS));
  // diasTotal nunca menor que diasRestantes (guarda contra relógio/janela estranhos — arcoDashoffset já
  // clipa a fração em [0,1], mas um diasTotal < diasRestantes inverteria a leitura visual do anel).
  const diasTotal = Math.max(diasRestantes, Math.ceil((fimMs - inicioMs) / DIA_MS));
  const categoria: CategoriaPrazo = diasRestantes <= 2 ? "urgente" : diasRestantes <= 5 ? "atencao" : "tranquilo";
  return { estado: "em-curso", diasRestantes, diasTotal, categoria };
}
