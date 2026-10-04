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
  if (status === 403) return "O assistente é da secretaria e dos vereadores.";
  return "Não foi possível falar com o assistente agora. Siga pela tela.";
}
