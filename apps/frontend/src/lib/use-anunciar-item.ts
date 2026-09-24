"use client";

// Hook de IO do ANÚNCIO de item da pauta no cockpit (docs/23 Fatia 4b): POST
// /sessoes/:id/pauta/itens/:item-id/anuncio, sem corpo (o instante e o autor são do servidor). 201 = anunciado,
// 200 = o item já era o anunciado (reenvio) — os dois são sucesso para quem opera. 409 traz a mensagem do
// domínio (sessão não aberta, item retirado). Quem chama recarrega a pauta depois (é dela que sai o
// `em-apreciacao` que marca o item no cockpit).

import { useCallback, useEffect, useRef, useState } from "react";
import { apiFetch } from "./api-fetch";
import { semCredencial } from "./modo";

export type ResultadoAnuncio = { ok: true } | { ok: false; erro: string };

export function useAnunciarItem(sessaoId: string, token: string | null) {
  const [enviando, setEnviando] = useState(false);
  const enviandoRef = useRef(false);
  const vivoRef = useRef(true);
  useEffect(() => {
    vivoRef.current = true;
    return () => {
      vivoRef.current = false;
    };
  }, []);

  const anunciar = useCallback(
    async (itemId: string): Promise<ResultadoAnuncio> => {
      if (semCredencial(token)) return { ok: false, erro: "sem token de autenticacao" };
      if (enviandoRef.current) return { ok: false, erro: "Aguarde o anúncio anterior terminar." };
      enviandoRef.current = true;
      setEnviando(true);
      try {
        const r = await apiFetch(
          `/api/sessoes/${encodeURIComponent(sessaoId)}/pauta/itens/${encodeURIComponent(itemId)}/anuncio`,
          { token: token ?? undefined, method: "POST" },
        );
        if (r.ok) return { ok: true };
        const e = await r.json().catch(() => null);
        return { ok: false, erro: e?.erro ?? `falha ao anunciar o item (status ${r.status})` };
      } catch {
        return { ok: false, erro: "Falha de rede — tente novamente." };
      } finally {
        enviandoRef.current = false;
        if (vivoRef.current) setEnviando(false);
      }
    },
    [sessaoId, token],
  );

  return { anunciar, enviando };
}
