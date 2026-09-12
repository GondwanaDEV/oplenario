// View-model puro da inbox (Onda E fatia 1) — 100% testável sem rede e sem DOM: agrupa temporalmente o
// que veio de GET /meu/notificacoes e deriva o estado visual. NENHUM fetch aqui (os hooks
// use-minhas-notificacoes / use-marcar-lida ficam no page.tsx), mesmo padrão de meu-painel-vista.ts.
//
// `agoraIso` é OPCIONAL (default = o relógio real) só para manter a função genuinamente pura — os testes
// fixam o instante explicitamente, e a PÁGINA também passa o seu (um `agora` de estado, atualizado de
// minuto em minuto: com o default, "há 5min" congelava no instante da montagem e saltava horas de uma
// vez no primeiro clique de aba). Comparação de ISO-8601 como string funciona porque os timestamps do
// backend são sempre UTC 'Z' (mesmo idioma de ficha-materia-vista.ts / mesa-vista.ts).
//
// ── A BARRA DE FILTRO (fatia 3): abas DERIVADAS do dado, não cravadas ──────────────────────────────
// O design (telas/notificacoes.html) desenha seis abas fixas — Tudo/Não lidas/Falhas/Prazos/Tramitação/
// Sessões. Aqui elas são derivadas. Três razões, em ordem de peso:
//
//  1. `categoria` é `:string` ABERTO no contrato de propósito. `paineis/wire/out/notificacao.clj` diz:
//     "a inbox é PROJEÇÃO de um evento cujo vocabulário é validado na FONTE (o produtor), não duplicado
//     aqui". Cravar seis rótulos no frontend seria justamente duplicar — um segundo vocabulário, que
//     drifta do primeiro em silêncio. Derivar mantém uma fonte só.
//  2. Aba que nunca tem item é uma forma de mentira. Hoje há UM produtor de fato — `norma_publicada`
//     (legislativo/components/repositorio.clj:879) — mais o fallback `sistema` de
//     paineis/components/repositorio.clj:239 para in-app sem categoria. "Falhas"/"Prazos"/"Sessões"
//     não têm produtor nenhum: seriam abas permanentemente vazias prometendo cobertura inexistente.
//  3. Lista fixa também ESCONDE o futuro: quando a Track IA emitir `falha_transcricao`, uma barra
//     cravada não teria aba para ela e o item sumiria de todo filtro. Derivada, a aba nasce sozinha.
//
// Consequência deliberada: com UMA só categoria presente, a barra é só `Tudo` + `Não lidas` — uma aba de
// categoria idêntica a "Tudo" é redundância, não informação. As abas de categoria só aparecem a partir da
// SEGUNDA categoria distinta no dado. `Tudo` e `Não lidas` ficam sempre (com item na lista) porque não são
// categorias: são lentes sobre o mesmo conjunto.
//
// A ordem das abas de categoria é por RÓTULO, não por chegada: barra que se reordena a cada notificação
// nova troca o alvo do dedo do usuário no meio do toque.

import type { MinhasNotificacoesOut, NotificacaoOut } from "./contrato-paineis.gen";

export type GrupoTemporal = "hoje" | "semana" | "antes";

export interface NotificacaoVista {
  id: string;
  categoria: string;
  assunto: string;
  corpo: string;
  objetoTipo: string;
  objetoId: string;
  criadoEm: string;
  /** Derivado da PRESENÇA do carimbo — o servidor não manda booleano; `lidaEm: null` é o estado. */
  lida: boolean;
  /** Destino do clique. "" quando o tipo de objeto não tem tela — melhor sem link que com link quebrado. */
  href: string;
  /** Rótulo relativo já formatado ("há 20min", "há 2 dias"). "" se o carimbo vier ilegível. */
  quando: string;
  /**
   * O instante ABSOLUTO no fuso da Casa ("18/07/2026 às 23:00"). O rótulo relativo defasa (a tela só
   * recalcula de minuto em minuto) e "há 15h" não diz de que DIA é o aviso — e prazo regimental conta
   * da publicação. Vai no `title`/`<time>` do carimbo: o dado exato existe mesmo quando o relativo erra.
   */
  quandoExato: string;
}

export interface GrupoVista {
  chave: GrupoTemporal;
  rotulo: string;
  itens: NotificacaoVista[];
}

/** Uma aba da barra. `quantidade` é sempre LOCAL (o que aquele filtro vai de fato mostrar). */
export interface FiltroVista {
  chave: string;
  rotulo: string;
  quantidade: number;
}

