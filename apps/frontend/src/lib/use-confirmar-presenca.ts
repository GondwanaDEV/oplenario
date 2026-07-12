"use client";

// Hook de mutação — POST /api/sessoes/:id/presenca/confirmar (Onda C3, autoatendimento). Mirror de
// use-acusar-ciencia.ts (vivoRef + enviandoRef, mesmo contrato de erro). Sem corpo: `vereador-id` nunca vem
// do cliente (a borda resolve do ator, anti-forja por construção).

import { useEffect, useRef, useState } from "react";
import { camelizarChaves } from "./boundary";

export type ConfirmarPresencaOut = { id: string };

type Estado = "ocioso" | "enviando" | "erro";

export function useConfirmarPresenca(token: string | null) {
  const [estado, setEstado] = useState<Estado>("ocioso");
  const [erro, setErro] = useState<string | null>(null);
  const vivoRef = useRef(true);
  const enviandoRef = useRef(false);

  useEffect(() => {
    return () => {
      vivoRef.current = false;
    };
  }, []);

  async function confirmar(sessaoId: string): Promise<ConfirmarPresencaOut> {
    if (!token) {
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
      const r = await fetch(`/api/sessoes/${sessaoId}/presenca/confirmar`, {
        method: "POST",
        headers: { Authorization: `Bearer ${token}` },
      });
      if (!r.ok) {
        const corpoErro = await r.json().catch(() => null);
        const msg = corpoErro?.erro ?? `falha ao confirmar presença (status ${r.status})`;
        tratado = true;
        if (vivoRef.current) {
          setEstado("erro");
          setErro(msg);
        }
        throw new Error(msg);
      }
      const dados = camelizarChaves(await r.json()) as ConfirmarPresencaOut;
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

  return { confirmar, estado, erro };
}
