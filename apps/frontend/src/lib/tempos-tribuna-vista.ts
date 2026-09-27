// Lógica pura da tela "Tempos da tribuna" (secretaria): a tabela de tempos regimentais da Casa como a pessoa a
// edita. Cada TIPO DE FALA tem um tempo padrão ("em qualquer fase") e, opcionalmente, um tempo diferente por
// FASE — a linha da fase vence o padrão ao iniciar a fala (backend: `logic/escolher-tempo-regimental`). Campo
// vazio = sem limite (a fala só conta o tempo, sem campainha).
//
// O tempo é digitado como "3" (minutos) ou "3:00" (minutos:segundos). A tabela vai INTEIRA ao servidor
// (PUT /tempos-regimentais): o que está vazio não vira linha, e some da tabela da Casa.
//
// Vocabulários da FONTE — `sessoes/logic.clj` (`tipos-fala`, `fases-pauta`). O teto de 60 min é o mesmo de
// `logic/tempo-regimental-maximo-segundos` (defesa contra digitar 3000 no lugar de 30).

import type { TempoRegimentalOut } from "./contrato-sessoes.gen";

export const TIPOS_FALA_TEMPO = [
  "principal",
  "aparte",
  "pela_ordem",
  "questao_de_ordem",
  "explicacao_pessoal",
  "comunicado",
] as const;

export const FASES_TEMPO = [
  "expediente",
  "grande_expediente",
  "ordem_do_dia",
  "explicacoes_pessoais",
  "tribuna_livre_cidadao",
] as const;

export const TEMPO_MAXIMO_SEGUNDOS = 3600;

export type TipoFalaTempo = (typeof TIPOS_FALA_TEMPO)[number];
export type FaseTempo = (typeof FASES_TEMPO)[number];

export type RascunhoTipo = { padrao: string; referencia: string; porFase: Record<FaseTempo, string> };
export type RascunhoTempos = Record<TipoFalaTempo, RascunhoTipo>;

/** A linha que vai ao servidor (camelCase; o hook converte para kebab). */
export type ItemTempo = Pick<TempoRegimentalOut, "fase" | "tipoFala" | "segundos" | "referenciaNormativa">;

export type LeituraTempo = { ok: true; segundos: number | null } | { ok: false; erro: string };

/** "3" → 180, "1:30" → 90, "" → null (sem limite). */
export function lerTempo(texto: string): LeituraTempo {
  const t = texto.trim();
  if (t === "") return { ok: true, segundos: null };
  const m = /^(\d{1,2})(?::(\d{2}))?$/.exec(t);
  if (!m) return { ok: false, erro: "Use minutos, como 3, ou minutos:segundos, como 1:30." };
  const minutos = Number(m[1]);
  const segundos = m[2] === undefined ? 0 : Number(m[2]);
  if (segundos >= 60) return { ok: false, erro: "Os segundos vão de 00 a 59." };
  const total = minutos * 60 + segundos;
  if (total <= 0) return { ok: false, erro: "O tempo precisa ser maior que zero. Deixe vazio para sem limite." };
  if (total > TEMPO_MAXIMO_SEGUNDOS) return { ok: false, erro: "O limite é de 60 minutos por fala." };
  return { ok: true, segundos: total };
}

/** 180 → "3:00"; null → "". */
export function textoDoTempo(segundos: number | null): string {
  if (segundos == null) return "";
  const m = Math.floor(segundos / 60);
  const s = segundos % 60;
  return `${m}:${String(s).padStart(2, "0")}`;
}

function faseVazia(): Record<FaseTempo, string> {
  return Object.fromEntries(FASES_TEMPO.map((f) => [f, ""])) as Record<FaseTempo, string>;
}

/** A tabela do servidor → o estado do formulário (todos os tipos e fases presentes, vazios quando sem linha). */
export function rascunhoDe(itens: readonly ItemTempo[]): RascunhoTempos {
  const r = Object.fromEntries(
    TIPOS_FALA_TEMPO.map((t) => [t, { padrao: "", referencia: "", porFase: faseVazia() }]),
  ) as RascunhoTempos;
  for (const it of itens) {
    const tipo = r[it.tipoFala as TipoFalaTempo];
    if (!tipo) continue; // vocabulário novo do servidor que a tela ainda não conhece: não inventa linha
    if (it.fase == null) {
      tipo.padrao = textoDoTempo(it.segundos);
      tipo.referencia = it.referenciaNormativa ?? "";
    } else if ((FASES_TEMPO as readonly string[]).includes(it.fase)) {
      tipo.porFase[it.fase as FaseTempo] = textoDoTempo(it.segundos);
    }
  }
  return r;
}

/** Chave do campo com erro: "<tipo>:padrao" ou "<tipo>:<fase>". */
export type ErrosTempos = Record<string, string>;

/** O formulário → a tabela que vai ao servidor, ou os erros por campo. */
export function itensDe(r: RascunhoTempos): { ok: true; itens: ItemTempo[] } | { ok: false; erros: ErrosTempos } {
  const itens: ItemTempo[] = [];
  const erros: ErrosTempos = {};
  for (const tipo of TIPOS_FALA_TEMPO) {
    const linha = r[tipo];
    const padrao = lerTempo(linha.padrao);
    if (!padrao.ok) erros[`${tipo}:padrao`] = padrao.erro;
    else if (padrao.segundos != null) {
      const ref = linha.referencia.trim();
      itens.push({ fase: null, tipoFala: tipo, segundos: padrao.segundos, referenciaNormativa: ref || null });
    }
    for (const fase of FASES_TEMPO) {
      const lido = lerTempo(linha.porFase[fase]);
      if (!lido.ok) erros[`${tipo}:${fase}`] = lido.erro;
      else if (lido.segundos != null)
        itens.push({ fase, tipoFala: tipo, segundos: lido.segundos, referenciaNormativa: null });
    }
  }
  return Object.keys(erros).length > 0 ? { ok: false, erros } : { ok: true, itens };
}

function normalizar(r: RascunhoTempos): string {
  // compara o SIGNIFICADO ("3" e "3:00" são o mesmo tempo); campo inválido compara pelo texto
  const norm = (t: string) => {
    const l = lerTempo(t);
    return l.ok ? String(l.segundos) : `!${t.trim()}`;
  };
  return JSON.stringify(
    TIPOS_FALA_TEMPO.map((t) => [
      norm(r[t].padrao),
      r[t].referencia.trim(),
      FASES_TEMPO.map((f) => norm(r[t].porFase[f])),
    ]),
  );
}

/** Há diferença (de significado) entre o que está na tela e o que está salvo? */
export function alterado(atual: RascunhoTempos, original: RascunhoTempos): boolean {
  return normalizar(atual) !== normalizar(original);
}

/** Algum tempo por fase preenchido? (abre o detalhe por fase já na carga) */
export function temTempoPorFase(t: RascunhoTipo): boolean {
  return FASES_TEMPO.some((f) => t.porFase[f].trim() !== "");
}
