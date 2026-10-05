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
 * A faixa e o selo da ficha pública. Precedência:
 *  1. o desfecho depois do plenário (do autógrafo em diante) decide os dois — o rito não sabe do Executivo nem da lei;
 *  2. senão, o RITO da Casa (a rota devolve as etapas em ordem, com o nome que a Casa deu): a faixa e o selo dizem a
 *     mesma etapa com o mesmo nome. Só vale quando o rito declara a etapa atual E ela é a do estado da matéria: o
 *     portal guarda o último rito junto com o estado, mas um rito de outra etapa (corrida entre eventos) apontaria a
 *     etapa errada, então não se confia nele;
 *  3. senão, o mapa fixo por nome de estado (matéria sem evento com rito, ou rito incoerente) — o comportamento anterior.
 */
function faixaESelo(ficha: FichaOut): { estagios: EstagioTramitacao[]; rotuloSituacao: string } {
  const fixa = derivarTramitacao(ficha.estado, ficha.desfecho);
  if (situacaoDoDesfecho(ficha.desfecho)) return fixa;
  const rito = ficha.rito?.atual?.chave === ficha.estado ? ficha.rito : null;
  const daCasa = faixaDoRito(rito);
  if (!daCasa || !rito?.atual) return fixa;
  return { estagios: daCasa.estagios, rotuloSituacao: rito.atual.rotulo };
}

export function derivarFicha(ficha: FichaOut, comentarios: ComentarioOut[] | null): FichaVista {
  const { estagios, rotuloSituacao } = faixaESelo(ficha);
  return {
    ref: derivarRef(ficha),
    titulo: ficha.ementa,
    situacao: rotuloSituacao,
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