export interface InboxVista {
  /** Já filtrados por `filtroAtivo`. */
  grupos: GrupoVista[];
  /** Contagem TOTAL de não lidas, vinda do servidor — pode ser maior que o número de itens (teto de 50). */
  naoLidas: number;
  /**
   * Quantas não lidas o servidor conta e a LISTA não tem. `naoLidas` (badge) é a contagem total, sem
   * teto (`contar-nao-lidas` em paineis/db/notificacao_caixa.clj); a lista e as contagens das abas vêm da
   * resposta CORTADA no SQL (`:limit teto-inbox`, 50). Sem este número a tela põe "10 não lidas" no
   * título e "Não lidas 0" na aba a dois centímetros de distância — dois universos exibidos como um só.
   * Derivado do CONTRATO, não de um `50` redigitado aqui: o teto é `^:private` no backend e
   * `MinhasNotificacoesOut` é `:closed`, então o FE não tem como saber que a lista foi cortada a não ser
   * por esta diferença. CARRY: quando o backend declarar o teto (e/ou o total de linhas) em
   * `MinhasNotificacoesOut`, dá para dizer também "mostrando 50 de N" — hoje não dá, e inventar o 50
   * aqui seria um segundo vocabulário driftando do primeiro em silêncio.
   */
  naoLidasForaDaLista: number;
  /**
   * Fatia "truncamento-familia" sitio (b): quantas notificações — LIDAS OU NÃO — o servidor tem que este
   * corte não mostra. `naoLidasForaDaLista` (acima) só enxerga o UNIVERSO das não lidas: um ator com 200
   * lidas + 5 não lidas (as 5 dentro do teto) teria `naoLidasForaDaLista === 0` e a tela concluiria, ERRADO,
   * que nada foi cortado — as 155 lidas escondidas não tinham nenhum sinal. Este campo usa
   * `notificacoesTotal` (o par AUTORITATIVO do servidor, mesmo WHERE da lista — nunca uma dedução
   * client-side comparando `naoLidas` com outra contagem) e por isso cobre exatamente esse buraco.
   */
  totalForaDaLista: number;
  /** Não existe NENHUMA notificação (independe do filtro). */
  vazia: boolean;
  /** As abas a desenhar. `[]` quando não há o que filtrar. */
  filtros: FiltroVista[];
  /** A aba efetivamente aplicada — pode diferir do que foi pedido, se o pedido não existe mais. */
  filtroAtivo: string;
  /** Há notificações, mas nenhuma passa o filtro atual. Distinto de `vazia`: a mensagem é outra. */
  vaziaNoFiltro: boolean;
}

export const FILTRO_TUDO = "tudo";
export const FILTRO_NAO_LIDAS = "nao-lidas";
/** Prefixo que impede colisão entre uma categoria chamada "tudo" e a lente "Tudo". */
export const PREFIXO_CATEGORIA = "cat:";

/**
 * Rótulo humano por categoria. Mapeia SÓ as categorias que existem de fato no backend hoje — inventar
 * rótulo para categoria sem produtor é redigitar vocabulário fictício (a armadilha que já manteve
 * read-model morto verde nesta casa). Categoria nova cai no fallback legível de `rotuloDaCategoria`.
 */
const ROTULOS_CATEGORIA: Record<string, string> = {
  norma_publicada: "Normas publicadas",
  sistema: "Sistema",
};

function rotuloDaCategoria(categoria: string): string {
  const conhecido = ROTULOS_CATEGORIA[categoria];
  if (conhecido) return conhecido;
  const cru = categoria.replace(/[_-]+/g, " ").trim();
  if (cru === "") return "Sem categoria";
  return cru.charAt(0).toUpperCase() + cru.slice(1);
}

function aplicarFiltro(itens: NotificacaoVista[], filtro: string): NotificacaoVista[] {
  if (filtro === FILTRO_NAO_LIDAS) return itens.filter((i) => !i.lida);
  if (filtro.startsWith(PREFIXO_CATEGORIA)) {
    const categoria = filtro.slice(PREFIXO_CATEGORIA.length);
    return itens.filter((i) => i.categoria === categoria);
  }
  return itens;
}

