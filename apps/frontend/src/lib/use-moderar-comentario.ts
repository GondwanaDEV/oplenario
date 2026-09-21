"use client";

// Hook de mutação — POST /api/comentarios/:id/moderar (SERVIDOR, exige-papel). Decide UM comentário:
// {acao: "aprovado" | "rejeitado", motivo-rejeicao?}. NÃO é bound a um id (a fila tem vários) — `moderar`
// recebe o id no disparo, então um único hook serve a lista inteira sem violar as regras de hooks. Guard
// `enviandoRef` síncrono contra duplo-clique; `vivoRef` re-armado no mount (StrictMode). 404 -> não existe;
// 409 -> já moderado (some da fila num próximo recarregar).

import { useEffect, useRef, useState } from "react";
import { apiFetch } from "./api-fetch";
import { semCredencial } from "./modo";

export type AcaoModeracao = "aprovado" | "rejeitado";
export type ModerarEntrada = { acao: AcaoModeracao; motivoRejeicao?: string };

type Estado = "ocioso" | "enviando" | "erro";

export function useModerarComentario(token: string | null) {
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

  async function moderar(id: string, entrada: ModerarEntrada): Promise<void> {
    if (semCredencial(token)) {
      throw new Error("sem token de autenticacao");
    }
    if (enviandoRef.current) {
      throw new Error("envio em andamento");
    }
    enviandoRef.current = true;
    setEstado("enviando");
    setErro(null);
    const corpo: Record<string, unknown> = { acao: entrada.acao };
    if (entrada.motivoRejeicao) corpo["motivo-rejeicao"] = entrada.motivoRejeicao;
    let tratado = false;
    try {
      const r = await apiFetch(`/api/comentarios/${id}/moderar`, {
        token: token ?? undefined,
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(corpo),
      });
      if (!r.ok) {
        const corpoErro = await r.json().catch(() => null);
        const msg = corpoErro?.erro ?? `falha ao moderar (status ${r.status})`;
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

  return { moderar, estado, erro };
}
