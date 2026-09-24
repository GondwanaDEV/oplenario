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

/** GET /sessoes/:id/pauta — oplenario.sessoes.adapters.out.pauta/pauta->wire (internos filtrados). */
export interface PautaItemOut {
  id: string;
  fase: string; // logic/fases-pauta
  "tipo-item": string; // logic/tipos-item-pauta
  "proposicao-id"?: string; // presente só p/ "proposicao" (chave omitida nos demais)
  /** Modo TV (docs/22): o resumo da matéria. Enriquecimento — ausente se a leitura em legislativo não
   * respondeu ou a matéria não é do tenant; `proposicao-id` continua sendo a referência. */
  proposicao?: { tipo: string; ano: number; sequencial: number; ementa: string } | null;
  "texto-descricao"?: string; // presente p/ os demais tipos (chave omitida em "proposicao")
  ordem: number;
}

export interface PautaOut {
  "sessao-id": string;
  itens: PautaItemOut[];
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

// ---- votação ao vivo (3 eventos; legislativo.events.votacao + projeção §22.6 sigilo) ----

/** votacao.aberta — a Mesa abre a votação sobre a matéria. `modalidade` diz ao painel se mostra placar nominal
 * (quem votou o quê) ou só contador (secreta). */
export interface VotacaoAberta {
  "votacao-id": string;
  "sessao-id": string;
  "objeto-tipo": string;
  "objeto-id": string;
  modalidade: string; // nominal | secreta | simbolica (no fio do plenário: nominal | secreta)
  "quorum-tipo": string;
  "pauta-item-id"?: string | null;
}

/** voto.registrado — UNIÃO DISCRIMINADA por `modalidade` (sigilo §22.6 cravado no contrato do backend).
 * NOMINAL carrega vereador+voto (público no placar nominal); SECRETA é um TICK anônimo (só o contador). */
export type VotoRegistrado =
  | { "votacao-id": string; "sessao-id": string; modalidade: "nominal"; "vereador-id": string; voto: string }
  | { "votacao-id": string; "sessao-id": string; modalidade: "secreta" };

/** votacao.encerrada — o resultado AGREGADO (público mesmo na secreta). Totais ausentes na 'simbolica'. */
export interface VotacaoEncerrada {
  "votacao-id": string;
  "sessao-id": string;
  resultado: string; // aprovada | rejeitada
  modalidade?: string;
  "total-sim"?: number | null;
  "total-nao"?: number | null;
  "total-abstencao"?: number | null;
  "base-membros"?: number | null;
}

/** tempo-real.lacuna — sinal SINTÉTICO (nunca um evento de domínio): o backplane Valkey da CanalStore o
 * injeta quando o replay encontra uma entrada corrompida no meio do stream (frente 'truncamento-familia',
 * sitio (d)). `dados` é sempre `{}` — nunca carrega o payload corrompido. */
export type LacunaDetectada = Record<string, never>;

/** Evento normalizado do canal plenário: o `tipo` discrimina o `dados`; `seq` = posição monotônica (Last-Event-ID). */
export type EventoPlenario =
  | { tipo: "sessao.transicionou"; seq: number; dados: SessaoTransicionou }
  | { tipo: "presenca.registrada"; seq: number; dados: PresencaRegistrada }
  | { tipo: "fala.iniciada"; seq: number; dados: FalaIniciada }
  | { tipo: "fala.encerrada"; seq: number; dados: FalaEncerrada }
  | { tipo: "fala.cronometro"; seq: number; dados: FalaCronometro }
  | { tipo: "inscricao.registrada"; seq: number; dados: InscricaoRegistrada }
  | { tipo: "inscricao.desistida"; seq: number; dados: InscricaoDesistida }
  | { tipo: "votacao.aberta"; seq: number; dados: VotacaoAberta }
  | { tipo: "voto.registrado"; seq: number; dados: VotoRegistrado }
  | { tipo: "votacao.encerrada"; seq: number; dados: VotacaoEncerrada }
  | { tipo: "tempo-real.lacuna"; seq: number; dados: LacunaDetectada };

/** Os 11 tipos que o cliente do painel reconhece — espelho de
 * oplenario.tempo-real.canais/tipos-emitidos-ao-cliente (fonte única no backend: os 10 roteados ao painel
 * + `tempo-real.lacuna`, o sinal sintético de buraco de replay). */
export const TIPOS_PLENARIO = [
  "sessao.transicionou",
  "presenca.registrada",
  "fala.iniciada",
  "fala.encerrada",
  "fala.cronometro",
  "inscricao.registrada",
  "inscricao.desistida",
  "votacao.aberta",
  "voto.registrado",
  "votacao.encerrada",
  "tempo-real.lacuna",
] as const;
