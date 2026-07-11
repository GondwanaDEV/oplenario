"use client";

// Hook de mutação — POST /api/meu/ciencias ("Dar ciência", Onda C1). Mirror de
// use-registrar-resposta.ts (vivoRef + enviandoRef, mesmo contrato de erro), sem `id` no path (o
// `vereador-id` nunca vem do cliente — a borda resolve do ator, anti-forja por construção; o corpo só
// carrega `evento-ref`/`tipo`).

import { useEffect, useRef, useState } from "react";
import { camelizarChaves } from "./boundary";
import type { AcusarCienciaOut } from "./contrato-legislativo.gen";

export type AcusarCienciaIn = { eventoRef: string; tipo: string };

type Estado = "ocioso" | "enviando" | "erro";

export function useAcusarCiencia(token: string | null) {
  const [estado, setEstado] = useState<Estado>("ocioso");
  const [erro, setErro] = useState<string | null>(null);
  const vivoRef = useRef(true);
  const enviandoRef = useRef(false);

  useEffect(() => {
    return () => {
      vivoRef.current = false;
    };
  }, []);

  async function acusar(corpo: AcusarCienciaIn): Promise<AcusarCienciaOut> {
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
      const r = await fetch("/api/meu/ciencias", {
        method: "POST",
        headers: { Authorization: `Bearer ${token}`, "Content-Type": "application/json" },
        body: JSON.stringify({ "evento-ref": corpo.eventoRef, tipo: corpo.tipo }),
      });
      if (!r.ok) {
        const corpoErro = await r.json().catch(() => null);
        const msg = corpoErro?.erro ?? `falha ao dar ciência (status ${r.status})`;
        tratado = true;
        if (vivoRef.current) {
          setEstado("erro");
          setErro(msg);
        }
        throw new Error(msg);
      }
      const dados = camelizarChaves(await r.json()) as AcusarCienciaOut;
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

  return { acusar, estado, erro };
}
