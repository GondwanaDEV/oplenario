// Lógica pura das NORMAS DE REFERÊNCIA (Faixa B / B.4, ADR-0011): a secretaria importa o texto da LOM, do Regimento
// ou de uma lei; o sistema quebra em dispositivos (artigo, parágrafo, inciso, alínea) e aponta o que conferir; nada
// vale até uma pessoa conferir e publicar. Aqui só se decide o TEXTO e a forma de mostrar.

export type Especie =
  | "lei_organica"
  | "regimento_interno"
  | "lei_complementar"
  | "lei"
  | "resolucao"
  | "decreto"
  | "outra"
  | "constituicao_federal"
  | "constituicao_estadual";

export type EstadoVersao = "em_conferencia" | "vigente" | "substituida" | "descartada";

export type NormaOut = {
  id: string;
  camada: "federal" | "estadual" | "municipal" | "casa";
  especie: Especie;
  titulo: string;
  numero: string | null;
  data: string | null;
  daCasa: boolean;
};

export type VersaoResumoOut = {
  id: string;
  estado: EstadoVersao;
  consolidadaAte: string | null;
  fonte: string;
  nDispositivos: number;
  nAlertas: number;
  enviadaEm: string;
  decididaEm: string | null;
};

export type NormaResumoOut = { norma: NormaOut; vigente: VersaoResumoOut | null; emConferencia: VersaoResumoOut | null };

export type DispositivoOut = {
  endereco: string;
  rotulo: string;
  tipo: "preambulo" | "artigo" | "paragrafo" | "inciso" | "alinea" | "item";
  pai: string | null;
  ordem: number;
  texto: string;
  agrupador: string | null;
};

export type VersaoOut = { norma: NormaOut; versao: VersaoResumoOut; alertas: string[]; dispositivos: DispositivoOut[] };

export const ESPECIES_DA_CASA: { valor: Especie; rotulo: string; unica: boolean }[] = [
  { valor: "lei_organica", rotulo: "Lei Orgânica do Município", unica: true },
  { valor: "regimento_interno", rotulo: "Regimento Interno da Câmara", unica: true },
  { valor: "lei_complementar", rotulo: "Lei complementar municipal", unica: false },
  { valor: "lei", rotulo: "Lei municipal", unica: false },
  { valor: "resolucao", rotulo: "Resolução da Câmara", unica: false },
  { valor: "decreto", rotulo: "Decreto", unica: false },
  { valor: "outra", rotulo: "Outra norma", unica: false },
];

const ROTULO_ESPECIE: Record<Especie, string> = {
  ...Object.fromEntries(ESPECIES_DA_CASA.map((e) => [e.valor, e.rotulo])),
  constituicao_federal: "Constituição Federal",
  constituicao_estadual: "Constituição Estadual",
} as Record<Especie, string>;

export function rotuloDaEspecie(e: Especie): string {
  return ROTULO_ESPECIE[e] ?? e;
}

export function especieUnica(e: string): boolean {
  return ESPECIES_DA_CASA.some((x) => x.valor === e && x.unica);
}

const CAMADA: Record<NormaOut["camada"], string> = {
  federal: "Federal",
  estadual: "Estadual",
  municipal: "Do Município",
  casa: "Da Câmara",
};

export function rotuloDaCamada(c: NormaOut["camada"]): string {
  return CAMADA[c];
}

function dataBr(iso: string | null): string | null {
  if (!iso) return null;
  const [a, m, d] = iso.slice(0, 10).split("-");
  return a && m && d ? `${d}/${m}/${a}` : iso;
}

/** "Vigente · conferida em 27/09/2026 · consolidada até 30/06/2026 · 214 dispositivos" */
export function linhaDaVigente(v: VersaoResumoOut): string {
  const partes = [`conferida em ${dataBr(v.decididaEm)}`];
  if (v.consolidadaAte) partes.push(`consolidada até ${dataBr(v.consolidadaAte)}`);
  partes.push(`${v.nDispositivos} dispositivos`);
  return partes.join(" · ");
}

export function linhaDaEmConferencia(v: VersaoResumoOut): string {
  const alertas = v.nAlertas === 0 ? "sem alertas" : v.nAlertas === 1 ? "1 ponto a conferir" : `${v.nAlertas} pontos a conferir`;
  return `Enviada em ${dataBr(v.enviadaEm)} · ${v.nDispositivos} dispositivos · ${alertas}`;
}

export const ESTADO: Record<EstadoVersao, string> = {
  em_conferencia: "Esperando conferência",
  vigente: "Vigente",
  substituida: "Substituída por versão mais nova",
  descartada: "Descartada",
};

/** Dispositivos em blocos pelo agrupador (Título/Capítulo), para a conferência ler como o texto original. */
export function emBlocos(ds: DispositivoOut[]): { agrupador: string | null; dispositivos: DispositivoOut[] }[] {
  const blocos: { agrupador: string | null; dispositivos: DispositivoOut[] }[] = [];
  for (const d of ds) {
    const ultimo = blocos[blocos.length - 1];
    if (ultimo && ultimo.agrupador === d.agrupador) ultimo.dispositivos.push(d);
    else blocos.push({ agrupador: d.agrupador, dispositivos: [d] });
  }
  return blocos;
}

/** O rótulo curto que abre o dispositivo no texto ("Art. 12", "§ 1º", "II", "a)"), a partir do rótulo de citação. */
export function marcaDoDispositivo(d: DispositivoOut): string {
  const ultimo = d.rotulo.split(", ").pop() ?? d.rotulo;
  switch (d.tipo) {
    case "artigo":
      return ultimo.replace(/^art\./, "Art.");
    case "paragrafo":
      return ultimo === "parágrafo único" ? "Parágrafo único." : ultimo;
    case "inciso":
      return `${ultimo} –`;
    case "alinea":
      return `${ultimo})`;
    case "item":
      return `${ultimo.replace("item ", "")}.`;
    default:
      return "";
  }
}

export function mensagemDeErroNormas(status: number, erro?: string): string {
  if (status === 409 && erro) return erro;
  if (status === 400) return "Confira os campos: espécie, título, de onde veio o texto e o próprio texto são obrigatórios.";
  if (status === 403) return "A curadoria das normas é da secretaria.";
  if (status === 413) return "O texto é grande demais para uma norma só.";
  return "Não foi possível falar com o sistema agora. Tente de novo.";
}
