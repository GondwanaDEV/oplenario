// View-model puro da matéria em tramitação (Task 1.1, Fatia A2.1 — Portal do Cidadão): a referência
// curta ("PL 042/2026") + a escolha de "destaque" (1º item) e "mais em tramitação" (próximos 3) a
// partir da listagem pública real (GET /portal/casa/{ente}/materias).
//
// MAPA tipo->sigla: confirmado contra o vocabulário REAL de proposição (`legislativo/logic.clj`, `def
// tipos` — grep 04/07/2026): projeto_lei, projeto_lei_complementar, projeto_resolucao,
// projeto_decreto_legislativo, proposta_emenda_lom, indicacao, requerimento, mocao. PL/PLC são as duas
// siglas que aparecem lado a lado no design-system (portal-cidadao.html: "PL 042/2026", "PLC
// 004/2026") — as demais seguem a convenção legislativa brasileira padrão (PR/PDL/PELOM/IND/REQ/MOC);
// sem uma tela-fonte que as confirme, é best-effort documentado, não [GAP] regulatório (a sigla é só
// rótulo de exibição, nunca persistida). Fail-closed: tipo fora do mapa -> sigla = o tipo cru em
// maiúsculas (nunca lança, nunca inventa uma sigla plausível para algo desconhecido).

import type { MateriaOut } from "./contrato-portal.gen";
import { derivarTramitacao, type EstagioTramitacao } from "./tramitacao-vista";

const SIGLA_POR_TIPO: Record<string, string> = {
  projeto_lei: "PL",
  projeto_lei_complementar: "PLC",
  projeto_resolucao: "PR",
  projeto_decreto_legislativo: "PDL",
  proposta_emenda_lom: "PELOM",
  indicacao: "IND",
  requerimento: "REQ",
  mocao: "MOC",
};

export type MateriaVista = {
  ref: string;
  titulo: string;
  situacao: string;
  permalink: string;
  proposicaoId: string;
  estagios: EstagioTramitacao[];
  autorTexto: string | null;
};

export function derivarRef(m: Pick<MateriaOut, "tipo" | "sequencial" | "ano">): string {
  const sigla = SIGLA_POR_TIPO[m.tipo] ?? m.tipo.toUpperCase();
  return `${sigla} ${String(m.sequencial).padStart(3, "0")}/${m.ano}`;
}

function paraVista(m: MateriaOut): MateriaVista {
  const { estagios, rotuloSituacao } = derivarTramitacao(m.estado);
  return {
    ref: derivarRef(m),
    titulo: m.ementa,
    situacao: rotuloSituacao,
    permalink: m.urnLex,
    proposicaoId: m.proposicaoId,
    estagios,
    autorTexto: m.autorTexto ?? null,
  };
}

// ---- [COPY, reusada literal de perfil-vereador-vista.ts/truncamentoMaterias] frente "truncamento-familia"
//      sitio (a): esta seção É a listagem pública de proposições (sem outra rota — barra-institucional.tsx
//      aponta "Proposições" pra cá) e escolhia so' 1+3 de até 200 vindos do backend, SEM contagem nenhuma.
//      MESMA ordem (ano DESC, sequencial DESC) de `listar-em-tramitacao` — por isso o MESMO texto ("da
//      numeração mais alta para a mais baixa") vale aqui.
const truncamentoTramitacao = (mostradas: number, total: number) =>
  `Mostrando ${mostradas} de ${total} matérias, da numeração mais alta para a mais baixa.`;

export function escolherDestaque(
  itens: MateriaOut[],
  materiasTotal: number,
): {
  destaque: MateriaVista | null;
  maisTramitacao: MateriaVista[];
  truncamento: string | null;
} {
  if (itens.length === 0) return { destaque: null, maisTramitacao: [], truncamento: null };
  const [primeiro, ...resto] = itens;
  const maisTramitacao = resto.slice(0, 3).map(paraVista);
  const mostradas = 1 + maisTramitacao.length;
  return {
    destaque: paraVista(primeiro),
    maisTramitacao,
    // `materiasTotal` (regra 4): SEMPRE o campo autoritativo do backend, NUNCA `itens.length` (que já vem
    // capado em 200 pelo backend) nem qualquer dedução client-side — a comparação certa é contra o total
    // real, senão uma Casa com 250 matérias exibiria "mostrando 4" sem nunca dizer de quantas.
    truncamento: materiasTotal > mostradas ? truncamentoTramitacao(mostradas, materiasTotal) : null,
  };
}
