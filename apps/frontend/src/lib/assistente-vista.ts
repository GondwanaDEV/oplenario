// Lógica pura do ASSISTENTE DA CASA (Faixa B / B.3). A tela pergunta ao core (POST /api/agente/perguntas), que emite
// a credencial delegada da execução, chama o satélite e devolve a conversa em SSE: `passo` (cada consulta feita ao
// sistema), `resposta` (texto com citações conferidas) ou `indisponivel` (R-IA-1), e `fim`. Aqui só se decide o
// TEXTO: como contar o que o assistente consultou e de onde veio cada citação.

import { camelizarChaves } from "./boundary";
import { diaLocal, horaLocal } from "./calendario-vista";
import type { CitacaoVista } from "./rascunho-ata-vista";

export type PassoAgente = { ferramenta: string; argumentos: Record<string, unknown>; ok: boolean };

export type RespostaAgente = {
  texto: string;
  citacoes: CitacaoVista[];
  paragrafosSemFonte: number[];
  incerteza: string;
  modelo: string;
  contaminado: boolean;
  /** Feature 8.4: o id da execução NO satélite (o registro da Camada de Confiança o conhece) — é o que o "Reportar
   *  erro" manda. Opcional: core antigo não envia. Não confundir com `Conversa.execucaoId` (o da credencial). */
  execucaoIa?: string;
};

export type PropostaNaConversa = { id: string; titulo: string; ritual: string };

export type Conversa = {
  passos: PassoAgente[];
  /** B.6: as propostas de ato criadas nesta execução — a pessoa confirma na tela, nunca aqui. */
  propostas: PropostaNaConversa[];
  resposta: RespostaAgente | null;
  indisponivel: string | null;
  execucaoId: string | null;
  /** ADR-0024: a linha do histórico desta pergunta e a conversa a que ela pertence (a próxima pergunta continua nela).
   *  Opcionais: core antigo não envia; sem `interacaoId`, a resposta não foi guardada e nem saiu. */
  interacaoId?: string | null;
  conversaId?: string | null;
};

/** O corpo SSE inteiro -> a conversa. Evento desconhecido é ignorado; JSON torto também (a tela nunca quebra). */
export function lerConversa(corpo: string): Conversa {
  const c: Conversa = { passos: [], propostas: [], resposta: null, indisponivel: null, execucaoId: null };
  for (const bloco of corpo.split(/\r?\n\r?\n/)) {
    const nome = /^event: (.+)$/m.exec(bloco)?.[1]?.trim();
    const dado = /^data: (.*)$/m.exec(bloco)?.[1];
    if (!nome || dado === undefined) continue;
    let v: Record<string, unknown>;
    try {
      v = camelizarChaves(JSON.parse(dado)) as Record<string, unknown>;
    } catch {
      continue;
    }
    if (nome === "passo") c.passos.push(v as unknown as PassoAgente);
    else if (nome === "proposta") c.propostas.push({ id: String(v.id), titulo: String(v.titulo ?? ""), ritual: String(v.ritual ?? "") });
    else if (nome === "resposta") c.resposta = v as unknown as RespostaAgente;
    else if (nome === "indisponivel") c.indisponivel = String(v.mensagem ?? "");
    else if (nome === "fim") {
      c.execucaoId = (v.execucaoId as string | undefined) ?? null;
      c.interacaoId = (v.interacaoId as string | undefined) ?? null;
      c.conversaId = (v.conversaId as string | undefined) ?? null;
    }
  }
  return c;
}

const SIGLAS: Record<string, string> = {
  projeto_lei: "PL",
  projeto_lei_complementar: "PLC",
  projeto_resolucao: "PR",
  projeto_decreto_legislativo: "PDL",
  proposta_emenda_lom: "PELOM",
  requerimento: "REQ",
  indicacao: "IND",
  mocao: "MOC",
};

function materia(args: Record<string, unknown>): string {
  const { tipo, sequencial, ano } = args as { tipo?: string; sequencial?: number; ano?: number };
  if (tipo && sequencial && ano) return `${SIGLAS[tipo] ?? tipo} ${sequencial}/${ano}`;
  return "a matéria";
}

const DA_ESPECIE: Record<string, string> = { lei_organica: "da Lei Orgânica", regimento_interno: "do Regimento Interno" };

