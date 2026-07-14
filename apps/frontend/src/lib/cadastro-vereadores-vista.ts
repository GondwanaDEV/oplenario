// View-model puro do Cadastro de vereadores (Task 7) — traduz VereadorLinhaOut (wire de
// GET /cadastros/vereadores, já camelizado) em pedaços de apresentação: avatar (iniciais+cor
// determinísticas), chip de estado do mandato, filtro de busca client-side e seleção inicial
// vinda da URL. Funções puras (sem DOM/fetch), mesma disciplina de tramitacao-board-vista.ts.

import type { VereadorLinhaOut } from "./contrato-cadastros.gen";

// Paleta fixa dos avatares na tela-fonte (cadastro-vereadores.html) — cada linha usa
// `style="background:<valor>"` direto, misturando `var(--token)` e hex literal. Reproduzimos os
// mesmos 7 valores, na mesma ordem em que aparecem lá, pra `cor` sair pronta pra virar `background`
// CSS sem tradução nenhuma no consumidor (Task 9).
const PALETA_AVATAR = [
  "var(--jade)",
  "var(--cobalto)",
  "var(--telha)",
  "var(--jade-claro)",
  "#7A4FA0",
  "var(--cobalto-fundo)",
  "#9C6B1E",
] as const;

export type Avatar = { iniciais: string; cor: string };

function somaCharCodes(s: string): number {
  let soma = 0;
  for (let i = 0; i < s.length; i += 1) soma += s.charCodeAt(i);
  return soma;
}

function iniciaisDoNome(nome: string): string {
  const palavras = nome.trim().split(/\s+/).filter((p) => p.length > 0);
  return palavras
    .slice(0, 2)
    .map((p) => p.charAt(0).toUpperCase())
    .join("");
}

// `id`, quando presente, decide a cor (estável mesmo se o nome mudar de grafia); na ausência
// (ex. linha ainda sem id resolvido), cai pro hash do próprio nome — em ambos os casos a MESMA
// entrada sempre produz a MESMA cor (requisito de determinismo do Task 9: o avatar não "pisca"
// de cor a cada re-render).
export function avatar(nome: string, id?: string | null): Avatar {
  const chaveDeCor = id ?? nome;
  const indice = somaCharCodes(chaveDeCor) % PALETA_AVATAR.length;
  return { iniciais: iniciaisDoNome(nome), cor: PALETA_AVATAR[indice] };
}

export type TomChip = "ativo" | "licenca" | "neutro";
export type EstadoChip = { rotulo: string; tom: TomChip };

// Rótulos humanizados dos estados-terminais conhecidos (`cadastros.models.cadastro/estados-mandato`
// no backend: vigente/licenciado tratados à parte abaixo; os demais são catch-all honesto — a Casa
// vê "por que" o mandato não está ativo, sem o FE inventar cor de sucesso pra um mandato cassado).
const ESTADOS_MANDATO_HUMANIZADOS: Record<string, string> = {
  cassado: "Mandato cassado",
  renunciado: "Mandato encerrado (renúncia)",
  falecido: "Mandato encerrado (óbito)",
  concluido: "Mandato concluído",
};

function humanizarEstadoDesconhecido(estado: string): string {
  const limpo = estado.replace(/_/g, " ").trim();
  if (limpo === "") return "Sem mandato";
  return limpo.charAt(0).toUpperCase() + limpo.slice(1);
}

// Catch-all fail-closed: NUNCA lança pra um `estado` fora do enum conhecido — um valor novo do
// backend (ou lixo) cai num rótulo honesto de tom neutro, nunca num crash da tela nem num chip
// verde/âmbar enganoso. Mesmo princípio de `derivarBoard`/`itemCorrespondeBusca` em
// tramitacao-board-vista.ts.
export function estadoChip(estado?: string | null): EstadoChip {
  if (estado === "vigente") return { rotulo: "Mandato ativo", tom: "ativo" };
  if (estado === "licenciado") return { rotulo: "Licença", tom: "licenca" };
  if (estado == null) return { rotulo: "Sem mandato", tom: "neutro" };
  return {
    rotulo: ESTADOS_MANDATO_HUMANIZADOS[estado] ?? humanizarEstadoDesconhecido(estado),
    tom: "neutro",
  };
}

// Filtro client-side por texto livre sobre nome/nome-parlamentar/partido — sem novo round-trip,
// sobre os dados já buscados. Case-insensitive; campos ausentes (null) simplesmente não casam.
export function filtrar(linhas: VereadorLinhaOut[], busca: string): VereadorLinhaOut[] {
  const alvo = busca.trim().toLowerCase();
  if (alvo === "") return linhas;
  return linhas.filter((linha) => {
    const campos = [linha.nome, linha.nomeParlamentar, linha.partido];
    return campos.some((campo) => campo != null && campo.toLowerCase().includes(alvo));
  });
}

// Seleção inicial do master-detail: honra o id vindo da URL (deep link) se ele de fato existir
// na lista corrente; senão cai pro 1º vereador da lista; lista vazia -> nenhuma seleção.
export function selecaoInicial(linhas: VereadorLinhaOut[], urlId: string | null | undefined): string | null {
  if (urlId != null && linhas.some((linha) => linha.id === urlId)) return urlId;
  return linhas[0]?.id ?? null;
}
