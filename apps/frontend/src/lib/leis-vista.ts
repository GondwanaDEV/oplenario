// Vista da LEGISLAÇÃO PUBLICADA no portal do cidadão (GET /portal/casa/{ente}/legislacao): os tipos do acervo em
// palavras, o título "Lei nº 12/2026" e a leitura do filtro que vem da URL. O vocabulário dos tipos é o do CHECK de
// `transparencia.norma` (migration 20260620000023): lei, lei_complementar, resolucao, decreto_legislativo, emenda_lom.

export const TIPOS_DE_NORMA = [
  { valor: "lei", rotulo: "Lei" },
  { valor: "lei_complementar", rotulo: "Lei complementar" },
  { valor: "resolucao", rotulo: "Resolução" },
  { valor: "decreto_legislativo", rotulo: "Decreto legislativo" },
  { valor: "emenda_lom", rotulo: "Emenda à Lei Orgânica" },
] as const;

export function rotuloDoTipo(tipo: string): string {
  const conhecido = TIPOS_DE_NORMA.find((t) => t.valor === tipo);
  if (conhecido) return conhecido.rotulo;
  const frase = tipo.replace(/_/g, " ").trim();
  return frase ? frase.charAt(0).toUpperCase() + frase.slice(1) : "Norma";
}

export function tituloDaNorma(n: { tipoNorma: string; numero: number; ano: number }): string {
  return `${rotuloDoTipo(n.tipoNorma)} nº ${n.numero}/${n.ano}`;
}

export type FiltroDeLeis = { tipo: string; ano: string; numero: string; ignorados: string[] };

type Parametro = string | string[] | undefined;

// O servidor lê ano e número como int4 (Integer/parseInt): mais de 9 dígitos pode estourar e vira 400. Aqui o valor
// inválido é descartado e DECLARADO (o componente avisa), em vez de mandar ao servidor e mostrar um erro genérico.
const INTEIRO = /^\d{1,9}$/;

function primeiro(p: Parametro): string {
  return (Array.isArray(p) ? p[0] : p)?.trim() ?? "";
}

export function lerFiltro(q: { tipo?: Parametro; ano?: Parametro; numero?: Parametro }): FiltroDeLeis {
  const ignorados: string[] = [];
  let tipo = primeiro(q.tipo);
  if (tipo && !TIPOS_DE_NORMA.some((t) => t.valor === tipo)) {
    ignorados.push("tipo");
    tipo = "";
  }
  let ano = primeiro(q.ano);
  if (ano && !INTEIRO.test(ano)) {
    ignorados.push("ano");
    ano = "";
  }
  let numero = primeiro(q.numero);
  if (numero && !INTEIRO.test(numero)) {
    ignorados.push("número");
    numero = "";
  }
  return { tipo, ano, numero, ignorados };
}

export function consultaDoFiltro(f: FiltroDeLeis): Record<string, string> {
  return {
    ...(f.tipo ? { tipo: f.tipo } : {}),
    ...(f.ano ? { ano: f.ano } : {}),
    ...(f.numero ? { numero: f.numero } : {}),
  };
}

// A página da lista (`?pagina=`): inteiro de 1 em diante. Qualquer outra coisa (ausente, 0, texto, número enorme) é a
// primeira página: a lista nunca fica em branco por causa de um endereço digitado errado.
export function lerPagina(p: Parametro): number {
  const t = primeiro(p);
  if (!INTEIRO.test(t)) return 1;
  const n = Number.parseInt(t, 10);
  return n > 1 ? n : 1;
}

// A consulta que vai ao servidor e à URL da tela: o filtro mais a página (a primeira não leva `?pagina=`).
export function consultaDaLista(f: FiltroDeLeis, pagina: number): Record<string, string> {
  return { ...consultaDoFiltro(f), ...(pagina > 1 ? { pagina: String(pagina) } : {}) };
}

export function filtroAtivo(f: FiltroDeLeis): boolean {
  return Boolean(f.tipo || f.ano || f.numero);
}
