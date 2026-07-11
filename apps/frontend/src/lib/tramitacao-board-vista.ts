// View-model puro do quadro de tramitação (Onda B Slice 4, "Tramitação" — a visão do servidor de onde
// cada matéria está agora). Traduz ItemBoardOut (wire de GET /api/paineis/tramitacao, já camelizado e já
// agrupado/ordenado por `estado` asc, `transicionouEm` asc DENTRO do grupo pelo backend) em colunas do
// quadro. Reusa formatarNumeroProposicao/formatarEspecieProposicao + os 3 conjuntos de estado
// (ESTADOS_APROVADOS/ESTADOS_ARQUIVADOS/ESTADOS_AGUARDANDO_PAUTA) já fechados em proposicoes-vista.ts —
// nenhum vocabulário novo de estado é inventado aqui, mesma disciplina de tramitacao-vista.ts.
//
// Vocabulário de `estado`: string LIVRE, template-driven por câmara (ver o aviso completo no topo de
// tramitacao-vista.ts) — NÃO existe enum fechado. Por isso o mapa abaixo cobre só os nomes conhecidos do
// rito ilustrativo do design-system (tramitacao-board.html: Protocolo/Comissões/Pronta p/ pauta/Em
// Plenário/Concluídas) + os estados-terminais já usados em proposicoes-vista.ts. QUALQUER estado fora
// deste mapa NÃO é descartado em silêncio — perder uma matéria de um quadro operacional do servidor
// (que decide o que fazer a seguir) é pior que mostrá-la de forma estranha numa coluna própria. Esses
// estados caem numa 6ª coluna literal "Outros" (azulejo neutro), que só aparece quando tem pelo menos 1
// item — fail-closed honesto, mesmo princípio de `derivarTramitacao`/`categorizarSituacao`.

import {
  ESTADOS_AGUARDANDO_PAUTA,
  ESTADOS_APROVADOS,
  ESTADOS_ARQUIVADOS,
  formatarEspecieProposicao,
  formatarNumeroProposicao,
} from "./proposicoes-vista";
import type { ItemBoardOut } from "./contrato-mesa.gen";

export type AzulejoCor = "jade" | "cobalto" | "amarelo" | "telha" | "verde" | "neutro";

export type ItemDoBoard = {
  proposicaoId: string;
  numero: string;
  especie: string;
  ementa: string;
  autor: string;
  estado: string;
};

export type ColunaBoard = {
  chave: string;
  titulo: string;
  azulejo: AzulejoCor;
  itens: ItemDoBoard[];
};

const ESTADOS_EM_PLENARIO = new Set(["primeiro_turno", "segundo_turno", "em_sancao"]);

// As 5 colunas fixas, na ordem do quadro-fonte (tramitacao-board.html). `pertence` decide se um `estado`
// cru cai nesta coluna — checado em ordem, a primeira que bater vence (sem sobreposição nos conjuntos
// conhecidos).
const COLUNAS_FIXAS: { chave: string; titulo: string; azulejo: AzulejoCor; pertence: (estado: string) => boolean }[] = [
  { chave: "protocolo", titulo: "Protocolo", azulejo: "jade", pertence: (e) => e === "protocolada" },
  { chave: "comissoes", titulo: "Comissões", azulejo: "cobalto", pertence: (e) => e === "em_comissoes" },
  { chave: "pronta-pauta", titulo: "Pronta p/ pauta", azulejo: "amarelo", pertence: (e) => ESTADOS_AGUARDANDO_PAUTA.has(e) },
  { chave: "em-plenario", titulo: "Em Plenário", azulejo: "telha", pertence: (e) => ESTADOS_EM_PLENARIO.has(e) },
  {
    chave: "concluidas",
    titulo: "Concluídas",
    azulejo: "verde",
    pertence: (e) => ESTADOS_APROVADOS.has(e) || ESTADOS_ARQUIVADOS.has(e),
  },
];

