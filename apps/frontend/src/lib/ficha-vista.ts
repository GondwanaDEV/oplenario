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
import { derivarRef } from "./materia-vista";
import { derivarTramitacao, type EstagioTramitacao } from "./tramitacao-vista";

export type NormaPublicadaVista = {
  normaId: string;
  urn: string;
  ementa: string;
  publicadoEm: string;
  tipoNorma: string;
  numero: number;
  ano: number;
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
  comentarios: ComentarioVista[];
};

export function derivarFicha(ficha: FichaOut, comentarios: ComentarioOut[] | null): FichaVista {
  const { estagios, rotuloSituacao } = derivarTramitacao(ficha.estado);
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
        }
      : null,
    // fail-closed: `comentarios` chega `null` quando o fetch daquela seção degradou (Global Constraints —
    // "degradação por seção") — nunca lança, nunca finge um comentário; vira lista vazia honesta.
    comentarios: (comentarios ?? []).map((c) => ({ id: c.id, corpo: c.corpo, criadoEm: c.criadoEm })),
  };
}
