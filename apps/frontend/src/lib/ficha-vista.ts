// View-model puro da ficha PÚBLICA da matéria (Task 3.1, Fatia A2.3 — Portal do Cidadão). Compõe a mesma
// derivação de referência/situação/estágios de materia-vista.ts (derivarRef/derivarTramitacao) + a
// ligação à norma publicada (FichaOut.norma — presente só quando a proposição "virou lei") + os
// comentários já aprovados (lista pública, mesma disciplina fail-closed do resto do módulo).
//
// ComentarioOut: hand-rolled — `contrato-portal.gen.ts` (Task 0.2) não cobre a lista pública de
// comentários (`participacao/wire/out/comentario.clj`, `PublicoOut`) porque o codegen daquela fatia
// apontou só aos schemas de transparência/e-SIC/LGPD, não a `participacao`. Mesma disciplina de
// `ItemBoardOut` em use-mesa.ts: interface hand-rolled com comentário explicando a origem, espelhando o
// schema Malli real (`PublicoOut`: id/corpo/criado-em — closed, SEM autor: a lista pública nunca expõe
// quem comentou, só o conteúdo já aprovado).
export interface ComentarioOut {
  id: string;
  corpo: string;
  criadoEm: string;
}

import type { FichaOut } from "./contrato-portal.gen";
import { situacaoDoDesfecho } from "./desfecho-vista";
import { derivarRef } from "./materia-vista";
import { derivarTramitacao, faixaDoRito, type EstagioTramitacao } from "./tramitacao-vista";

export type NormaPublicadaVista = {
  normaId: string;
  urn: string;
  ementa: string;
  publicadoEm: string;
  tipoNorma: string;
  numero: number;
  ano: number;
  // há texto publicado para baixar? (o servidor diz; sem isso o link levaria a um 404)
  temTexto: boolean;
};

export type ComentarioVista = {
  id: string;
  corpo: string;
  criadoEm: string;
};

export type FichaVista = {
  ref: string;
  titulo: string;
  situacao: string;
  permalink: string;
  proposicaoId: string;
  autorTexto: string | null;
  estagios: EstagioTramitacao[];
  normaPublicada: NormaPublicadaVista | null;
  // Faixa A / A.8b: o resumo em linguagem simples PUBLICADO pela Casa (null = ainda não publicou)
  resumo: ResumoVista | null;
  comentarios: ComentarioVista[];
};

export type ResumoVista = { paragrafos: string[]; geradoComIa: boolean; publicadoEm: string };

/**
 * A faixa "Onde este projeto está" pelo RITO da Casa (a rota devolve as etapas em ordem, com o nome que a Casa deu), ou
 * null quando o rito não vale e a tela usa o mapa fixo por nome de estado (o comportamento anterior):
 *  - o desfecho depois do plenário (do autógrafo em diante) decide a faixa — o rito não sabe do Executivo nem da lei;
 *  - sem rito (matéria sem evento com rito), ou rito que não declara a etapa atual: mapa fixo;
 *  - a etapa atual do rito tem de ser a do estado da matéria: o portal guarda o último rito junto com o estado, mas um
 *    rito de outra etapa (corrida entre eventos) apontaria a etapa errada, então não se confia nele.
 * Só a FAIXA vem daqui. O selo de situação (nome da etapa) segue o caminho próprio dele.
 */
function faixaPeloRito(ficha: FichaOut): EstagioTramitacao[] | null {
  if (situacaoDoDesfecho(ficha.desfecho)) return null;
  const rito = ficha.rito?.atual?.chave === ficha.estado ? ficha.rito : null;
  return faixaDoRito(rito)?.estagios ?? null;
}

/** `etapaAtual`: o nome que o rito da Casa dá à etapa atual, como "Por onde a matéria passou" o mostra (a movimentação
 * mais recente, já projetada em `transparencia.materia_movimentacao`). O chip diz o mesmo; a partir do autógrafo vale
 * o desfecho; sem etapa nomeada, o rótulo fixo. A faixa vem do rito da Casa quando a ficha o traz (`faixaPeloRito`);
 * senão, do mapa fixo. */
export function derivarFicha(
  ficha: FichaOut,
  comentarios: ComentarioOut[] | null,
  etapaAtual?: string | null,
): FichaVista {
  const { estagios: estagiosFixos, rotuloSituacao } = derivarTramitacao(ficha.estado, ficha.desfecho);
  const estagios = faixaPeloRito(ficha) ?? estagiosFixos;
  const nomeDaCasa = etapaAtual?.trim();
  return {
    ref: derivarRef(ficha),
    titulo: ficha.ementa,
    situacao: situacaoDoDesfecho(ficha.desfecho) || !nomeDaCasa ? rotuloSituacao : nomeDaCasa,
    permalink: ficha.urnLex,
    proposicaoId: ficha.proposicaoId,
    autorTexto: ficha.autorTexto ?? null,
    estagios,
    normaPublicada: ficha.norma
      ? {
          normaId: ficha.norma.normaId,
          urn: ficha.norma.urn,
          ementa: ficha.norma.ementa,
          publicadoEm: ficha.norma.publicadoEm,
          tipoNorma: ficha.norma.tipoNorma,
          numero: ficha.norma.numero,
          ano: ficha.norma.ano,
          temTexto: ficha.norma.temTexto,
        }
      : null,
    resumo: ficha.resumo
      ? {
          paragrafos: ficha.resumo.texto.split(/\n\s*\n/).map((p) => p.trim()).filter(Boolean),
          geradoComIa: ficha.resumo.geradoComIa,
          publicadoEm: ficha.resumo.publicadoEm,
        }
      : null,
    // fail-closed: `comentarios` chega `null` quando o fetch daquela seção degradou (Global Constraints —
    // "degradação por seção") — nunca lança, nunca finge um comentário; vira lista vazia honesta.
    comentarios: (comentarios ?? []).map((c) => ({ id: c.id, corpo: c.corpo, criadoEm: c.criadoEm })),
  };
}