function derivarFiltros(itens: NotificacaoVista[]): FiltroVista[] {
  if (itens.length === 0) return [];
  const base: FiltroVista[] = [
    { chave: FILTRO_TUDO, rotulo: "Tudo", quantidade: itens.length },
    { chave: FILTRO_NAO_LIDAS, rotulo: "Não lidas", quantidade: itens.filter((i) => !i.lida).length },
  ];
  const categorias = Array.from(new Set(itens.map((i) => i.categoria)));
  // 1 categoria => a aba seria clone de "Tudo". Só a partir da 2ª ela informa alguma coisa.
  if (categorias.length < 2) return base;
  const abas = categorias
    .map((c) => ({
      chave: PREFIXO_CATEGORIA + c,
      rotulo: rotuloDaCategoria(c),
      quantidade: itens.filter((i) => i.categoria === c).length,
    }))
    .sort((a, b) => a.rotulo.localeCompare(b.rotulo, "pt-BR"));
  return [...base, ...abas];
}

const ROTULOS: Record<GrupoTemporal, string> = {
  hoje: "Hoje",
  // "Esta semana" prometia semana de CALENDÁRIO e o código media os últimos 7 dias — numa segunda-feira
  // o título absorvia a semana anterior inteira. O rótulo agora diz o que de fato se mede.
  semana: "Últimos 7 dias",
  antes: "Antes",
};

const ORDEM: GrupoTemporal[] = ["hoje", "semana", "antes"];

const MS_HORA = 3_600_000;
const MS_DIA = 24 * MS_HORA;

/**
 * O fuso da CASA, espelhando `apps/backend .../kernel/tempo.clj` (`ZoneId/of "America/Fortaleza"`).
 * "Hoje" é o dia de calendário da Casa, NUNCA o do aparelho do vereador nem uma janela de 24h.
 */
const FUSO_DA_CASA = "America/Fortaleza";

/** "2026-07-19" — o DIA na Casa. `en-CA` porque rende ISO (AAAA-MM-DD) já ordenável e subtraível. */
const DIA_NA_CASA = new Intl.DateTimeFormat("en-CA", {
  timeZone: FUSO_DA_CASA,
  year: "numeric",
  month: "2-digit",
  day: "2-digit",
});

/** "18/07/2026, 23:00" na Casa — vira "18/07/2026 às 23:00" em `quandoExato`. */
const INSTANTE_NA_CASA = new Intl.DateTimeFormat("pt-BR", {
  timeZone: FUSO_DA_CASA,
  day: "2-digit",
  month: "2-digit",
  year: "numeric",
  hour: "2-digit",
  minute: "2-digit",
});

function diaNaCasa(iso: string): string | null {
  const t = Date.parse(iso);
  return Number.isNaN(t) ? null : DIA_NA_CASA.format(t);
}

/** Diferença em DIAS de calendário entre dois "AAAA-MM-DD" (meia-noite UTC dos dois lados: exata). */
function distanciaEmDias(diaMaisNovo: string, diaMaisVelho: string): number {
  return Math.round((Date.parse(`${diaMaisNovo}T00:00:00Z`) - Date.parse(`${diaMaisVelho}T00:00:00Z`)) / MS_DIA);
}

function instanteNaCasa(iso: string): string {
  const t = Date.parse(iso);
  if (Number.isNaN(t)) return "";
  return INSTANTE_NA_CASA.format(t).replace(",", " às");
}

/**
 * Rota da tela de destino por tipo de objeto, DENTRO do shell do vereador.
 * Sem destino acessível -> "" (sem link, nunca link quebrado).
 *
 * Hoje nenhum tipo tem destino: `proposicao` apontava para /ficha-materia/:id, que é tela do shell
 * do SERVIDOR e cujo endpoint (GET /legislativo/proposicoes/:id/ficha — "leitura interna, servidor")
 * responde 403 ao papel `vereador`. Provado ao vivo na Task 12. Uma âncora que erra é pior que a
 * ausência dela, então a inbox informa sem prometer navegação que não existe.
 *
 * CARRY: quando a fatia "ficha da minha matéria no shell do vereador" existir, `proposicao` ganha uma
 * entrada em ROTAS_POR_TIPO — é o único ponto a mudar.
 *
 * A tabela é vazia HOJE e mesmo assim explícita: é ela que faz o `?? ""` ser uma guarda de verdade
 * (tipo sem rota -> sem link) em vez de um `return ""` que passa com qualquer implementação.
 */
const ROTAS_POR_TIPO: Record<string, string | undefined> = {};

function hrefDoObjeto(objetoTipo: string): string {
  return ROTAS_POR_TIPO[objetoTipo] ?? "";
}

