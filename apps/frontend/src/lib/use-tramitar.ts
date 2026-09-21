"use client";

// Hook de mutação — POST /api/legislativo/proposicoes/:id/tramitacao (dispara UM gatilho do rito, eixo C).
// Mirror do idioma de use-emitir-parecer.ts: guard `enviandoRef` síncrono contra duplo-disparo, `vivoRef`
// re-armado no mount (StrictMode), estado ocioso/enviando/erro. O corpo é `{gatilho}` (wire/in
// TramitarProposicao é :closed e só aceita `gatilho` — o destino quem decide é o template, nunca o
// cliente). O backend responde 200 (recibo), 409 (rito recusou / conflito de estado) ou 403 (autz).

import { useEffect, useRef, useState } from "react";
import { apiFetch } from "./api-fetch";
import { semCredencial } from "./modo";

type Estado = "ocioso" | "enviando" | "erro";

export function useTramitar(token: string | null, id: string) {
  const [estado, setEstado] = useState<Estado>("ocioso");
  const [erro, setErro] = useState<string | null>(null);
  const vivoRef = useRef(true);
  const enviandoRef = useRef(false);

  useEffect(() => {
    vivoRef.current = true;
    return () => {
      vivoRef.current = false;
    };
  }, []);

  async function tramitar(gatilho: string): Promise<void> {
    if (semCredencial(token)) {
      throw new Error("sem token de autenticacao");
    }
    if (enviandoRef.current) {
      throw new Error("envio em andamento");
    }
    enviandoRef.current = true;
    setEstado("enviando");
    setErro(null);
    let tratado = false;
    try {
      const r = await apiFetch(`/api/legislativo/proposicoes/${id}/tramitacao`, {
        token: token ?? undefined,
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ gatilho }),
      });
      if (!r.ok) {
        const corpoErro = await r.json().catch(() => null);
        const msg = corpoErro?.erro ?? `falha ao tramitar (status ${r.status})`;
        tratado = true;
        if (vivoRef.current) {
          setEstado("erro");
          setErro(msg);
        }
        throw new Error(msg);
      }
      if (vivoRef.current) setEstado("ocioso");
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

  return { tramitar, estado, erro };
}
