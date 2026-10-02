// View-model puro dos COMUNICADOS INTERNOS (ADR-0020) — sem rede e sem DOM, 100% testável: a caixa unificada
// (comunicados + avisos do sistema), a faixa de ciência, o formulário de envio (destinos, alcance, validação) e as
// frases do comunicado, do painel de leitura e dos enviados. Os hooks ficam em use-comunicados.ts; as telas só
// compõem.
//
// ── A CAIXA É UMA SÓ (Eixo 7) ────────────────────────────────────────────────────────────────────────────────────
// Comunicados (`comunicacao`) e avisos (`paineis`) têm donos diferentes; a junção é feita AQUI, por data, e não no
// banco. Os filtros são os quatro da ADR — Tudo / Não lidos / Para ciência / Do sistema — e são FIXOS, ao contrário das
// abas derivadas da antiga inbox do vereador: "Para ciência" e "Do sistema" são lentes sobre a ORIGEM e o DEVER do
// item, não categorias abertas de um produtor, então não há vocabulário a duplicar. As quatro aparecem sempre (com a
// contagem, que pode ser zero), para a barra não mudar de forma sob o dedo de quem usa.
//
// Agrupamento e "há 20min" vêm de notificacoes-vista.ts (o dia de calendário DA CASA, nunca janela de 24 h): os dois
// tipos de item envelhecem pela mesma régua.

import type { MinhasNotificacoesOut } from "./contrato-paineis.gen";
import {
  ORDEM_GRUPOS,
  ROTULOS_GRUPO,
  derivarInbox,
  grupoDe,
  instanteNaCasa,
  paraVista,
  quandoRelativo,
  type GrupoTemporal,
} from "./notificacoes-vista";
import {
  LIMITE_DE_ANEXOS,
  TAMANHO_MAXIMO_DO_ANEXO,
  type CaixaOut,
  type ComunicadoOut,
  type DestinoOut,
  type DestinosOut,
  type ItemDaCaixaOut,
  type ItemEnviadoOut,
  type LinhaDeLeitura,
  type NovoComunicadoIn,
  type ObjetoLigado,
  type TipoDestino,
  type TipoObjeto,
  type TotaisDeLeitura,
} from "./contrato-comunicacao";

// ── formatos (o fuso é o da CASA, como em notificacoes-vista.ts) ──────────────────────────────────────────────────

const FUSO_DA_CASA = "America/Fortaleza";
const DIA_MES = new Intl.DateTimeFormat("pt-BR", { timeZone: FUSO_DA_CASA, day: "2-digit", month: "2-digit" });

/** "05/10" no fuso da Casa. "" se o carimbo vier ilegível. */
export function diaMes(iso: string | null | undefined): string {
  if (!iso) return "";
  const t = Date.parse(iso);
  return Number.isNaN(t) ? "" : DIA_MES.format(t);
}

/** "02/10/2026 às 14:05" no fuso da Casa. */
export function instante(iso: string | null | undefined): string {
  return iso ? instanteNaCasa(iso) : "";
}

/** A marca curta de uma célula ("02/10 às 14:05"). `null` = a marca ainda não existe. */
export function marcaCurta(iso: string | null | undefined): string | null {
  if (!iso) return null;
  const completo = instanteNaCasa(iso);
  if (!completo) return null;
  return completo.replace(/^(\d{2}\/\d{2})\/\d{4}/, "$1");
}

export function plural(n: number, um: string, varios: string): string {
  return n === 1 ? `1 ${um}` : `${n} ${varios}`;
}

