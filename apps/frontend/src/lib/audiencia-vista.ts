// View-model PURO da audiência pública (ADR-0021 Parte A) — sem React, testável isolado. Três consumidores: a tela
// da Mesa (/sessoes/[id]/audiencia), a página do portal (/portal/casa/[ente]/audiencias/[sessao]) e a área da
// cidadã (/meus-protocolos). O cronômetro da fala é o MESMO da tribuna (`cronometro.ts`): contagem regressiva a partir
// do tempo único da audiência, "último minuto" e "+excedido" — a audiência não pausa nem concede tempo extra (A3),
// então não há marcos: só o instante da chamada.

import { relogioDaFala, segundosDecorridos, tempoDaFala, type TempoDaFala } from "./cronometro";
import {
  FALA_COMO,
  corpoDaInscricaoCidada,
  corpoDaInscricaoPresencial,
  type AudienciaOut,
  type AudienciaPublicaOut,
  type FalaComo,
  type InscricaoOut,
} from "./contrato-audiencia";

/** Teto do tema da fala (ADR: `tema` ≤ 200), o mesmo do tema da audiência. */
export const LIMITE_TEMA = 200;
export const LIMITE_ENTIDADE = 200;
export const LIMITE_NOME = 200;

/** O tempo de fala em minutos que a Mesa escolhe: a faixa do banco (60..1800 s). */
export const TEMPO_FALA_MIN = 1;
export const TEMPO_FALA_MAX = 30;
export const TEMPO_FALA_PADRAO_MIN = 5;

export type Validado<T> = { ok: true; corpo: T } | { ok: false; campo: string; mensagem: string };

const ehFalaComo = (v: string): v is FalaComo => FALA_COMO.some((f) => f.valor === v);

function conferirComum(f: { falaComo: string; entidade: string; tema: string }): { campo: string; mensagem: string } | null {
  if (!ehFalaComo(f.falaComo)) return { campo: "fala-como", mensagem: "Escolha como você fala na audiência." };
  if (f.falaComo !== "individual") {
    const e = f.entidade.trim();
    if (!e) return { campo: "entidade", mensagem: "Diga qual entidade, conselho ou movimento você representa." };
    if (e.length > LIMITE_ENTIDADE) return { campo: "entidade", mensagem: `O nome da entidade passou de ${LIMITE_ENTIDADE} caracteres.` };
  }
  const t = f.tema.trim();
  if (!t) return { campo: "tema", mensagem: "Escreva, em uma frase, sobre o que pretende falar." };
  if (t.length > LIMITE_TEMA) return { campo: "tema", mensagem: `O tema passou de ${LIMITE_TEMA} caracteres. Resuma em uma frase.` };
  return null;
}

/** A inscrição da cidadã no portal. O aviso de publicidade é obrigatório: sem ele, nada vai ao servidor. */
export function validarInscricaoCidada(f: {
  falaComo: string;
  entidade: string;
  tema: string;
  ciente: boolean;
}): Validado<ReturnType<typeof corpoDaInscricaoCidada>> {
  const falta = conferirComum(f);
  if (falta) return { ok: false, ...falta };
  if (!f.ciente) {
    return {
      ok: false,
      campo: "ciente",
      mensagem: "Para se inscrever, confirme que está ciente de que a fala é pública e entra na ata e na transmissão.",
    };
  }
  return { ok: true, corpo: corpoDaInscricaoCidada({ falaComo: f.falaComo as FalaComo, entidade: f.entidade, tema: f.tema }) };
}

/** A inscrição que a Mesa faz no dia, para quem está presente: aqui o nome é digitado. */
export function validarInscricaoPresencial(f: {
  nome: string;
  falaComo: string;
  entidade: string;
  tema: string;
}): Validado<ReturnType<typeof corpoDaInscricaoPresencial>> {
  const nome = f.nome.trim();
  if (!nome) return { ok: false, campo: "nome", mensagem: "Escreva o nome de quem vai falar." };
  if (nome.length > LIMITE_NOME) return { ok: false, campo: "nome", mensagem: `O nome passou de ${LIMITE_NOME} caracteres.` };
  const falta = conferirComum(f);
  if (falta) return { ok: false, ...falta };
  return {
    ok: true,
    corpo: corpoDaInscricaoPresencial({ nome, falaComo: f.falaComo as FalaComo, entidade: f.entidade, tema: f.tema }),
  };
}

/** Minutos digitados pela Mesa → segundos do contrato, ou o motivo da recusa. */
export function tempoFalaEmSegundos(minutos: string): { ok: true; segundos: number } | { ok: false; mensagem: string } {
  const n = Number(minutos.trim().replace(",", "."));
  if (!minutos.trim() || !Number.isInteger(n) || n < TEMPO_FALA_MIN || n > TEMPO_FALA_MAX) {
    return { ok: false, mensagem: `O tempo de fala vai de ${TEMPO_FALA_MIN} a ${TEMPO_FALA_MAX} minutos, em minutos inteiros.` };
  }
  return { ok: true, segundos: n * 60 };
}

