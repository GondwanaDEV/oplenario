// View-model PURO da CHAMADA (§22.6 eixo C, Etapa 3B fatia 1) — traduz `ChamadaOut`/`LinhaChamadaOut` (o
// snapshot que o servidor publica) no que a TELA precisa: ordenação, agrupamento, contagem local otimista,
// o diff a enviar para `POST /sessoes/:id/presenca/lote`, e a mecânica de desfazer da ação em massa. Sem IO,
// sem React — mesmo padrão de placar-vista.ts / mesa-vista.ts. Testado em chamada-vista.test.ts.
//
// A REGRA MAIS IMPORTANTE DESTE MÓDULO, herdada de plenario-reducer.ts (ver ali `hidratarQuorum`): o
// cliente NÃO soma, não subtrai e não deduz o quórum. `contarLocal` existe só para feedback OTIMISTA
// imediato do operador durante a chamada — o número oficial é sempre `ChamadaOut.quorum`
// (`ChamadaQuorumOut`), vindo do servidor (`logic/contar-quorum`), e este módulo nunca tenta reconciliar
// os dois.

import type { LinhaChamadaOut } from "./contrato-sessoes.gen";

export type EstadoLinhaChamada = LinhaChamadaOut["estado"];

// ---------- carry: `LinhaChamadaOut.justificativa` no codegen ----------
// O codegen (oplenario.codegen.malli-ts) perdeu a forma do map aninhado e gerou `justificativa` como
// `Record<string, unknown> | null` — a FONTE (apps/backend/src/oplenario/sessoes/wire/out.clj,
// `LinhaChamadaOut`) mostra a forma real: `{estado, motivo, decidido-em}` (camelCase no wire:
// `decididoEm`). NÃO se conserta o codegen aqui (é gerado, fora do escopo desta fatia) — declara-se o tipo
// estreito de LEITURA e uma função de narrowing defensiva, fail-closed: forma inesperada devolve `null`,
// nunca lança.

export interface JustificativaLinha {
  estado: "aprovada" | "indeferida" | "pendente";
  motivo: string;
  decididoEm: string | null;
}

const ESTADOS_JUSTIFICATIVA = new Set(["aprovada", "indeferida", "pendente"]);

/** Narrowing fail-closed de `LinhaChamadaOut.justificativa` (`Record<string, unknown> | null` no gerado)
 * para a forma real do wire. Forma inesperada (campo faltando, tipo errado, estado forasteiro ao
 * vocabulário) devolve `null` em vez de lançar — o mesmo espírito de `lerSnapshot` em plenario-reducer.ts. */
export function lerJustificativa(cru: Record<string, unknown> | null): JustificativaLinha | null {
  if (cru === null) return null;
  const { estado, motivo, decididoEm } = cru;
  if (typeof estado !== "string" || !ESTADOS_JUSTIFICATIVA.has(estado)) return null;
  if (typeof motivo !== "string") return null;
  if (decididoEm !== null && decididoEm !== undefined && typeof decididoEm !== "string") return null;
  return { estado: estado as JustificativaLinha["estado"], motivo, decididoEm: (decididoEm as string | undefined) ?? null };
}

// ---------- 1. ordenarLinhas ----------

export type CriterioOrdenacao = "servidor" | "alfabetica" | "partido" | "estado";

/** A ordem de precedência dos 6 estados da chamada (dado, não `if` espalhado pela UI). Também governa o
 * critério 'estado' de `ordenarLinhas`. "Justificativa pendente de decisão" NUNCA colapsa com falta
 * injustificada — por isso os dois vêm em posições distintas, na ordem em que a chamada os trata
 * (`logic/estado-de-presenca`: presente > licenciado > justificado > pendente > ausente). */
export const precedenciaDeEstado: EstadoLinhaChamada[] = [
  "presente-plenario",
  "presente-remoto",
  "ausente-justificado",
  "ausente-justificativa-pendente",
  "ausente",
  "licenciado",
];

const RANK_ESTADO = new Map(precedenciaDeEstado.map((estado, indice) => [estado, indice]));