/** 820 KB · 1,4 MB — base 1024, vírgula decimal. */
export function tamanhoLegivel(bytes: number): string {
  if (!Number.isFinite(bytes) || bytes < 0) return "";
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${Math.round(bytes / 1024)} KB`;
  return `${(bytes / (1024 * 1024)).toFixed(1).replace(".", ",")} MB`;
}

// ── a caixa ───────────────────────────────────────────────────────────────────────────────────────────────────────

export type FiltroDaCaixa = "tudo" | "nao-lidos" | "ciencia" | "sistema";

export const FILTROS_DA_CAIXA: { chave: FiltroDaCaixa; rotulo: string }[] = [
  { chave: "tudo", rotulo: "Tudo" },
  { chave: "nao-lidos", rotulo: "Não lidos" },
  { chave: "ciencia", rotulo: "Para ciência" },
  { chave: "sistema", rotulo: "Do sistema" },
];

export type CienciaDoItem = { estado: "pendente" | "vencida" | "dada"; rotulo: string };

export type ItemDaCaixaVista = {
  /** Única entre as duas origens (um aviso e um comunicado podem ter o mesmo id). */
  chave: string;
  origem: "comunicado" | "aviso";
  id: string;
  titulo: string;
  /** Comunicado: "De Rita Campos · COM-2026-000123". Aviso: o corpo do aviso. */
  detalhe: string;
  em: string;
  quando: string;
  quandoExato: string;
  lido: boolean;
  /** Só nos comunicados que pedem ciência. */
  ciencia: CienciaDoItem | null;
  /** Comunicado: a tela do comunicado. Aviso: a tela do objeto ("" quando não há tela — melhor sem link). */
  href: string;
};

export type CaixaVista = {
  grupos: { chave: GrupoTemporal; rotulo: string; itens: ItemDaCaixaVista[] }[];
  filtros: { chave: FiltroDaCaixa; rotulo: string; quantidade: number }[];
  filtroAtivo: FiltroDaCaixa;
  /** Não há nada, em filtro nenhum. */
  vazia: boolean;
  /** Há itens, mas nenhum neste filtro. */
  vaziaNoFiltro: boolean;
  /** O total por ler segundo o SERVIDOR (comunicados + avisos) — pode passar do que a lista mostra (teto de 50). */
  naoLidos: number;
  /** "Você tem 2 comunicados aguardando ciência — o mais próximo vence em 05/10." (fatia 3), ou null. */
  faixaCiencia: string | null;
  /** O aviso de que a lista foi cortada no servidor, ou null. */
  avisoDeCorte: string | null;
};

export function cienciaDoItem(i: Pick<ItemDaCaixaOut, "exigeCiencia" | "cienteEm" | "cienciaAte" | "vencido">): CienciaDoItem | null {
  if (!i.exigeCiencia) return null;
  if (i.cienteEm) return { estado: "dada", rotulo: "Ciente" };
  if (i.vencido) return { estado: "vencida", rotulo: i.cienciaAte ? `Ciência vencida em ${diaMes(i.cienciaAte)}` : "Ciência vencida" };
  return { estado: "pendente", rotulo: i.cienciaAte ? `Ciência até ${diaMes(i.cienciaAte)}` : "Pede ciência" };
}

function comunicadoParaItem(c: ItemDaCaixaOut, agoraIso: string, hrefComunicado: (id: string) => string): ItemDaCaixaVista {
  return {
    chave: `comunicado:${c.id}`,
    origem: "comunicado",
    id: c.id,
    titulo: c.assunto,
    detalhe: `De ${c.remetenteNome} · ${c.protocolo}`,
    em: c.enviadoEm,
    quando: quandoRelativo(c.enviadoEm, agoraIso),
    quandoExato: instanteNaCasa(c.enviadoEm),
    lido: !!c.lidoEm,
    ciencia: cienciaDoItem(c),
    href: hrefComunicado(c.id),
  };
}

function passaNoFiltro(i: ItemDaCaixaVista, f: FiltroDaCaixa): boolean {
  if (f === "nao-lidos") return !i.lido;
  if (f === "ciencia") return i.origem === "comunicado" && !!i.ciencia && i.ciencia.estado !== "dada";
  if (f === "sistema") return i.origem === "aviso";
  return true;
}

function instanteMs(iso: string): number {
  const t = Date.parse(iso);
  return Number.isNaN(t) ? 0 : t;
}

/** A faixa do topo da caixa (fatia 3). O prazo "mais próximo" só é dito se ainda está no futuro; os que já venceram
 *  são contados à parte — "vence em 30/09" de um prazo passado seria uma frase errada. */
export function faixaDeCiencia(caixa: CaixaOut | null | undefined, agoraIso: string): string | null {
  if (!caixa) return null;
  const n = Math.max(caixa.pendentesCiencia ?? 0, 0);
  if (n === 0) return null;
  const vencidos = caixa.itens.filter((i) => i.exigeCiencia && !i.cienteEm && i.vencido).length;
  let frase = `Você tem ${plural(n, "comunicado aguardando", "comunicados aguardando")} ciência`;
  const proxima = caixa.proximaCienciaAte;
  if (proxima && instanteMs(proxima) > instanteMs(agoraIso)) frase += ` — o mais próximo vence em ${diaMes(proxima)}`;
  if (vencidos > 0) frase += `; ${vencidos === 1 ? "1 já passou do prazo" : `${vencidos} já passaram do prazo`}`;
  return `${frase}.`;
}

function avisoDeCorte(caixa: CaixaOut | null | undefined, avisos: MinhasNotificacoesOut | null | undefined): string | null {
  const partes: string[] = [];
  if (caixa) {
    const naLista = caixa.itens.filter((i) => !i.lidoEm).length;
    const fora = Math.max(0, (caixa.naoLidos ?? 0) - naLista);
    if (fora > 0) {
      partes.push(fora === 1 ? "1 comunicado não lido mais antigo ficou fora dela" : `${fora} comunicados não lidos mais antigos ficaram fora dela`);
    }
  }
  if (avisos) {
    const inbox = derivarInbox(avisos);
    if (inbox.totalForaDaLista > 0) {
      partes.push(
        inbox.totalForaDaLista === 1
          ? "1 aviso do sistema mais antigo ficou fora dela"
          : `${inbox.totalForaDaLista} avisos do sistema mais antigos ficaram fora dela`,
      );
    } else if (inbox.naoLidasForaDaLista > 0) {
      partes.push(
        inbox.naoLidasForaDaLista === 1
          ? "1 aviso não lido mais antigo ficou fora dela"
          : `${inbox.naoLidasForaDaLista} avisos não lidos mais antigos ficaram fora dela`,
      );
    }
  }
  return partes.length ? `A caixa mostra só os itens mais recentes: ${partes.join("; ")}.` : null;
}

/**
 * Comunicados + avisos → a caixa. Qualquer das duas fontes pode faltar (ainda carregando, ou falhou): a caixa mostra o
 * que tem — a tela diz à parte qual fonte não veio. Ordem: mais recentes primeiro, pelas duas origens juntas.
 */
export function derivarCaixa(
  caixa: CaixaOut | null | undefined,
  avisos: MinhasNotificacoesOut | null | undefined,
  agoraIso: string,
  filtro: FiltroDaCaixa,
  hrefComunicado: (id: string) => string,
): CaixaVista {
  const doComunicado = (caixa?.itens ?? []).map((c) => comunicadoParaItem(c, agoraIso, hrefComunicado));
  const doSistema: ItemDaCaixaVista[] = (avisos?.notificacoes ?? []).map((n) => {
    const v = paraVista(n, agoraIso);
    return {
      chave: `aviso:${v.id}`,
      origem: "aviso",
      id: v.id,
      titulo: v.assunto,
      detalhe: v.corpo,
      em: v.criadoEm,
      quando: v.quando,
      quandoExato: v.quandoExato,
      lido: v.lida,
      ciencia: null,
      href: v.href,
    };
  });
  const todos = [...doComunicado, ...doSistema].sort((a, b) => instanteMs(b.em) - instanteMs(a.em));
  const filtroAtivo: FiltroDaCaixa = FILTROS_DA_CAIXA.some((f) => f.chave === filtro) ? filtro : "tudo";
  const visiveis = todos.filter((i) => passaNoFiltro(i, filtroAtivo));
  return {
    grupos: ORDEM_GRUPOS.map((chave) => ({
      chave,
      rotulo: ROTULOS_GRUPO[chave],
      itens: visiveis.filter((i) => grupoDe(i.em, agoraIso) === chave),
    })).filter((g) => g.itens.length > 0),
    filtros: FILTROS_DA_CAIXA.map((f) => ({ ...f, quantidade: todos.filter((i) => passaNoFiltro(i, f.chave)).length })),
    filtroAtivo,
    vazia: todos.length === 0,
    vaziaNoFiltro: todos.length > 0 && visiveis.length === 0,
    naoLidos: Math.max(0, caixa?.naoLidos ?? 0) + Math.max(0, avisos?.naoLidas ?? 0),
    faixaCiencia: faixaDeCiencia(caixa, agoraIso),
    avisoDeCorte: avisoDeCorte(caixa, avisos),
  };
}

// ── os destinos ───────────────────────────────────────────────────────────────────────────────────────────────────

/** Como o destino se lê numa frase: "setor Jurídico", "Comissão de Finanças", "Rita Campos", "todos os setores". */
export function rotuloDoDestino(d: Pick<DestinoOut, "tipo" | "alvoNome">): string {
  const nome = (d.alvoNome ?? "").trim();
  if (d.tipo === "todos") return "todos os setores";
  if (d.tipo === "setor") return nome ? `setor ${nome}` : "um setor";
  if (d.tipo === "comissao") return nome || "uma comissão";
  return nome || (d.tipo === "vereador" ? "um vereador" : "uma pessoa");
}

/** "Para: setor Jurídico, Comissão de Finanças". */
export function linhaPara(destinos: Pick<DestinoOut, "tipo" | "alvoNome">[]): string {
  return destinos.length ? `Para: ${destinos.map(rotuloDoDestino).join(", ")}` : "";
}

export type TipoDeDestinoVista = { tipo: TipoDestino; rotulo: string; deGrupo: boolean };

export const TIPOS_DE_DESTINO: TipoDeDestinoVista[] = [
  { tipo: "pessoa", rotulo: "Uma pessoa", deGrupo: false },
  { tipo: "vereador", rotulo: "Um vereador", deGrupo: false },
  { tipo: "setor", rotulo: "Um setor", deGrupo: true },
  { tipo: "comissao", rotulo: "Uma comissão", deGrupo: true },
  { tipo: "todos", rotulo: "Todos os setores", deGrupo: true },
];

/** Os tipos que esta pessoa pode usar (Eixo 3: grupo só para secretaria, administração e Mesa). */
export function tiposDisponiveis(podeEnviarAGrupos: boolean): TipoDeDestinoVista[] {
  return TIPOS_DE_DESTINO.filter((t) => podeEnviarAGrupos || !t.deGrupo);
}

export type OpcaoDeAlvo = { id: string; nome: string; membros: number | null };

/** As opções do segundo seletor, para o tipo escolhido. `todos` não tem alvo. */
export function opcoesDoTipo(tipo: TipoDestino, d: DestinosOut | null | undefined): OpcaoDeAlvo[] {
  if (!d) return [];
  const porNome = (a: OpcaoDeAlvo, b: OpcaoDeAlvo) => a.nome.localeCompare(b.nome, "pt-BR");
  if (tipo === "pessoa") return d.pessoas.map((p) => ({ id: p.identidadeId, nome: p.nome, membros: 1 })).sort(porNome);
  if (tipo === "vereador") return d.vereadores.map((v) => ({ id: v.id, nome: v.nome, membros: 1 })).sort(porNome);
  if (tipo === "setor") return d.setores.map((s) => ({ id: s.id, nome: s.nome, membros: s.membros })).sort(porNome);
  if (tipo === "comissao") return d.comissoes.map((c) => ({ id: c.id, nome: c.nome, membros: c.membros })).sort(porNome);
  return [];
}

export type DestinoEscolhido = { tipo: TipoDestino; alvoId: string | null; nome: string | null; membros: number | null };

export function chaveDoDestino(d: Pick<DestinoEscolhido, "tipo" | "alvoId">): string {
  return `${d.tipo}:${d.alvoId ?? ""}`;
}

/** O rótulo do destino escolhido, com a contagem de pessoas nos grupos ("setor Jurídico · 3 pessoas"). */
export function rotuloDoEscolhido(d: DestinoEscolhido): string {
  const base = rotuloDoDestino({ tipo: d.tipo, alvoNome: d.nome });
  if ((d.tipo === "setor" || d.tipo === "comissao") && d.membros !== null) {
    return `${base} · ${plural(d.membros, "pessoa", "pessoas")}`;
  }
  return base;
}

/**
 * "Vai para N pessoas" ANTES do envio — uma ESTIMATIVA a partir das contagens das opções. O número final vem no 201:
 * quem está em dois destinos recebe uma vez, quem envia não entra na própria lista e o vereador sem acesso fica de
 * fora. Por isso "até", salvo no caso exato de uma pessoa só. "Todos os setores" não traz contagem no contrato: a frase
 * o diz por extenso em vez de inventar um número.
 */
export function fraseDoAlcance(escolhidos: DestinoEscolhido[]): string | null {
  if (escolhidos.length === 0) return null;
  const comTodos = escolhidos.some((d) => d.tipo === "todos");
  const n = escolhidos.filter((d) => d.tipo !== "todos").reduce((s, d) => s + Math.max(0, d.membros ?? 0), 0);
  const individual = escolhidos.length === 1 && (escolhidos[0].tipo === "pessoa" || escolhidos[0].tipo === "vereador");
  if (comTodos) {
    return n > 0
      ? `Vai para todos os servidores e a administração da Casa, e até ${plural(n, "pessoa", "pessoas")} pelos outros destinos.`
      : "Vai para todos os servidores e a administração da Casa.";
  }
  if (individual) return "Vai para 1 pessoa.";
  if (n === 0) return "Os destinos escolhidos não têm ninguém por enquanto.";
  return `Vai para até ${plural(n, "pessoa", "pessoas")}.`;
}

// ── o formulário ──────────────────────────────────────────────────────────────────────────────────────────────────

export const LIMITE_DO_ASSUNTO = 200;

export type FormDoComunicado = {
  assunto: string;
  corpo: string;
  destinos: DestinoEscolhido[];
  exigeCiencia: boolean;
  /** O valor de um <input type="datetime-local"> ("2026-10-05T18:00"), no fuso do aparelho; "" = sem prazo. */
  prazo: string;
  objetoTipo: TipoObjeto | "";
  /** O identificador ou o link colado. */
  objetoRef: string;
  anexos: File[];
};

export type CampoDoForm = "destinos" | "assunto" | "corpo" | "prazo" | "objeto" | "anexos";
export type ErrosDoForm = Partial<Record<CampoDoForm, string>>;

const UUID = /[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}/gi;

/** O id do item a partir do que a pessoa colou: o ÚLTIMO uuid de um link ("…/ficha-materia/<id>?token=…"), ou o
 *  próprio texto se ele já é um identificador simples. Texto com espaço ou barra sem uuid = não dá para saber. */
export function extrairIdDoObjeto(texto: string): string | null {
  const t = texto.trim();
  if (!t) return null;
  const achados = t.match(UUID);
  if (achados && achados.length) return achados[achados.length - 1].toLowerCase();
  return /^[A-Za-z0-9_-]+$/.test(t) ? t : null;
}

/** O instante no formato do <input type="datetime-local"> ("2026-10-02T14:05"), no fuso do aparelho — para o `min`. */
export function paraDatetimeLocal(ms: number): string {
  const d = new Date(ms);
  const p = (n: number) => String(n).padStart(2, "0");
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}T${p(d.getHours())}:${p(d.getMinutes())}`;
}

