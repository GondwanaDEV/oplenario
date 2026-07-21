"use client";

// Hook de mutação — POST /api/meu/notificacoes/:id/lida (Onda E fatia 1). Mirror de use-acusar-ciencia.ts
// (vivoRef + enviandoRef, mesmo contrato de erro). Sem corpo: o único dado é o `id` do path; o
// destinatário nunca vem do cliente (a borda resolve do ator, anti-forja por construção).
//
// A operação é IDEMPOTENTE no servidor (COALESCE preserva o primeiro carimbo), então um clique duplo é
// inofensivo — o `enviandoRef` existe só para não disparar dois round-trips simultâneos.

import { useEffect, useRef, useState } from "react";
import { apiFetch } from "./api-fetch";
import { camelizarChaves } from "./boundary";
import type { MarcarLidaOut } from "./contrato-paineis.gen";
import { semCredencial } from "./modo";

type Estado = "ocioso" | "enviando" | "erro";

export function useMarcarLida(token: string | null) {
  const [estado, setEstado] = useState<Estado>("ocioso");
  const [erro, setErro] = useState<string | null>(null);
  const vivoRef = useRef(true);
  const enviandoRef = useRef(false);

  useEffect(() => {
    return () => {
      vivoRef.current = false;
    };
  }, []);

  async function marcar(id: string): Promise<MarcarLidaOut> {
    if (semCredencial(token)) {
      throw new Error("sem token de autenticacao");
    }
    if (!id) {
      // guard de URL: sem isto, `id` vazio geraria POST /api/meu/notificacoes//lida (404 confuso).
      throw new Error("id da notificação ausente");
    }
    if (enviandoRef.current) {
      throw new Error("envio em andamento");
    }
    enviandoRef.current = true;
    setEstado("enviando");
    setErro(null);
    let tratado = false;
    try {
      const r = await apiFetch(`/api/meu/notificacoes/${encodeURIComponent(id)}/lida`, {
        token: token ?? undefined,
        method: "POST",
      });
      if (!r.ok) {
        const corpoErro = await r.json().catch(() => null);
        const msg = corpoErro?.erro ?? `falha ao marcar como lida (status ${r.status})`;
        tratado = true;
        if (vivoRef.current) {
          setEstado("erro");
          setErro(msg);
        }
        throw new Error(msg);
      }
      const dados = camelizarChaves(await r.json()) as MarcarLidaOut;
      if (vivoRef.current) setEstado("ocioso");
      return dados;
    } catch (e) {
      if (vivoRef.current && !tratado) {
        setEstado("erro");
        setErro("falha de rede — tente novamente");
      }
      throw e;
    } finally {
      enviandoRef.current = false;
    }
  }

  return { marcar, estado, erro };
}
