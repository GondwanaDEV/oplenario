"use client";

// Acesso ao BALCÃO DE ATENDIMENTO AO CIDADÃO (6.1 e-SIC, 6.2 ouvidoria, 5.10 LGPD). Leitura: as filas
// (GET /api/atendimento/{esic,ouvidoria,lgpd}?situacao=) e o detalhe (GET /api/atendimento/{especie}/{id}). Escrita: as
// rotas de servidor que já existiam no módulo participacao, com o corpo EXATO que cada uma aceita:
//   · responder  — POST /api/esic/pedidos/{id}/resposta · /api/ouvidoria/manifestacoes/{id}/resposta ·
//                  /api/lgpd/solicitacoes/{id}/resposta, todas {corpo}
//   · indeferir  — POST /api/esic/pedidos/{id}/indeferir · /api/lgpd/solicitacoes/{id}/indeferir, ambas {fundamentacao}
//   · recurso    — POST /api/esic/recursos/{id}/decisao {corpo}
//   · prorrogar  — POST /api/esic/pedidos/{id}/prorrogar · /api/ouvidoria/manifestacoes/{id}/prorrogar, {justificativa}
//   · arquivar   — POST /api/ouvidoria/manifestacoes/{id}/arquivar {motivo}
//   · anexar     — POST /api/atendimento/{especie}/{id}/anexos (multipart, campo `arquivo`, UM por chamada, DEPOIS do ato)
//   · baixar     — GET  /api/atendimento/{especie}/{id}/anexos/{anexo} (a secretaria; o cidadão tem a rota dele)
//   · encarregado — GET/PUT /api/lgpd/encarregado {nome, rotulo, email}
// A authz real é o backend (papel `secretario`); aqui só se traduz cada resposta em frase honesta. Uma rota que falha
// vira erro na tela, nunca uma lista vazia fingindo.

import { useCallback, useEffect, useState } from "react";
import { apiFetch } from "./api-fetch";
import { camelizarChaves } from "./boundary";
import { CAMPO_DO_ANEXO } from "./anexos-do-atendimento";
import { semCredencial } from "./modo";
import { mensagemDeErroAtendimento, type AcaoAtendimento, type Especie, type Situacao } from "./atendimento-vista";
import type {
  AnexoOut,
  DetalheEsicOut,
  DetalheLgpdOut,
  DetalheOuvidoriaOut,
  FilaEsicOut,
  FilaLgpdOut,
  FilaOuvidoriaOut,
} from "./contrato-atendimento.gen";

export type Resultado<T> = { ok: true; dado: T } | { ok: false; status: number; mensagem: string };
export type Carga<T> = { fase: "carregando" } | { fase: "pronto"; dado: T } | { fase: "erro"; status: number; mensagem: string };

export type FilaOut = FilaEsicOut | FilaOuvidoriaOut | FilaLgpdOut;
export type DetalheOut = DetalheEsicOut | DetalheOuvidoriaOut | DetalheLgpdOut;

/** O contato público do encarregado de dados (LGPD art. 41). */
export type Encarregado = { nome: string; rotulo: string; email: string };

async function pedir<T>(
  token: string | null,
  caminho: string,
  acao: AcaoAtendimento,
  init: { method?: string; corpo?: unknown; arquivo?: File } = {},
): Promise<Resultado<T>> {
  if (semCredencial(token)) return { ok: false, status: 401, mensagem: mensagemDeErroAtendimento(401, acao) };
  try {
    // multipart (anexo): SEM Content-Type — o navegador põe o boundary
    let extra: RequestInit = {};
    if (init.arquivo) {
      const fd = new FormData();
      fd.append(CAMPO_DO_ANEXO, init.arquivo, init.arquivo.name);
      extra = { body: fd };
    } else if (init.corpo !== undefined) {
      extra = { headers: { "Content-Type": "application/json" }, body: JSON.stringify(init.corpo) };
    }
    const r = await apiFetch(caminho, { token: token ?? undefined, cache: "no-store", method: init.method, ...extra });
    const corpo = await r.json().catch(() => ({}));
    if (!r.ok) {
      const erro = (corpo as { erro?: unknown })?.erro;
      return {
        ok: false,
        status: r.status,
        mensagem: mensagemDeErroAtendimento(r.status, acao, typeof erro === "string" ? erro : undefined),
      };
    }
    return { ok: true, dado: camelizarChaves(corpo) as T };
  } catch {
    return { ok: false, status: 0, mensagem: mensagemDeErroAtendimento(0, acao) };
  }
}

