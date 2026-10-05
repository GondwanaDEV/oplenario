// Lógica pura dos ATOS DA OPERAÇÃO INICIADOS SEM DESFECHO REGISTRADO, no console do operador (ADR-0017, adendo de
// 05/10/2026, "tentativa antes, desfecho depois"; a trilha da Casa diz o mesmo em `trilha-auditoria-vista.ts`).
//
// O que o servidor devolve (`GET /operacao/atos-sem-desfecho`): as tentativas da Operação — entrar no console, definir o
// orçamento de IA de uma Casa, reaplicar o login — que a corrente selada registrou no começo e das quais não guardou
// o fim. O ato pode não ter acontecido, ou ter acontecido e o registro do desfecho ter caído: quem confere é a pessoa.
// A tela é só leitura, e só aparece quando há item (não existe "tudo certo" aqui: o silêncio é o estado normal).
//
// O nome do ato em palavras é o de `rotuloAcao` (use-operacao.ts), o mesmo da lista de atuação da ficha da Casa.

export type AtoSemDesfecho = {
  id: string;
  em: string;
  acao: string;
  /** O nome do operador. Nulo quando não há pessoa (linha de comando) ou o cadastro dele não foi achado. */
  operador: string | null;
  /** `linha-de-comando` quando o ato veio do comando da Operação, sem pessoa. */
  origem: string | null;
  enteId: string | null;
  casaNome: string | null;
};

export type AtosSemDesfecho = {
  /** Quanto tempo a tentativa precisa ter para ser acusada: mais nova que isto ainda pode estar em curso. */
  toleranciaSegundos: number;
  limite: number;
  total: number;
  /** Há mais atos do que a lista mostra (a lista traz os mais recentes). */
  truncado: boolean;
  atos: AtoSemDesfecho[];
};

/** A resposta do servidor só vale se tiver a forma esperada; qualquer outra coisa não vira "nenhum ato". */
export function lerAtosSemDesfecho(d: unknown): AtosSemDesfecho | null {
  if (!d || typeof d !== "object") return null;
  const r = d as Partial<AtosSemDesfecho>;
  if (!Array.isArray(r.atos) || typeof r.total !== "number") return null;
  return {
    toleranciaSegundos: typeof r.toleranciaSegundos === "number" ? r.toleranciaSegundos : 120,
    limite: typeof r.limite === "number" ? r.limite : r.atos.length,
    total: r.total,
    truncado: r.truncado === true,
    atos: r.atos,
  };
}

const NUMERO = new Intl.NumberFormat("pt-BR");

/** "1 ato iniciado sem desfecho registrado" / "3 atos iniciados sem desfecho registrado". */
export function tituloDosAtos(total: number): string {
  return total === 1
    ? "1 ato iniciado sem desfecho registrado"
    : `${NUMERO.format(total)} atos iniciados sem desfecho registrado`;
}

/** "Mostrando os 50 mais recentes de 83." — só quando a lista foi cortada. */
export function avisoDeCorte(a: Pick<AtosSemDesfecho, "truncado" | "total" | "atos">): string | null {
  return a.truncado ? `Mostrando os ${NUMERO.format(a.atos.length)} mais recentes de ${NUMERO.format(a.total)}.` : null;
}

/** A janela de tolerância em palavras: 120 → "2 minutos", 60 → "1 minuto", 90 → "90 segundos". */
export function toleranciaEmPalavras(segundos: number): string {
  if (segundos >= 60 && segundos % 60 === 0) {
    const m = segundos / 60;
    return m === 1 ? "1 minuto" : `${m} minutos`;
  }
  return segundos === 1 ? "1 segundo" : `${segundos} segundos`;
}

/** Quem iniciou o ato. Nunca "pela própria câmara": estes atos são da Operação. */
export function quemIniciou(a: Pick<AtoSemDesfecho, "operador" | "origem">): string {
  if (a.operador) return a.operador;
  return a.origem === "linha-de-comando" ? "linha de comando da Operação" : "operador não identificado";
}

/** A Câmara pelo nome. Sem Câmara = ato da Operação em si (a entrada no console), e a linha não diz nada dela. */
export function camaraDoAto(a: Pick<AtoSemDesfecho, "enteId" | "casaNome">): string | null {
  if (a.casaNome) return a.casaNome;
  return a.enteId ? "Câmara fora do registro" : null;
}

// O console da Operação é de quem opera a plataforma, e a plataforma conta o tempo em Fortaleza (como o resto do
// produto: observabilidade da IA, faixa de acesso restrito). Sem `timeZone` o Intl usaria o fuso do navegador, e no
// render do servidor (UTC) o mesmo ato mudaria de dia.
const QUANDO = new Intl.DateTimeFormat("pt-BR", {
  day: "2-digit", month: "2-digit", year: "numeric", hour: "2-digit", minute: "2-digit", hourCycle: "h23",
  timeZone: "America/Fortaleza",
});

/** "05/10/2026 09:31" no fuso de Fortaleza. */
export function quandoIniciou(iso: string): string {
  const d = new Date(iso);
  return Number.isNaN(d.getTime()) ? "data não registrada" : QUANDO.format(d).replace(",", "");
}
