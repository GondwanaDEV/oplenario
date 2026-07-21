// View-model puro da inbox (Onda E fatia 1) — 100% testável sem rede e sem DOM: agrupa temporalmente o
// que veio de GET /meu/notificacoes e deriva o estado visual. NENHUM fetch aqui (os hooks
// use-minhas-notificacoes / use-marcar-lida ficam no page.tsx), mesmo padrão de meu-painel-vista.ts.
//
// `agoraIso` é OPCIONAL (default = o relógio real) só para manter a função genuinamente pura — os testes
// fixam o instante explicitamente. Comparação de ISO-8601 como string funciona porque os timestamps do
// backend são sempre UTC 'Z' (mesmo idioma de ficha-materia-vista.ts / mesa-vista.ts).

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
  /** Rótulo relativo já formatado ("há 20min", "há 2 dias"). */
  quando: string;
}

export interface GrupoVista {
  chave: GrupoTemporal;
  rotulo: string;
  itens: NotificacaoVista[];
}

export interface InboxVista {
  grupos: GrupoVista[];
  /** Contagem TOTAL de não lidas, vinda do servidor — pode ser maior que o número de itens (teto de 50). */
  naoLidas: number;
  vazia: boolean;
}

const ROTULOS: Record<GrupoTemporal, string> = {
  hoje: "Hoje",
  semana: "Esta semana",
  antes: "Antes",
};

const ORDEM: GrupoTemporal[] = ["hoje", "semana", "antes"];

const MS_HORA = 3_600_000;
const MS_DIA = 24 * MS_HORA;

/**
 * Rota da tela de destino por tipo de objeto, DENTRO do shell do vereador.
 * Sem destino acessível -> "" (sem link, nunca link quebrado).
 *
 * Hoje nenhum tipo tem destino: `proposicao` apontava para /ficha-materia/:id, que é tela do shell
 * do SERVIDOR e cujo endpoint (GET /legislativo/proposicoes/:id/ficha — "leitura interna, servidor")
 * responde 403 ao papel `vereador`. Provado ao vivo na Task 12. Uma âncora que erra é pior que a
 * ausência dela, então a inbox informa sem prometer navegação que não existe.
 *
 * CARRY: quando a fatia "ficha da minha matéria no shell do vereador" existir, `proposicao` volta a
 * ter destino aqui — é o único ponto a mudar.
 */
function hrefDoObjeto(): string {
  return "";
}

function grupoDe(criadoEm: string, agoraIso: string): GrupoTemporal {
  const delta = Date.parse(agoraIso) - Date.parse(criadoEm);
  if (delta < MS_DIA) return "hoje";
  if (delta < 7 * MS_DIA) return "semana";
  return "antes";
}

function quandoRelativo(criadoEm: string, agoraIso: string): string {
  const delta = Math.max(0, Date.parse(agoraIso) - Date.parse(criadoEm));
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
    href: hrefDoObjeto(),
    quando: quandoRelativo(n.criadoEm, agoraIso),
  };
}

/**
 * `dados` (MinhasNotificacoesOut) -> a inbox derivada. `dados` ausente (fetch ainda não resolveu) ->
 * estrutura vazia coerente, nunca lança. A ORDEM dentro de cada grupo é a do servidor (mais recentes
 * primeiro, garantida pelo ORDER BY do SQL) — não reordenamos aqui.
 */
export function derivarInbox(
  dados: MinhasNotificacoesOut | null | undefined,
  agoraIso: string = new Date().toISOString()
): InboxVista {
  const itens = (dados?.notificacoes ?? []).map((n) => paraVista(n, agoraIso));
  const grupos: GrupoVista[] = ORDEM.map((chave) => ({
    chave,
    rotulo: ROTULOS[chave],
    itens: itens.filter((i) => grupoDe(i.criadoEm, agoraIso) === chave),
  })).filter((g) => g.itens.length > 0);
  return {
    grupos,
    naoLidas: dados?.naoLidas ?? 0,
    vazia: itens.length === 0,
  };
}