function paraItemDoBoard(item: ItemBoardOut): ItemDoBoard {
  return {
    proposicaoId: item.proposicaoId,
    numero: formatarNumeroProposicao(item.tipo, item.sequencial, item.ano),
    especie: formatarEspecieProposicao(item.tipo),
    ementa: item.ementa,
    autor: item.autorTexto ?? "—",
    estado: item.estado,
  };
}

export function derivarBoard(itens: ItemBoardOut[]): ColunaBoard[] {
  const colunas: ColunaBoard[] = COLUNAS_FIXAS.map((c) => ({
    chave: c.chave,
    titulo: c.titulo,
    azulejo: c.azulejo,
    itens: [],
  }));
  const outros: ColunaBoard = { chave: "outros", titulo: "Outros", azulejo: "neutro", itens: [] };

  // agrupa preservando a ordem de chegada (o backend já entrega estado asc/transicionouEm asc dentro do
  // grupo — não reordenar aqui).
  for (const item of itens) {
    const alvo = COLUNAS_FIXAS.findIndex((c) => c.pertence(item.estado));
    if (alvo === -1) {
      outros.itens.push(paraItemDoBoard(item));
    } else {
      colunas[alvo].itens.push(paraItemDoBoard(item));
    }
  }

  // "Outros" só aparece quando há ao menos 1 item nela — as 5 colunas fixas aparecem sempre (mesmo
  // vazias), pra o servidor ver de cara que "Em Plenário" está zerado, por exemplo.
  return outros.itens.length > 0 ? [...colunas, outros] : colunas;
}

// Filtro client-side por texto livre (ementa OU número formatado), sobre os dados já buscados — sem novo
// round-trip. Case-insensitive + aproximação sem-acento (normalize NFD, barato e nativo do JS).
function normalizarBusca(s: string): string {
  return s
    .normalize("NFD")
    .replace(/[\u0300-\u036f]/g, "")
    .toLowerCase();
}

export function itemCorrespondeBusca(item: ItemDoBoard, busca: string): boolean {
  const alvo = busca.trim();
  if (alvo === "") return true;
  const agulha = normalizarBusca(alvo);
  return normalizarBusca(item.ementa).includes(agulha) || normalizarBusca(item.numero).includes(agulha);
}

export function filtrarColunasPorBusca(colunas: ColunaBoard[], busca: string): ColunaBoard[] {
  if (busca.trim() === "") return colunas;
  return colunas.map((coluna) => ({
    ...coluna,
    itens: coluna.itens.filter((item) => itemCorrespondeBusca(item, busca)),
  }));
}

// Filtro client-side por espécie (facetа "Espécie" do quadro) — pura, mesma convenção das demais funções
// deste módulo (movida de dentro de page.tsx: era lógica de derivação sem nenhuma cobertura de teste).
// `tipo` é o valor cru do <option> (ex. "projeto_lei"); comparamos contra `especie` já formatada porque é
// o que ItemDoBoard carrega.
export function filtrarColunasPorEspecie(colunas: ColunaBoard[], tipo: string, formatarEspecie: (tipo: string) => string): ColunaBoard[] {
  if (tipo === "") return colunas;
  const especie = formatarEspecie(tipo);
  return colunas.map((coluna) => ({
    ...coluna,
    itens: coluna.itens.filter((item) => item.especie === especie),
  }));
}

// Teto de itens renderizados de uma vez por coluna antes de exigir "mostrar mais" — a coluna "Concluídas"
// funde 8 estados terminais distintos (ver ESTADOS_APROVADOS/ESTADOS_ARQUIVADOS acima) e cresce sem limite
// ao longo da legislatura; sem isso o DOM da coluna acumula centenas de cards de uma vez só.
export const TETO_ITENS_VISIVEIS_POR_COLUNA = 30;

export function paginarColuna(coluna: ColunaBoard, expandida: boolean): ColunaBoard {
  if (expandida || coluna.itens.length <= TETO_ITENS_VISIVEIS_POR_COLUNA) return coluna;
  return { ...coluna, itens: coluna.itens.slice(0, TETO_ITENS_VISIVEIS_POR_COLUNA) };
}