/** Sort estável explícito (não depende da garantia de estabilidade do engine — barato e remove a dúvida). */
function ordenarEstavel<T>(itens: T[], chave: (item: T) => string | number): T[] {
  return itens
    .map((item, indiceOriginal) => ({ item, indiceOriginal }))
    .sort((a, b) => {
      const ca = chave(a.item);
      const cb = chave(b.item);
      if (ca < cb) return -1;
      if (ca > cb) return 1;
      return a.indiceOriginal - b.indiceOriginal;
    })
    .map(({ item }) => item);
}

const chaveAlfabetica = (l: LinhaChamadaOut): string => (l.nomeParlamentar ?? l.nome ?? "").toLocaleLowerCase("pt-BR");
const chavePartido = (l: LinhaChamadaOut): string => (l.partido ?? "").toLocaleLowerCase("pt-BR");
const chaveEstado = (l: LinhaChamadaOut): number => RANK_ESTADO.get(l.estado) ?? precedenciaDeEstado.length;

/** Ordena as linhas da chamada pelo critério pedido. `"servidor"` é o DEFAULT e é a IDENTIDADE — devolve na
 * ordem recebida — porque a ordem da chamada varia por regimento interno de cada Casa e o cliente NÃO
 * inventa rito. Nunca muta o array recebido; sempre estável. */
export function ordenarLinhas(linhas: LinhaChamadaOut[], criterio: CriterioOrdenacao = "servidor"): LinhaChamadaOut[] {
  switch (criterio) {
    case "servidor":
      return [...linhas];
    case "alfabetica":
      return ordenarEstavel(linhas, chaveAlfabetica);
    case "partido":
      return ordenarEstavel(linhas, chavePartido);
    case "estado":
      return ordenarEstavel(linhas, chaveEstado);
  }
}

// ---------- 2. agruparLinhas ----------

export interface GruposChamada {
  mesa: LinhaChamadaOut[];
  casa: LinhaChamadaOut[];
  licenciados: LinhaChamadaOut[];
  foraDaComposicao: LinhaChamadaOut[];
}

/** Agrupa as linhas em 4 seções, cada linha em EXATAMENTE um grupo. `semAssento` GANHA de tudo (é o
 * fail-loud da §22.6 eixo C — a linha não veio do roster e não deve se disfarçar de mesa/licenciado): uma
 * linha órfã com `cargoMesa` preenchido ou `estado === "licenciado"` ainda vai para `foraDaComposicao`. */
export function agruparLinhas(linhas: LinhaChamadaOut[]): GruposChamada {
  const grupos: GruposChamada = { mesa: [], casa: [], licenciados: [], foraDaComposicao: [] };
  for (const l of linhas) {
    if (l.semAssento) grupos.foraDaComposicao.push(l);
    else if (l.estado === "licenciado") grupos.licenciados.push(l);
    else if (l.cargoMesa !== null) grupos.mesa.push(l);
    else grupos.casa.push(l);
  }
  return grupos;
}

// ---------- 3. contarLocal ----------

export interface ContagemLocal {
  presentesPlenario: number;
  presentesRemoto: number;
  presentesTotal: number;
}

/** Contagem LOCAL/OTIMISTA das linhas — feedback imediato do operador enquanto ele marca a chamada.
 *
 * NOMEADA ASSIM DE PROPÓSITO: este número NÃO É O QUÓRUM. O quórum oficial é `ChamadaOut.quorum`
 * (`ChamadaQuorumOut`), vindo do servidor (`logic/contar-quorum`) — a regra já cravada em
 * `plenario-reducer.ts` (ver `hidratarQuorum`) é que o cliente não soma, não subtrai e não deduz o
 * quórum, e vale aqui do mesmo jeito. Os dois números PODEM divergir por construção (ex.: uma linha
 * `sem-assento` fora desta amostra que o servidor já contou no numerador) e esta função não tenta
 * reconciliá-los — nem consulta `membrosDaCasa`, que não é dela. */
export function contarLocal(linhas: LinhaChamadaOut[]): ContagemLocal {
  let presentesPlenario = 0;
  let presentesRemoto = 0;
  for (const l of linhas) {
    if (l.estado === "presente-plenario") presentesPlenario++;
    else if (l.estado === "presente-remoto") presentesRemoto++;
  }
  return { presentesPlenario, presentesRemoto, presentesTotal: presentesPlenario + presentesRemoto };
}

