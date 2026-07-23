// formatarData — util compartilhado (ISO -> pt-BR dd/mm/aaaa), consolidado a partir de 4 cópias
// idênticas (ficha-cabecalho.tsx, dados-materia-card.tsx, ficha-materia-tabs.tsx, secao-ficha.tsx) achadas
// no review da fatia Onda B Slice 3 (ficha-materia). `Intl.DateTimeFormat` fica em escopo de módulo — é
// caro de construir e não muda entre chamadas, então uma única instância é compartilhada por todo o app
// em vez de recriada a cada invocação/render.

const FORMATO_DATA_BR = new Intl.DateTimeFormat("pt-BR", {
  day: "2-digit",
  month: "2-digit",
  year: "numeric",
});

export function formatarData(iso: string): string {
  try {
    return FORMATO_DATA_BR.format(new Date(iso));
  } catch {
    return iso;
  }
}

// formatarHora — extraído pro Livro do Protocolo Geral (Onda B Slice 6, coluna "Hora"): HH:mm, 24h.
const FORMATO_HORA_BR = new Intl.DateTimeFormat("pt-BR", {
  hour: "2-digit",
  minute: "2-digit",
  hour12: false,
});

export function formatarHora(iso: string): string {
  try {
    return FORMATO_HORA_BR.format(new Date(iso));
  } catch {
    return iso;
  }
}

// formatarDataSimples — Onda E fatia 2 (perfil público do vereador). SÓ para as constantes de deploy
// DATE-ONLY do contrato (`acervo-com-elo-de-autoria-desde`, `presenca-projetada-desde`: "2026-07-20").
// NÃO usar `formatarData` aqui: `new Date("2026-07-20")` é parseado como MEIA-NOITE UTC e, formatado no
// fuso do beachhead (America/Fortaleza, UTC−3), volta um dia — a página publicaria "19/07/2026" como marco
// do registro eletrônico. O container roda em UTC, então o bug passaria batido no teste e só apareceria no
// cidadão. Numa página que declara publicamente o corte do acervo, errar um dia é errar o corte. Por isso
// aqui não há `Date` nenhum: é recorte textual puro, fuso-independente por construção.
// Fail-closed: formato inesperado sai CRU, nunca lança, nunca inventa data.
export function formatarDataSimples(iso: string): string {
  const m = /^(\d{4})-(\d{2})-(\d{2})$/.exec(iso);
  return m ? `${m[3]}/${m[2]}/${m[1]}` : iso;
}

// formatarDiaSemana — Onda C Slice C2 (pauta-convocacao): nome do dia da semana por extenso, pt-BR.
const FORMATO_DIA_SEMANA_BR = new Intl.DateTimeFormat("pt-BR", { weekday: "long" });

export function formatarDiaSemana(iso: string): string {
  try {
    return FORMATO_DIA_SEMANA_BR.format(new Date(iso));
  } catch {
    return iso;
  }
}