/** B.5: "o art. 45 do Regimento Interno" — pelo artigo do endereço (art45_par1 → art. 45); a norma, pela espécie. */
function dispositivo(a: Record<string, unknown>): string {
  const art = /^art(\d+)/.exec(String(a.endereco ?? ""))?.[1];
  const onde = DA_ESPECIE[String(a.especie ?? "")] ?? "da norma";
  return `${art ? `o art. ${art}` : "um dispositivo"} ${onde}`;
}

const O_QUE: Record<string, (a: Record<string, unknown>) => string> = {
  situacao_da_materia: (a) => `a situação do ${materia(a)}`,
  tramitacao_da_materia: (a) => `a tramitação do ${materia(a)}`,
  pauta_da_sessao: (a) => (a.sessaoId ?? a["sessao-id"] ? "a pauta da sessão" : "a pauta da próxima sessão"),
  ata_da_sessao: (a) => (a.sessaoId ?? a["sessao-id"] ? "a ata da sessão" : "a ata da última sessão"),
  modelos_de_requerimento: () => "os modelos de requerimento da Casa",
  buscar_dispositivos: (a) => `as normas da Casa sobre “${String(a.consulta ?? "")}”`,
  ler_dispositivo: dispositivo,
};

/** B.6: passos de ATO — o agente só prepara a proposta; o rótulo nunca diz que o ato foi feito. */
const ATOS: Record<string, { ok: string; falha: string }> = {
  protocolar_requerimento: {
    ok: "Preparou uma proposta de requerimento — nada foi protocolado",
    falha: "Não conseguiu preparar a proposta de requerimento",
  },
};

/** "Consultou a situação do PL 12/2026" / "Não encontrou …". */
export function rotuloDoPasso(p: PassoAgente): string {
  const ato = ATOS[p.ferramenta];
  if (ato) return p.ok ? ato.ok : ato.falha;
  const o = O_QUE[p.ferramenta]?.(p.argumentos ?? {}) ?? `"${p.ferramenta}"`;
  return p.ok ? `Consultou ${o}` : `Não encontrou ${o}`;
}

/** A citação aponta `ferramenta:<nome>#<n>` (o n-ésimo passo): vira o rótulo do passo que a sustenta. Citação de
 *  dispositivo (`norma:<id>#<endereço>`) já vem com o rótulo do satélite — "Regimento Interno, art. 45, § 1º
 *  (consolidada até 30/06/2026)" — e fica como veio. */
export function comRotuloDoPasso(c: CitacaoVista, passos: PassoAgente[]): CitacaoVista {
  const m = /^ferramenta:([a-z_]+)#(\d+)$/.exec(c.fonteId);
  const p = m ? passos[Number(m[2]) - 1] : undefined;
  if (!p) return c;
  const o = O_QUE[p.ferramenta]?.(p.argumentos ?? {});
  return { ...c, rotulo: o ? o.charAt(0).toUpperCase() + o.slice(1) : c.rotulo };
}

export function mensagemDeErroAssistente(status: number): string {
  if (status === 400) return "Escreva a pergunta com 2 a 1000 caracteres.";
  if (status === 403) return "A Clara não está disponível para o seu acesso nesta Casa.";
  return "Não foi possível falar com a Clara agora. Siga pela tela.";
}

/** R-IA-1 em forma de sinal: o título diz o que houve (a cota da Casa é um caso à parte), o texto é o que o core mandou.
 *  `guardada`: a pergunta entrou no histórico mesmo sem resposta (o `fim` trouxe o id da interação). */
export function sinalDeIndisponivel(mensagem: string, guardada: boolean): { titulo: string; texto: string } {
  const cota = /cota/i.test(mensagem);
  const titulo = cota ? "A cota de IA da Casa deste mês acabou" : "A Clara está indisponível agora";
  const resto = mensagem.startsWith(titulo) ? mensagem.slice(titulo.length).replace(/^[.\s]+/, "") : mensagem;
  return { titulo, texto: [resto || "Siga pela tela: nada do seu trabalho depende dela.", guardada ? "Sua pergunta ficou no histórico, sem resposta." : ""].filter(Boolean).join(" ") };
}

// ---------------------------------------------------------------------------
// ADR-0024: o histórico da Clara (GET /api/agente/historico e /api/agente/conversas/:id)
// ---------------------------------------------------------------------------