// ---------- 5. diffParaLote ----------

/** Espelha `oplenario.sessoes.wire.in/teto-lote-presenca` — o teto de LINHAS de
 * `POST /sessoes/:id/presenca/lote` no servidor. Duplicado aqui de propósito (é uma constante de wire, não
 * algo importável de um `.gen.ts`): se o servidor mudar o teto, este número precisa mudar junto — não é
 * um `[GAP]`, é o preço de não termos um canal de constantes compartilhado entre as duas linguagens. */
export const TETO_LOTE_PRESENCA = 200;

/** O estado que o OPERADOR pode marcar como alvo numa linha. Deliberadamente menor que
 * `EstadoLinhaChamada`: `licenciado` não é um alvo (é fato de cadastro, não de chamada) e os estados de
 * justificativa (`ausente-justificado` / `ausente-justificativa-pendente`) não se marcam por aqui — nascem
 * do fluxo apartado de justificativa (Etapa 2), não da chamada de presença. */
export type EstadoAlvo = "presente-plenario" | "presente-remoto" | "ausente";

export interface MarcacaoPendente {
  estadoAlvo: EstadoAlvo;
  /** Instante ISO-8601 do FATO (`ocorrido-em`) — não do clique. O domínio permite registro retroativo de
   * propósito; esta função nunca substitui `desde` por um relógio interno. */
  desde: string;
}

/** vereadorId -> marcação pendente (ainda não enviada ao servidor). */
export type MarcacoesPendentes = Record<string, MarcacaoPendente>;

export interface RegistroLote {
  vereadorId: string;
  tipo: "entrada" | "saida" | "retorno" | "mudanca_modalidade";
  modalidade: "plenario" | "remoto";
  ocorridoEm: string;
}

export type ResultadoDiff = { ok: true; registros: RegistroLote[] } | { ok: false; erro: string };

const modalidadeDoEstadoPresente = (estado: EstadoAlvo): "plenario" | "remoto" | null => {
  if (estado === "presente-plenario") return "plenario";
  if (estado === "presente-remoto") return "remoto";
  return null;
};

const modalidadeDaLinha = (estado: EstadoLinhaChamada): "plenario" | "remoto" | null => {
  if (estado === "presente-plenario") return "plenario";
  if (estado === "presente-remoto") return "remoto";
  return null;
};

/** O `tipo` do evento de presença que leva a linha do estado de ORIGEM ao `estadoAlvo`. Regra:
 *   - alvo "ausente"                                -> "saida";
 *   - origem já presente (plenário<->remoto)        -> "mudanca_modalidade" (não sai, só troca de coluna);
 *   - origem ausente/justificado/pendente -> presente -> "entrada".
 * "retorno" (também positivo no domínio) não é produzido por esta função — não há, nesta fatia, um sinal
 * na UI que distinga "retorno de licença" de "entrada"; ambos são tratados igual pela derivação do
 * servidor (`logic/tipos-presenca-positiva`), então "entrada" é a escolha segura e não perde informação de
 * quórum. Registrado como carry no relatório. */
function tipoDaTransicao(origem: EstadoLinhaChamada, alvo: EstadoAlvo): RegistroLote["tipo"] {
  if (alvo === "ausente") return "saida";
  const origemEraPresente = origem === "presente-plenario" || origem === "presente-remoto";
  return origemEraPresente ? "mudanca_modalidade" : "entrada";
}

/** As marcações que MUDARAM, prontas no formato do corpo de `POST /sessoes/:id/presenca/lote`
 * (`{registros: [...]}` — aqui devolve-se só o array; o hook de I/O monta o envelope).
 *
 * Regras (nenhuma delas é opcional):
 *   - só entram linhas cujo estado REALMENTE mudou (marcar de novo o mesmo estado não vira escrita);
 *   - linhas `licenciado` e `semAssento` NUNCA entram, mesmo se vierem marcadas (a UI não deveria oferecer
 *     o controle nelas, mas esta função não confia na UI — fail-closed aqui também);
 *   - o lote respeita o teto de `TETO_LOTE_PRESENCA` linhas do servidor: se passar, devolve ERRO
 *     EXPLÍCITO (`{ok:false}`), nunca trunca em silêncio — um lote truncado é uma chamada que a tela
 *     acha que gravou e o servidor nunca viu por completo. */