/** O prazo do datetime-local para ISO (UTC). `null` se vazio ou ilegível. */
export function prazoParaIso(valor: string): string | null {
  if (!valor) return null;
  const t = new Date(valor).getTime();
  return Number.isNaN(t) ? null : new Date(t).toISOString();
}

export function validarComunicado(f: FormDoComunicado, agoraMs: number): ErrosDoForm {
  const e: ErrosDoForm = {};
  if (f.destinos.length === 0) e.destinos = "Escolha pelo menos um destinatário.";
  const assunto = f.assunto.trim();
  if (!assunto) e.assunto = "Escreva o assunto: é ele que aparece na caixa de quem recebe.";
  else if (assunto.length > LIMITE_DO_ASSUNTO) e.assunto = `O assunto passa de ${LIMITE_DO_ASSUNTO} caracteres. Resuma e deixe o detalhe no texto.`;
  if (!f.corpo.trim()) e.corpo = "Escreva o texto do comunicado.";
  if (f.exigeCiencia && f.prazo) {
    const iso = prazoParaIso(f.prazo);
    if (!iso) e.prazo = "Informe a data e a hora do prazo, ou deixe em branco para pedir ciência sem prazo.";
    else if (Date.parse(iso) <= agoraMs) e.prazo = "O prazo precisa ser depois de agora.";
  }
  if (f.objetoTipo && !extrairIdDoObjeto(f.objetoRef)) {
    e.objeto = "Cole o endereço da página do item (o link do navegador) ou o identificador dele.";
  }
  const erroAnexo = erroDosAnexos(f.anexos);
  if (erroAnexo) e.anexos = erroAnexo;
  return e;
}