/** Carrega um GET e o refaz sob demanda (`recarregar`) sem piscar. Trocar de caminho mostra "carregando". */
function useCarregar<T>(token: string | null, caminho: string | null, acao: AcaoAtendimento) {
  const [carga, setCarga] = useState<{ caminho: string | null; estado: Carga<T> }>({
    caminho: null,
    estado: { fase: "carregando" },
  });
  const [rev, setRev] = useState(0);
  useEffect(() => {
    if (!caminho || semCredencial(token)) return;
    let vivo = true;
    (async () => {
      const r = await pedir<T>(token, caminho, acao);
      if (vivo)
        setCarga({
          caminho,
          estado: r.ok ? { fase: "pronto", dado: r.dado } : { fase: "erro", status: r.status, mensagem: r.mensagem },
        });
    })();
    return () => {
      vivo = false;
    };
    // `acao` é constante do chamador; refazer só por token, caminho ou pedido explícito
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [token, caminho, rev]);
  const estado: Carga<T> = !caminho
    ? { fase: "carregando" }
    : semCredencial(token)
      ? { fase: "erro", status: 401, mensagem: mensagemDeErroAtendimento(401, acao) }
      : carga.caminho === caminho
        ? carga.estado
        : { fase: "carregando" };
  const recarregar = useCallback(() => setRev((n) => n + 1), []);
  return { estado, recarregar };
}

const enc = encodeURIComponent;

export function useFilaAtendimento(token: string | null, especie: Especie, situacao: Situacao) {
  return useCarregar<FilaOut>(token, `/api/atendimento/${especie}?situacao=${situacao}`, "listar");
}

export function useDetalheAtendimento(token: string | null, especie: Especie | null, id: string | null) {
  return useCarregar<DetalheOut>(token, especie && id ? `/api/atendimento/${especie}/${enc(id)}` : null, "abrir");
}

// ---- as ações (as rotas de escrita que já existiam; o corpo é o que cada uma aceita) ----

const RESPONDER: Record<Especie, (id: string) => string> = {
  esic: (id) => `/api/esic/pedidos/${enc(id)}/resposta`,
  ouvidoria: (id) => `/api/ouvidoria/manifestacoes/${enc(id)}/resposta`,
  lgpd: (id) => `/api/lgpd/solicitacoes/${enc(id)}/resposta`,
};

/** Responder: devolve {respondidaEm}. */
export function responder(token: string | null, especie: Especie, id: string, corpo: string) {
  return pedir<{ respondidaEm: string }>(token, RESPONDER[especie](id), "responder", { method: "POST", corpo: { corpo } });
}

/** Indeferir (recusa fundamentada: LAI art. 11 §1º II no e-SIC; LGPD art. 18 §4º): devolve o instante do ato
 *  (`indeferidoEm` no pedido, `indeferidaEm` na solicitação — a chave segue o gênero do estado; aqui já normalizado). */
export async function indeferir(
  token: string | null,
  especie: "esic" | "lgpd",
  id: string,
  fundamentacao: string,
): Promise<Resultado<{ indeferidoEm: string }>> {
  const caminho = especie === "esic" ? `/api/esic/pedidos/${enc(id)}/indeferir` : `/api/lgpd/solicitacoes/${enc(id)}/indeferir`;
  const r = await pedir<{ indeferidoEm?: string; indeferidaEm?: string }>(token, caminho, "indeferir", {
    method: "POST",
    corpo: { fundamentacao },
  });
  if (!r.ok) return r;
  return { ok: true, dado: { indeferidoEm: r.dado.indeferidoEm ?? r.dado.indeferidaEm ?? "" } };
}

/** Decidir o recurso do e-SIC: devolve {decididoEm}. */
export function decidirRecurso(token: string | null, recursoId: string, corpo: string) {
  return pedir<{ decididoEm: string }>(token, `/api/esic/recursos/${enc(recursoId)}/decisao`, "decidir-recurso", {
    method: "POST",
    corpo: { corpo },
  });
}

/** Prorrogar (e-SIC: +10 dias, LAI art. 11 §2º; ouvidoria: +30, Lei 13.460): devolve {prorrogadoAte}. */
export function prorrogar(token: string | null, especie: "esic" | "ouvidoria", id: string, justificativa: string) {
  const caminho =
    especie === "esic" ? `/api/esic/pedidos/${enc(id)}/prorrogar` : `/api/ouvidoria/manifestacoes/${enc(id)}/prorrogar`;
  return pedir<{ prorrogadoAte: string }>(token, caminho, "prorrogar", { method: "POST", corpo: { justificativa } });
}

/** Arquivar a manifestação sem resposta de mérito (o motivo é obrigatório): devolve {arquivadaEm}. */
export function arquivar(token: string | null, id: string, motivo: string) {
  return pedir<{ arquivadaEm: string }>(token, `/api/ouvidoria/manifestacoes/${enc(id)}/arquivar`, "arquivar", {
    method: "POST",
    corpo: { motivo },
  });
}

// ---- os anexos da resposta ----

/** O endereço do anexo no balcão (a secretaria). O requerente tem o dele em /portal/meus-protocolos/... */
export const rotaDoAnexoNoBalcao = (especie: Especie, id: string, anexoId: string) =>
  `/api/atendimento/${especie}/${enc(id)}/anexos/${enc(anexoId)}`;

/** Um anexo por chamada, DEPOIS do ato (a rota pede a resposta já gravada). Devolve o anexo criado. */
export function anexar(token: string | null, especie: Especie, id: string, arquivo: File) {
  return pedir<AnexoOut>(token, `/api/atendimento/${especie}/${enc(id)}/anexos`, "anexar", { method: "POST", arquivo });
}

/** Modo DEV (token no header): o link cru não leva o `Authorization`, então baixa pelos bytes e dispara o download aqui
 *  (mesma escolha dos anexos de comunicado). No modo real a tela usa um link direto — o cookie vai junto. */
export async function baixarAnexoComToken(token: string, caminho: string, nome: string): Promise<Resultado<null>> {
  let blob: Blob;
  try {
    const r = await apiFetch(caminho, { token, cache: "no-store" });
    if (!r.ok) return { ok: false, status: r.status, mensagem: mensagemDeErroAtendimento(r.status, "baixar-anexo") };
    blob = await r.blob();
  } catch {
    return { ok: false, status: 0, mensagem: mensagemDeErroAtendimento(0, "baixar-anexo") };
  }
  const url = URL.createObjectURL(blob);
  try {
    const a = document.createElement("a");
    a.href = url;
    a.download = nome;
    document.body.appendChild(a);
    a.click();
    a.remove();
  } finally {
    URL.revokeObjectURL(url);
  }
  return { ok: true, dado: null };
}

// ---- o encarregado de dados da Casa ----

/** GET /api/lgpd/encarregado: o contato atual, ou `null` quando a Casa ainda não definiu (404). */
export function useEncarregado(token: string | null) {
  const [estado, setEstado] = useState<Carga<Encarregado | null>>({ fase: "carregando" });
  useEffect(() => {
    if (semCredencial(token)) return;
    let vivo = true;
    (async () => {
      const r = await pedir<Encarregado>(token, "/api/lgpd/encarregado", "encarregado");
      if (!vivo) return;
      if (r.ok) setEstado({ fase: "pronto", dado: r.dado });
      else if (r.status === 404) setEstado({ fase: "pronto", dado: null });
      else setEstado({ fase: "erro", status: r.status, mensagem: r.mensagem });
    })();
    return () => {
      vivo = false;
    };
  }, [token]);
  return { estado, setEstado };
}

export function salvarEncarregado(token: string | null, e: Encarregado) {
  return pedir<Encarregado>(token, "/api/lgpd/encarregado", "salvar-encarregado", {
    method: "PUT",
    corpo: { nome: e.nome.trim(), rotulo: e.rotulo.trim(), email: e.email.trim() },
  });
}