export function diffParaLote(linhasOriginais: LinhaChamadaOut[], marcacoes: MarcacoesPendentes): ResultadoDiff {
  const registros: RegistroLote[] = [];
  for (const linha of linhasOriginais) {
    const marca = marcacoes[linha.vereadorId];
    if (!marca) continue;
    if (linha.estado === "licenciado" || linha.semAssento) continue;
    if (linha.estado === marca.estadoAlvo) continue;

    const tipo = tipoDaTransicao(linha.estado, marca.estadoAlvo);
    const modalidade = modalidadeDoEstadoPresente(marca.estadoAlvo) ?? modalidadeDaLinha(linha.estado) ?? "plenario";
    registros.push({ vereadorId: linha.vereadorId, tipo, modalidade, ocorridoEm: marca.desde });
  }
  if (registros.length > TETO_LOTE_PRESENCA) {
    return { ok: false, erro: `lote excede o teto de ${TETO_LOTE_PRESENCA} linhas (tem ${registros.length})` };
  }
  return { ok: true, registros };
}

// ---------- 6. aplicarOtimista / reverterOtimista ----------

export interface SnapshotOtimista {
  /** vereadorId -> a marcação que existia ANTES da ação em massa, ou `null` quando não havia nenhuma (a
   * linha estava intocada). O `null` é o que permite `reverterOtimista` REMOVER a chave em vez de inventar
   * um valor — "não havia marcação" é um estado distinto de "havia uma marcação vazia". */
  anteriores: Map<string, MarcacaoPendente | null>;
}

/** Ação em massa "Todos presentes" (plenário), com DESFAZER como mecanismo de segurança — não confirmação.
 * Marca `presente-plenario` em toda linha editável (exclui `licenciado`/`semAssento`, do mesmo jeito que
 * `diffParaLote`), e devolve junto o snapshot que permite reverter EXATAMENTE ao estado anterior. */
export function aplicarOtimista(
  linhas: LinhaChamadaOut[],
  marcacoesAtuais: MarcacoesPendentes,
  desde: string,
): { marcacoes: MarcacoesPendentes; snapshot: SnapshotOtimista } {
  const marcacoes = { ...marcacoesAtuais };
  const anteriores = new Map<string, MarcacaoPendente | null>();
  for (const l of linhas) {
    if (l.estado === "licenciado" || l.semAssento) continue;
    anteriores.set(l.vereadorId, marcacoesAtuais[l.vereadorId] ?? null);
    marcacoes[l.vereadorId] = { estadoAlvo: "presente-plenario", desde };
  }
  return { marcacoes, snapshot: { anteriores } };
}

/** Desfaz uma `aplicarOtimista`: restaura o estado EXATO anterior, linha a linha. Uma linha que não tinha
 * marcação nenhuma antes da ação em massa volta a não ter marcação nenhuma (chave removida) — nunca um
 * valor fabricado. */
export function reverterOtimista(marcacoesAtuais: MarcacoesPendentes, snapshot: SnapshotOtimista): MarcacoesPendentes {
  const marcacoes = { ...marcacoesAtuais };
  for (const [vereadorId, anterior] of snapshot.anteriores) {
    if (anterior === null) delete marcacoes[vereadorId];
    else marcacoes[vereadorId] = anterior;
  }
  return marcacoes;
}

// ---------- 7. podeEditar ----------

/** Allowlist dos estados de sessão em que a chamada pode ser editada — NUNCA complemento. A decisão de que
 * complemento é fail-open é da Etapa 2 (backend, `sessoes/logic.clj`); aqui a mesma disciplina se repete no
 * cliente: um estado desconhecido/novo (que a lista ainda não previu) cai em NÃO-editável por construção,
 * não em editável por omissão. */
export function podeEditar(sessaoEstado: string): boolean {
  return sessaoEstado === "agendada" || sessaoEstado === "aberta" || sessaoEstado === "suspensa";
}