function erroDosAnexos(anexos: File[]): string | null {
  if (anexos.length > LIMITE_DE_ANEXOS) return `São no máximo ${LIMITE_DE_ANEXOS} anexos por comunicado.`;
  const grande = anexos.find((a) => a.size > TAMANHO_MAXIMO_DO_ANEXO);
  return grande ? `“${grande.name}” passa de 10 MB, o limite por anexo.` : null;
}

/** Junta os arquivos escolhidos aos que já estavam, recusando — com o motivo — o que passa do limite. */
export function adicionarAnexos(atuais: File[], novos: File[]): { anexos: File[]; recusados: string[] } {
  const anexos = [...atuais];
  const recusados: string[] = [];
  for (const a of novos) {
    if (anexos.some((x) => x.name === a.name && x.size === a.size)) continue;
    if (a.size > TAMANHO_MAXIMO_DO_ANEXO) {
      recusados.push(`“${a.name}” passa de 10 MB e não foi incluído.`);
      continue;
    }
    if (anexos.length >= LIMITE_DE_ANEXOS) {
      recusados.push(`“${a.name}” não foi incluído: são no máximo ${LIMITE_DE_ANEXOS} anexos.`);
      continue;
    }
    anexos.push(a);
  }
  return { anexos, recusados };
}

