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
import { categoriaDoDesfecho } from "./desfecho-vista";
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
  const { estagios, rotuloSituacao } = derivarTramitacao(m.estado, m.desfecho);
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
// "Em tramitação agora" não mostra matéria que já saiu do rito: arquivada, rejeitada, retirada ou aprovada sem ato
// depois do plenário, e a que virou lei ou teve o veto mantido. Antes a seção pegava as 4 mais recentes, e a capa
// abria com uma matéria arquivada sob o título "Em tramitação agora". À espera do Executivo ou com o veto por apreciar
// ainda tramita (a categoria "tram" do desfecho).
const ESTADOS_FORA_DA_TRAMITACAO = new Set(["aprovada", "arquivada", "rejeitada", "retirada", "prejudicada"]);

export function emTramitacao(m: Pick<MateriaOut, "estado" | "desfecho">): boolean {
  const categoria = categoriaDoDesfecho(m.desfecho);
  if (categoria) return categoria === "tram";
  return !ESTADOS_FORA_DA_TRAMITACAO.has(m.estado);
}

// O total: com a lista inteira na mão (o backend manda até 200), conta-se as que tramitam; com a lista cortada, não se
// sabe quantas tramitam, e o texto diz só o total da Casa, que é o campo autoritativo (regra 4, nunca deduzido).
const truncamentoTramitacao = (mostradas: number, emCurso: number, total: number, completa: boolean): string | null => {
  if (completa) {
    return emCurso > mostradas
      ? `Mostrando ${mostradas} de ${emCurso} matérias em tramitação, da numeração mais alta para a mais baixa.`
      : null;
  }
  const quais = mostradas === 1 ? "1 matéria" : `${mostradas} matérias`;
  return `Mostrando ${quais} em tramitação, das ${total} da Casa no portal, da numeração mais alta para a mais baixa.`;
};

export function escolherDestaque(
  itens: MateriaOut[],
  materiasTotal: number,
): {
  destaque: MateriaVista | null;
  maisTramitacao: MateriaVista[];
  truncamento: string | null;
} {
  const emCurso = itens.filter(emTramitacao);
  if (emCurso.length === 0) return { destaque: null, maisTramitacao: [], truncamento: null };
  const [primeiro, ...resto] = emCurso;
  const maisTramitacao = resto.slice(0, 3).map(paraVista);
  const mostradas = 1 + maisTramitacao.length;
  return {
    destaque: paraVista(primeiro),
    maisTramitacao,
    // `materiasTotal` (regra 4): o campo autoritativo do backend decide se a lista veio inteira; `itens.length` já
    // vem capado em 200, então só serve de total quando o backend diz que não há mais nada.
    truncamento: truncamentoTramitacao(mostradas, emCurso.length, materiasTotal, materiasTotal <= itens.length),
  };
}
