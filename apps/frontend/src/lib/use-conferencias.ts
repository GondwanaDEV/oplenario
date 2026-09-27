"use client";

// Acesso à conferência das proposições (Faixa B / B.8, ADR-0013): a fila de notas técnicas da IA
// (GET /api/legislativo/notas-tecnicas), uma nota, a decisão da secretaria (aproveitar ou descartar) e o agente
// institucional que o administrador da Casa liga e desliga (/api/identidade/agentes-institucionais). A authz real é
// o backend: a fila é do "secretario"; ligar e desligar, do "admin_ente".

import { useCallback, useEffect, useState } from "react";
import { apiFetch } from "./api-fetch";
import { camelizarChaves } from "./boundary";
import { mensagemDeErroConferencias, type AgenteInstitucional, type FiltroFila } from "./conferencias-vista";
import type { NotaTecnicaOut, NotasTecnicasOut } from "./contrato-legislativo.gen";

type Resultado<T> = { ok: true; dado: T } | { ok: false; status: number; mensagem: string };

async function pedir<T>(token: string | null, caminho: string, init: RequestInit = {}): Promise<Resultado<T>> {
  try {
    const r = await apiFetch(caminho, {
      token: token ?? undefined,
      cache: "no-store",
      ...init,
      headers: init.body ? { "Content-Type": "application/json" } : undefined,
    });
    const corpo = await r.json().catch(() => ({}));
    if (r.ok) return { ok: true, dado: camelizarChaves(corpo) as T };
    return { ok: false, status: r.status, mensagem: mensagemDeErroConferencias(r.status, (corpo as { erro?: string }).erro) };
  } catch {
    return { ok: false, status: 0, mensagem: mensagemDeErroConferencias(0) };
  }
}

export type Carga<T> = { fase: "carregando" } | { fase: "pronto"; dado: T } | { fase: "erro"; status: number; mensagem: string };

function useCarregar<T>(token: string | null, caminho: string | null) {
  // o resultado guarda DE QUAL caminho veio: trocar de aba mostra "carregando" até a resposta nova chegar, sem
  // setState síncrono no efeito
  const [carga, setCarga] = useState<{ caminho: string | null; estado: Carga<T> }>({
    caminho: null,
    estado: { fase: "carregando" },
  });
  useEffect(() => {
    if (!caminho) return;
    let vivo = true;
    (async () => {
      const r = await pedir<T>(token, caminho);
      if (vivo)
        setCarga({
          caminho,
          estado: r.ok ? { fase: "pronto", dado: r.dado } : { fase: "erro", status: r.status, mensagem: r.mensagem },
        });
    })();
    return () => {
      vivo = false;
    };
  }, [token, caminho]);
  const estado: Carga<T> = carga.caminho === caminho ? carga.estado : { fase: "carregando" };
  const setEstado = useCallback((e: Carga<T>) => setCarga({ caminho, estado: e }), [caminho]);
  return { estado, setEstado };
}

export function useNotasTecnicas(token: string | null, filtro: FiltroFila) {
  return useCarregar<NotasTecnicasOut>(token, `/api/legislativo/notas-tecnicas?estado=${filtro}`);
}

export function useNotaTecnica(token: string | null, id: string | null) {
  return useCarregar<NotaTecnicaOut>(token, id ? `/api/legislativo/notas-tecnicas/${encodeURIComponent(id)}` : null);
}

export function decidirNota(token: string | null, id: string, desfecho: "aproveitada" | "descartada", texto?: string) {
  return pedir<NotaTecnicaOut>(token, `/api/legislativo/notas-tecnicas/${encodeURIComponent(id)}/decisao`, {
    method: "POST",
    body: JSON.stringify({ desfecho, ...(texto ? { texto } : {}) }),
  });
}

export function useAgentesInstitucionais(token: string | null) {
  return useCarregar<{ itens: AgenteInstitucional[] }>(token, "/api/identidade/agentes-institucionais");
}

export function mudarAgente(token: string | null, agente: string, ligar: boolean) {
  return pedir<{ itens: AgenteInstitucional[] }>(
    token,
    `/api/identidade/agentes-institucionais/${encodeURIComponent(agente)}/concessao`,
    { method: ligar ? "PUT" : "DELETE" },
  );
}