export function entradaDoForm(f: FormDoComunicado, substituiId: string | null): NovoComunicadoIn {
  const id = f.objetoTipo ? extrairIdDoObjeto(f.objetoRef) : null;
  return {
    assunto: f.assunto,
    corpo: f.corpo,
    exigeCiencia: f.exigeCiencia,
    cienciaAte: f.exigeCiencia ? prazoParaIso(f.prazo) : null,
    substituiId,
    objeto: f.objetoTipo && id ? { tipo: f.objetoTipo, id } : null,
    destinos: f.destinos.map((d) => ({ tipo: d.tipo, alvoId: d.tipo === "todos" ? null : d.alvoId })),
  };
}

/** O comunicado substituído pré-preenche o substituto: mesmo endereçamento, mesmo texto para corrigir. */
export function formDoSubstituto(c: ComunicadoOut): Pick<FormDoComunicado, "assunto" | "corpo" | "destinos" | "exigeCiencia"> {
  return {
    assunto: c.assunto,
    corpo: c.corpo,
    exigeCiencia: c.exigeCiencia,
    destinos: c.destinos.map((d) => ({ tipo: d.tipo, alvoId: d.alvoId, nome: d.alvoNome, membros: null })),
  };
}

/** Completa os destinos herdados do comunicado substituído com a contagem ATUAL das opções (o alcance da revisão). */
export function enriquecerDestinos(escolhidos: DestinoEscolhido[], d: DestinosOut | null | undefined): DestinoEscolhido[] {
  return escolhidos.map((e) => {
    if (e.tipo === "todos" || !e.alvoId) return e;
    const op = opcoesDoTipo(e.tipo, d).find((o) => o.id === e.alvoId);
    return op ? { ...e, nome: e.nome ?? op.nome, membros: op.membros } : e;
  });
}

