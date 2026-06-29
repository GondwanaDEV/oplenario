// Contrato externo do backend (§22.10 wire/out) — espelho TS, hand-rolled neste 1º corte do track FE.
// FONTE: oplenario.sessoes.wire.out (SessaoOut) + oplenario.sessoes.events.* (payloads SSE) +
// oplenario.tempo-real.wire.out.evento-sse (envelope). As CHAVES são kebab-case porque é o que o
// jsonista emite no fio (keyword :sessao-id -> "sessao-id"); mantemos kebab fiel ao wire, sem camada
// de transformação que poderia driftar. Quando o codegen Malli→TS (Eixo 8) chegar, ele substitui este
// arquivo por fatia vertical — a forma aqui é a mesma que ele vai gerar.

/** GET /sessoes/:id — oplenario.sessoes.adapters.out.sessao/sessao->wire (lock-version e ente-id filtrados). */
export interface SessaoOut {
  id: string;
  "sessao-legislativa-id": string;
  "tipo-sessao": string;
  "numero-sequencial": number;
  estado: string;
  modalidade: string;
  delibera: boolean;
  "transmite-publica": boolean;
  "gera-ata-regimental": boolean;
  "permite-voto-secreto": boolean;
  "permite-modalidade-remota": boolean;
  "agendada-para": string | null;
  "aberta-em": string | null;
  "encerrada-em": string | null;
  "motivo-nao-realizada": string | null;
}

// ---- payloads dos 7 eventos do canal plenário (= o :dados de cada evento, JSON kebab-case) ----

export interface SessaoTransicionou {
  "sessao-id": string;
  de: string;
  para: string; // = um de estados-sessao
  "ator-id"?: string | null;
}

export interface PresencaRegistrada {
  "sessao-id": string;
  "vereador-id": string;
  tipo: string; // entrada | saida | retorno | mudanca_modalidade (logic/tipos-evento-presenca)
  modalidade: string;
  fonte: string;
  "ocorrido-em": string; // ISO-8601
}

export interface FalaIniciada {
  "fala-id": string;
  "sessao-id": string;
  "orador-id": string;
  "tipo-fala": string;
  fase: string;
  "iniciou-em": string; // ISO-8601 — âncora do cronômetro client-side
  "inscricao-id"?: string | null;
}

export interface FalaEncerrada {
  "fala-id": string;
  "sessao-id": string;
  "tempo-segundos": number;
  "encerrou-em": string;
}

export interface FalaCronometro {
  "fala-id": string;
  "sessao-id": string;
  tipo: string; // pausada | retomada | aparte_concedido | tempo_adicional_concedido
  "ocorrido-em": string;
  "segundos-adicionais"?: number | null;
}

export interface InscricaoRegistrada {
  "inscricao-id": string;
  "sessao-id": string;
  "vereador-id": string;
  "origem-inscricao": string;
  fase: string;
  ordem: number;
}

export interface InscricaoDesistida {
  "inscricao-id": string;
  "sessao-id": string;
}

/** Evento normalizado do canal plenário: o `tipo` discrimina o `dados`; `seq` = posição monotônica (Last-Event-ID). */
export type EventoPlenario =
  | { tipo: "sessao.transicionou"; seq: number; dados: SessaoTransicionou }
  | { tipo: "presenca.registrada"; seq: number; dados: PresencaRegistrada }
  | { tipo: "fala.iniciada"; seq: number; dados: FalaIniciada }
  | { tipo: "fala.encerrada"; seq: number; dados: FalaEncerrada }
  | { tipo: "fala.cronometro"; seq: number; dados: FalaCronometro }
  | { tipo: "inscricao.registrada"; seq: number; dados: InscricaoRegistrada }
  | { tipo: "inscricao.desistida"; seq: number; dados: InscricaoDesistida };

/** Os 7 tipos roteados ao painel — espelho de oplenario.tempo-real.canais/tipos-plenario (fonte única no backend). */
export const TIPOS_PLENARIO = [
  "sessao.transicionou",
  "presenca.registrada",
  "fala.iniciada",
  "fala.encerrada",
  "fala.cronometro",
  "inscricao.registrada",
  "inscricao.desistida",
] as const;
