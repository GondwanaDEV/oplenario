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