/** Depois do 201: o aviso de quem ficou de fora por não ter acesso ao sistema. */
export function fraseSemAcesso(semAcesso: number, temComissao: boolean): string | null {
  if (!semAcesso || semAcesso <= 0) return null;
  const quem = semAcesso === 1 ? "1 vereador" : `${semAcesso} vereadores`;
  const de = temComissao ? (semAcesso === 1 ? " da comissão" : " das comissões") : "";
  const verbo = semAcesso === 1 ? "ainda não tem acesso ao sistema e não vai receber" : "ainda não têm acesso ao sistema e não vão receber";
  return `${quem}${de} ${verbo} este comunicado. O acesso é concedido pelo administrador da Casa.`;
}

// ── o comunicado ──────────────────────────────────────────────────────────────────────────────────────────────────

const ROTULO_OBJETO: Record<TipoObjeto, string> = {
  sessao: "Abrir a sessão",
  proposicao: "Abrir a ficha da matéria",
  protocolo: "Protocolo vinculado",
};

export const NOME_DO_TIPO_DE_OBJETO: Record<TipoObjeto, string> = {
  sessao: "Sessão",
  proposicao: "Proposição",
  protocolo: "Protocolo",
};

/** A tela do item ligado. O protocolo não tem tela própria (o Livro do Expediente é por dia): "" = sem link. */
export function hrefDoObjetoLigado(o: ObjetoLigado | null | undefined): string {
  if (!o) return "";
  if (o.tipo === "proposicao") return `/ficha-materia/${encodeURIComponent(o.id)}`;
  if (o.tipo === "sessao") return `/sessoes/${encodeURIComponent(o.id)}/plenario`;
  return "";
}

