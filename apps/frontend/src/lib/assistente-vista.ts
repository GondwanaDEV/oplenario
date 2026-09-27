// Lógica pura do ASSISTENTE DA CASA (Faixa B / B.3). A tela pergunta ao core (POST /api/agente/perguntas), que emite
// a credencial delegada da execução, chama o satélite e devolve a conversa em SSE: `passo` (cada consulta feita ao
// sistema), `resposta` (texto com citações conferidas) ou `indisponivel` (R-IA-1), e `fim`. Aqui só se decide o
// TEXTO: como contar o que o assistente consultou e de onde veio cada citação.

import { camelizarChaves } from "./boundary";
import type { CitacaoVista } from "./rascunho-ata-vista";

export type PassoAgente = { ferramenta: string; argumentos: Record<string, unknown>; ok: boolean };

export type RespostaAgente = {
  texto: string;
  citacoes: CitacaoVista[];
  paragrafosSemFonte: number[];
  incerteza: string;
  modelo: string;
  contaminado: boolean;
};

export type Conversa = {
  passos: PassoAgente[];
  resposta: RespostaAgente | null;
  indisponivel: string | null;
  execucaoId: string | null;
};

/** O corpo SSE inteiro -> a conversa. Evento desconhecido é ignorado; JSON torto também (a tela nunca quebra). */
export function lerConversa(corpo: string): Conversa {
  const c: Conversa = { passos: [], resposta: null, indisponivel: null, execucaoId: null };
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
    else if (nome === "resposta") c.resposta = v as unknown as RespostaAgente;
    else if (nome === "indisponivel") c.indisponivel = String(v.mensagem ?? "");
    else if (nome === "fim") c.execucaoId = (v.execucaoId as string | undefined) ?? null;
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

const O_QUE: Record<string, (a: Record<string, unknown>) => string> = {
  situacao_da_materia: (a) => `a situação do ${materia(a)}`,
  tramitacao_da_materia: (a) => `a tramitação do ${materia(a)}`,
  pauta_da_sessao: (a) => (a.sessaoId ?? a["sessao-id"] ? "a pauta da sessão" : "a pauta da próxima sessão"),
};

/** "Consultou a situação do PL 12/2026" / "Não encontrou …". */
export function rotuloDoPasso(p: PassoAgente): string {
  const o = O_QUE[p.ferramenta]?.(p.argumentos ?? {}) ?? `"${p.ferramenta}"`;
  return p.ok ? `Consultou ${o}` : `Não encontrou ${o}`;
}

/** A citação aponta `ferramenta:<nome>#<n>` (o n-ésimo passo): vira o rótulo do passo que a sustenta. */
export function comRotuloDoPasso(c: CitacaoVista, passos: PassoAgente[]): CitacaoVista {
  const m = /^ferramenta:([a-z_]+)#(\d+)$/.exec(c.fonteId);
  const p = m ? passos[Number(m[2]) - 1] : undefined;
  if (!p) return c;
  const o = O_QUE[p.ferramenta]?.(p.argumentos ?? {});
  return { ...c, rotulo: o ? o.charAt(0).toUpperCase() + o.slice(1) : c.rotulo };
}

export function mensagemDeErroAssistente(status: number): string {
  if (status === 400) return "Escreva a pergunta com 2 a 1000 caracteres.";
  if (status === 403) return "O assistente é da secretaria e dos vereadores.";
  return "Não foi possível falar com o assistente agora. Siga pela tela.";
}