export type ItemHistorico = {
  id: string;
  conversaId: string;
  identidadeId?: string;
  /** Só vem quando quem lê é o auditor, olhando o histórico de outra pessoa. */
  nome?: string | null;
  pergunta: string;
  desfecho: "resposta" | "indisponivel" | string;
  ocorridoEm: string;
  nFontes: number;
  nPropostas: number;
};

export type InteracaoGuardada = {
  id: string;
  pergunta: string;
  desfecho: "resposta" | "indisponivel" | string;
  resposta: Omit<RespostaAgente, "modelo" | "execucaoIa"> | null;
  passos: PassoAgente[];
  propostas: PropostaNaConversa[];
  modelo: string | null;
  execucaoIa: string | null;
  ocorridoEm: string;
  conteudoSha256: string;
  /** O hash recalculado pelo servidor sobre a linha lida confere com o gravado. */
  integra: boolean;
};

export type ConversaGuardada = { conversaId: string; identidadeId: string; nome: string | null; interacoes: InteracaoGuardada[] };

export const SEM_RESPOSTA_GUARDADA = "Ficou sem resposta: a Clara estava indisponível.";

/** A pergunta guardada no formato que a tela de resposta já sabe desenhar. */
export function conversaDaInteracao(i: InteracaoGuardada): Conversa {
  return {
    passos: i.passos ?? [],
    propostas: i.propostas ?? [],
    resposta: i.resposta ? { ...i.resposta, modelo: i.modelo ?? "", execucaoIa: i.execucaoIa ?? undefined } : null,
    indisponivel: i.desfecho === "resposta" ? null : SEM_RESPOSTA_GUARDADA,
    execucaoId: null,
    interacaoId: i.id,
  };
}

const NOMES_DO_DIA = ["Domingo", "Segunda-feira", "Terça-feira", "Quarta-feira", "Quinta-feira", "Sexta-feira", "Sábado"];

/** O rótulo do dia de um instante, no fuso da Casa: "Hoje", "Ontem", "Sexta-feira, 03/10" (na semana) ou "03/10/2026". */
export function rotuloDoDia(iso: string, agora: Date = new Date()): string {
  const dia = diaLocal(iso);
  const hoje = diaLocal(agora.toISOString());
  if (!dia || !hoje) return "";
  const d = Date.UTC(+dia.slice(0, 4), +dia.slice(5, 7) - 1, +dia.slice(8, 10));
  const h = Date.UTC(+hoje.slice(0, 4), +hoje.slice(5, 7) - 1, +hoje.slice(8, 10));
  const dias = Math.round((h - d) / 86_400_000);
  if (dias === 0) return "Hoje";
  if (dias === 1) return "Ontem";
  const ddmm = `${dia.slice(8, 10)}/${dia.slice(5, 7)}`;
  if (dias > 1 && dias < 7) return `${NOMES_DO_DIA[new Date(d).getUTCDay()]}, ${ddmm}`;
  return `${ddmm}/${dia.slice(0, 4)}`;
}

/** A lista (já na ordem do servidor, a mais recente primeiro) agrupada por dia, sem reordenar. */
export function agruparPorDia(itens: ItemHistorico[], agora: Date = new Date()): { rotulo: string; itens: ItemHistorico[] }[] {
  const grupos: { rotulo: string; itens: ItemHistorico[] }[] = [];
  for (const it of itens) {
    const rotulo = rotuloDoDia(it.ocorridoEm, agora);
    const ultimo = grupos[grupos.length - 1];
    if (ultimo && ultimo.rotulo === rotulo) ultimo.itens.push(it);
    else grupos.push({ rotulo, itens: [it] });
  }
  return grupos;
}

/** "10h42 · 3 fontes · 1 proposta" — a linha de dados de um item do histórico. */
export function metaDoItem(it: ItemHistorico): string {
  const partes = [horaLocal(it.ocorridoEm) ?? ""];
  if (it.desfecho !== "resposta") partes.push("sem resposta");
  else partes.push(it.nFontes === 1 ? "1 fonte" : `${it.nFontes} fontes`);
  if (it.nPropostas > 0) partes.push(it.nPropostas === 1 ? "1 proposta" : `${it.nPropostas} propostas`);
  return partes.filter(Boolean).join(" · ");
}
