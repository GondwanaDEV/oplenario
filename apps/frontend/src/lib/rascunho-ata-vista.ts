// Lógica pura do RASCUNHO DA ATA pela IA (Faixa A / A.6b). O rascunho vive na IA; a tela só decide o TEXTO: em que
// pé está o pedido, por que revisar com atenção (Camada de Confiança, §16.8), e como mostrar cada citação conferida
// ao lado do parágrafo que ela sustenta — e o parágrafo que não tem fonte nenhuma.

import type { AtaRascunhoOut, CitacaoRascunhoOut } from "./contrato-sessoes.gen";

// A mesma marca que o satélite confere (`confianca/citacao.py`): [[id-da-fonte | trecho literal]].
const MARCA = /\[\[\s*([^|\]\s"]+)\s*(?:\|\s*([\s\S]*?)\s*)?\]\]/g;
const PONTO = /\[\s*confirmar\s*:\s*([^\]]+?)\s*\]/gi;

export type Parte =
  | { tipo: "texto"; texto: string }
  | { tipo: "citacao"; n: number; citacao: CitacaoRascunhoOut | null }
  | { tipo: "confirmar"; texto: string };

export type Paragrafo = { semFonte: boolean; partes: Parte[] };

function pontosEm(texto: string): Parte[] {
  const partes: Parte[] = [];
  let desde = 0;
  for (const m of texto.matchAll(PONTO)) {
    if (m.index! > desde) partes.push({ tipo: "texto", texto: texto.slice(desde, m.index) });
    partes.push({ tipo: "confirmar", texto: m[1] });
    desde = m.index! + m[0].length;
  }
  if (desde < texto.length) partes.push({ tipo: "texto", texto: texto.slice(desde) });
  return partes;
}

/** O texto marcado -> parágrafos com as citações numeradas NA ORDEM (a mesma do satélite: casam pela posição na
 * lista) e os pontos a confirmar destacados. Parágrafo = bloco separado por linha em branco; título `#` não conta
 * como parágrafo para o "sem fonte" (igual ao satélite). */
export function paragrafosDoRascunho(texto: string, citacoes: CitacaoRascunhoOut[], semFonte: number[]): Paragrafo[] {
  const sem = new Set(semFonte);
  let n = 0;
  let contados = 0;
  const saida: Paragrafo[] = [];
  for (const bloco of texto.split(/\n[ \t]*\n/)) {
    if (!bloco.trim()) continue;
    const conta = !bloco.trimStart().startsWith("#");
    const partes: Parte[] = [];
    let desde = 0;
    for (const m of bloco.matchAll(MARCA)) {
      partes.push(...pontosEm(bloco.slice(desde, m.index)));
      partes.push({ tipo: "citacao", n: n + 1, citacao: citacoes[n] ?? null });
      n += 1;
      desde = m.index! + m[0].length;
    }
    partes.push(...pontosEm(bloco.slice(desde)));
    saida.push({ semFonte: conta && sem.has(contados), partes: partes.filter((p) => p.tipo !== "texto" || p.texto !== "") });
    if (conta) contados += 1;
  }
  return saida;
}

const MOTIVOS: Record<string, string> = {
  conteudo_de_terceiro: "o texto nasceu da fala transcrita, que pode ter erros de reconhecimento",
  sem_fonte: "há parágrafos sem fonte na transcrição",
  citacao_nao_conferida: "alguma citação não bate com o que foi dito",
  truncado: "o rascunho foi cortado antes do fim",
};

/** Por que revisar com atenção, em linguagem de quem revisa. null = nada a destacar. */
export function avisoDoRascunho(nivel: string, motivos: string[]): string | null {
  if (nivel !== "revisar_com_atencao") return null;
  const frases = motivos.map((m) => MOTIVOS[m]).filter(Boolean);
  return frases.length ? `Revise com atenção: ${frases.join("; ")}.` : "Revise com atenção antes de usar.";
}

export function rotuloDaCitacao(c: CitacaoRascunhoOut | null): string {
  if (!c) return "Fonte não identificada";
  const onde = c.rotulo ?? c.fonteId;
  return c.status === "conferida" ? onde : `${onde} — não confere com a transcrição`;
}

export type Situacao = { titulo: string; detalhe: string; podePedir: boolean; revisar: boolean };

/** Em que pé está o pedido de rascunho. `null` (nunca pediu) -> convite. */
export function situacaoDoRascunho(r: AtaRascunhoOut | null | undefined): Situacao {
  if (!r)
    return {
      titulo: "Rascunho pela IA",
      detalhe: "A IA pode redigir um rascunho a partir da transcrição da sessão. Você revisa, corrige e publica — nada sai sem você.",
      podePedir: true,
      revisar: false,
    };
  if (r.situacao === "solicitado")
    return {
      titulo: "A IA está redigindo o rascunho…",
      detalhe: "Costuma levar alguns minutos. Esta página atualiza sozinha; você pode redigir pela tela enquanto isso.",
      podePedir: false,
      revisar: false,
    };
  if (r.situacao === "falhou")
    return {
      titulo: "A IA não conseguiu redigir o rascunho",
      detalhe: `${r.detalheErro ?? "Motivo não informado."} Siga pela tela ou peça de novo.`,
      podePedir: true,
      revisar: false,
    };
  const pontos = r.nPontosAConfirmar ?? 0;
  const semFonte = r.nParagrafosSemFonte ?? 0;
  const partes = [
    pontos ? `${pontos} ${pontos === 1 ? "ponto" : "pontos"} a confirmar` : null,
    semFonte ? `${semFonte} ${semFonte === 1 ? "parágrafo" : "parágrafos"} sem fonte` : null,
  ].filter(Boolean);
  return {
    titulo: "Rascunho pronto para revisar",
    detalhe: partes.length ? `Antes de usar: ${partes.join(" e ")}.` : "Confira o texto com a transcrição antes de usar.",
    podePedir: true,
    revisar: true,
  };
}