export function rotuloDoObjetoLigado(o: ObjetoLigado): string {
  return ROTULO_OBJETO[o.tipo] ?? "Item vinculado";
}

export type EstadoDaCiencia = "nao-exige" | "nao-destinatario" | "pendente" | "vencida" | "dada";

export function estadoDaCiencia(c: ComunicadoOut, agoraMs: number): EstadoDaCiencia {
  if (!c.exigeCiencia) return "nao-exige";
  if (!c.minhasMarcas) return "nao-destinatario";
  if (c.minhasMarcas.cienteEm) return "dada";
  if (c.cienciaAte && Date.parse(c.cienciaAte) < agoraMs) return "vencida";
  return "pendente";
}

/** "12 de 15 leram · 9 cientes · 3 faltam ler · 2 com prazo vencido". */
export function resumoDaLeitura(t: TotaisDeLeitura, exigeCiencia: boolean): string {
  const faltam = Math.max(0, t.destinatarios - t.lidos);
  const partes = [`${t.lidos} de ${t.destinatarios} leram`];
  if (exigeCiencia) partes.push(`${t.cientes} ${t.cientes === 1 ? "ciente" : "cientes"}`);
  partes.push(faltam === 0 ? "ninguém falta ler" : `${faltam} ${faltam === 1 ? "falta" : "faltam"} ler`);
  if (t.pendentesVencidos > 0) partes.push(`${t.pendentesVencidos} com prazo vencido`);
  return partes.join(" · ");
}

/** A tabela de leitura com quem FALTA primeiro: os vencidos, depois quem ainda deve (a ciência, se pedida; senão a
 *  leitura), depois quem já cumpriu. Dentro de cada faixa, a ordem do servidor. */
export function ordenarLinhasDeLeitura(linhas: LinhaDeLeitura[], exigeCiencia: boolean): LinhaDeLeitura[] {
  const faixa = (l: LinhaDeLeitura) => (l.vencido ? 0 : (exigeCiencia ? !l.cienteEm : !l.lidoEm) ? 1 : 2);
  return linhas.map((l, i) => ({ l, i })).sort((a, b) => faixa(a.l) - faixa(b.l) || a.i - b.i).map((x) => x.l);
}

// ── os enviados ───────────────────────────────────────────────────────────────────────────────────────────────────

export function linhaDoEnviado(i: ItemEnviadoOut): { leram: string; cientes: string | null; vencidos: string | null } {
  return {
    leram: `${i.lidos} de ${i.destinatarios} leram`,
    cientes: i.exigeCiencia ? `${i.cientes} de ${i.destinatarios} cientes` : null,
    vencidos: i.pendentesVencidos > 0 ? `${plural(i.pendentesVencidos, "pendente vencido", "pendentes vencidos")}` : null,
  };
}