/**
 * O grupo é por DIA DE CALENDÁRIO na Casa, não por janela móvel de milissegundos. Um aviso de ontem às
 * 23h aparecia sob "Hoje" às 12h de hoje (delta 13h < 24h) — e prazo regimental conta da publicação,
 * então o rótulo errado custa um dia. Dia do item no futuro (relógio adiantado) cai em "hoje", que é
 * onde o usuário vai procurar; nunca some da tela. Carimbo ilegível cai em "antes" — nunca lança.
 */
function grupoDe(criadoEm: string, agoraIso: string): GrupoTemporal {
  const hoje = diaNaCasa(agoraIso);
  const doItem = diaNaCasa(criadoEm);
  if (hoje === null || doItem === null) return "antes";
  const dias = distanciaEmDias(hoje, doItem);
  if (dias <= 0) return "hoje";
  if (dias < 7) return "semana";
  return "antes";
}

function quandoRelativo(criadoEm: string, agoraIso: string): string {
  const bruto = Date.parse(agoraIso) - Date.parse(criadoEm);
  if (Number.isNaN(bruto)) return "";
  const delta = Math.max(0, bruto);
  if (delta < MS_HORA) {
    const min = Math.max(1, Math.floor(delta / 60_000));
    return `há ${min}min`;
  }
  if (delta < MS_DIA) {
    const h = Math.floor(delta / MS_HORA);
    return `há ${h}h`;
  }
  const d = Math.floor(delta / MS_DIA);
  return d === 1 ? "há 1 dia" : `há ${d} dias`;
}

function paraVista(n: NotificacaoOut, agoraIso: string): NotificacaoVista {
  return {
    id: n.id,
    categoria: n.categoria,
    assunto: n.assunto,
    corpo: n.corpo,
    objetoTipo: n.objetoTipo,
    objetoId: n.objetoId,
    criadoEm: n.criadoEm,
    lida: n.lidaEm != null,
    href: hrefDoObjeto(n.objetoTipo),
    quando: quandoRelativo(n.criadoEm, agoraIso),
    quandoExato: instanteNaCasa(n.criadoEm),
  };
}

/**
 * `dados` (MinhasNotificacoesOut) -> a inbox derivada. `dados` ausente (fetch ainda não resolveu) ->
 * estrutura vazia coerente, nunca lança. A ORDEM dentro de cada grupo é a do servidor (mais recentes
 * primeiro, garantida pelo ORDER BY do SQL) — não reordenamos aqui.
 */
export function derivarInbox(
  dados: MinhasNotificacoesOut | null | undefined,
  agoraIso: string = new Date().toISOString(),
  filtro: string = FILTRO_TUDO
): InboxVista {
  const itens = (dados?.notificacoes ?? []).map((n) => paraVista(n, agoraIso));
  const filtros = derivarFiltros(itens);
  // Filtro pedido que não existe mais (categoria que sumiu do dado, estado obsoleto) NÃO esvazia a tela:
  // cai em "tudo". Falhar aberto aqui é o certo — o usuário perde o filtro, não a inbox.
  const filtroAtivo = filtros.some((f) => f.chave === filtro) ? filtro : FILTRO_TUDO;
  const visiveis = aplicarFiltro(itens, filtroAtivo);
  const grupos: GrupoVista[] = ORDEM.map((chave) => ({
    chave,
    rotulo: ROTULOS[chave],
    itens: visiveis.filter((i) => grupoDe(i.criadoEm, agoraIso) === chave),
  })).filter((g) => g.itens.length > 0);
  const naoLidas = dados?.naoLidas ?? 0;
  // Fatia "truncamento-familia": `notificacoesTotal` chega SEMPRE que `dados` chega (o contrato o exige) —
  // o `??` cobre só o instante em que `dados` ainda é null (fetch em voo), nunca um valor ausente do servidor.
  const notificacoesTotal = dados?.notificacoesTotal ?? itens.length;
  return {
    grupos,
    naoLidas,
    // `Math.max(0, …)`: o servidor conta as não lidas numa query separada da listagem; uma marcação em
    // voo pode fazer o total chegar MENOR que o local por um instante, e número negativo na tela é pior
    // que silêncio.
    naoLidasForaDaLista: Math.max(0, naoLidas - itens.filter((i) => !i.lida).length),
    totalForaDaLista: Math.max(0, notificacoesTotal - itens.length),
    vazia: itens.length === 0,
    filtros,
    filtroAtivo,
    vaziaNoFiltro: itens.length > 0 && visiveis.length === 0,
  };
}