/** "300" → "5 min"; "90" → "1 min 30 s". */
export function rotuloTempoFala(segundos: number): string {
  const m = Math.floor(segundos / 60);
  const s = segundos % 60;
  if (m === 0) return `${s} s`;
  return s === 0 ? `${m} min` : `${m} min ${s} s`;
}

// ---- a fila da Mesa ----

export type FilaAudiencia = {
  /** Todas, em ordem de inscrição. */
  ordenadas: InscricaoOut[];
  /** Quem está com a palavra (no máximo uma). */
  falando: InscricaoOut | null;
  /** A próxima a ser chamada: a primeira `inscrita`. */
  proxima: InscricaoOut | null;
  /** Quantas ainda esperam a vez. */
  aguardando: number;
};

export function filaDaAudiencia(inscricoes: InscricaoOut[]): FilaAudiencia {
  const ordenadas = [...inscricoes].sort((a, b) => a.ordem - b.ordem);
  const esperando = ordenadas.filter((i) => i.estado === "inscrita");
  return {
    ordenadas,
    falando: ordenadas.find((i) => i.estado === "falando") ?? null,
    proxima: esperando[0] ?? null,
    aguardando: esperando.length,
  };
}

/** A Mesa só chama com a sessão aberta e ninguém na palavra (o servidor confere de novo: 409). */
export function podeChamar(a: Pick<AudienciaOut, "estado" | "inscricoes">): boolean {
  return a.estado === "aberta" && !a.inscricoes.some((i) => i.estado === "falando");
}

/** Inscrever presencialmente vale enquanto a audiência não terminou — mesmo com as inscrições do portal fechadas. */
export function podeInscreverPresencial(a: Pick<AudienciaOut, "estado">): boolean {
  return a.estado === "agendada" || a.estado === "aberta" || a.estado === "suspensa";
}

export type CronometroAudiencia = { decorrido: number; tempo: TempoDaFala; relogio: string };

/** O relógio da fala em curso: regressivo a partir do tempo único da audiência (o da tribuna, sem marcos). */
export function cronometroDaFala(inscricao: Pick<InscricaoOut, "chamadaEm">, tempoFalaSegundos: number, agoraMs: number): CronometroAudiencia {
  const decorrido = inscricao.chamadaEm ? segundosDecorridos(inscricao.chamadaEm, [], agoraMs) : 0;
  const tempo = tempoDaFala(tempoFalaSegundos, [], decorrido);
  return { decorrido, tempo, relogio: relogioDaFala(tempo, decorrido) };
}

/** Quem fala, como aparece na fila e no portal: "Ana Souza · Associação do Bairro Ellery". */
export function quemFala(i: { nome: string; falaComo: string; entidade: string | null }): string {
  return i.falaComo !== "individual" && i.entidade ? `${i.nome} · ${i.entidade}` : i.nome;
}

// ---- o portal ----

export type SituacaoInscricaoPortal = "aberta" | "fechada" | "realizada" | "nao_realizada";

/** O que o cartão "Quero falar" mostra. `inscricoesAbertas` já vem efetivo do servidor (flag ∧ estado). */
export function situacaoDaInscricao(a: Pick<AudienciaPublicaOut, "estado" | "inscricoesAbertas">): SituacaoInscricaoPortal {
  if (a.estado === "encerrada" || a.estado === "arquivada") return "realizada";
  if (a.estado === "nao_realizada") return "nao_realizada";
  return a.inscricoesAbertas ? "aberta" : "fechada";
}

const MESES = ["jan", "fev", "mar", "abr", "mai", "jun", "jul", "ago", "set", "out", "nov", "dez"];
const DIAS = ["Domingo", "Segunda", "Terça", "Quarta", "Quinta", "Sexta", "Sábado"];

function data(iso: string | null | undefined): Date | null {
  if (!iso) return null;
  const d = new Date(iso);
  return Number.isNaN(d.getTime()) ? null : d;
}

/** "9h" / "14h30" — a hora como o design escreve. */
export function horaCurta(iso: string | null | undefined): string | null {
  const d = data(iso);
  if (!d) return null;
  const m = d.getMinutes();
  return `${d.getHours()}h${m ? String(m).padStart(2, "0") : ""}`;
}

/** O quadradinho da lista: {dia: "18", mes: "jun"}. */
export function diaEMes(iso: string | null | undefined): { dia: string; mes: string } | null {
  const d = data(iso);
  return d ? { dia: String(d.getDate()).padStart(2, "0"), mes: MESES[d.getMonth()] } : null;
}

/** "Quinta, 11/06/2026 · 9h" — o "quando" do topo da página. Sem data: null (a tela diz "data a definir"). */
export function quandoPorExtenso(iso: string | null | undefined): string | null {
  const d = data(iso);
  if (!d) return null;
  const dd = String(d.getDate()).padStart(2, "0");
  const mm = String(d.getMonth() + 1).padStart(2, "0");
  return `${DIAS[d.getDay()]}, ${dd}/${mm}/${d.getFullYear()} · ${horaCurta(iso)}`;
}
