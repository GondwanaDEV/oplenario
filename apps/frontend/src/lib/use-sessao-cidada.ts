"use client";

// Quem está logado no PORTAL (formulários do cidadão, ADR-0015). O grupo (publico) não tem AuthProvider — é
// público —, então este hook resolve a sessão sozinho, só para decidir o que mostrar: o formulário (logada nesta
// Casa), o convite "Entrar com gov.br" (anônima) ou o aviso de outra Casa. O backend segue sendo o portão real.
//
//   - modo real: a sessão é o cookie httpOnly `sessao`; pergunta a GET /api/eu (401 = anônima).
//   - modo dev: o `?token=` da URL (mesmo bypass das telas internas); sem token é anônima, sem ir ao backend.
//
// "outra-casa": o backend grava na Casa DA SESSÃO, nunca na da URL — quem entrou pela Câmara A e abriu o portal da
// Câmara B não pode escrever achando que é na B.

import { useEffect, useState } from "react";
import { apiFetch } from "./api-fetch";
import { modoReal } from "./modo";

export type EstadoSessaoCidada = "carregando" | "anonima" | "cidada" | "outra-casa" | "erro";

function tokenDev(): string | null {
  if (modoReal() || typeof window === "undefined") return null;
  return new URLSearchParams(window.location.search).get("token") ?? process.env.NEXT_PUBLIC_DEV_TOKEN ?? null;
}

export function useSessaoCidada(ente: string): { estado: EstadoSessaoCidada; token: string | null } {
  const [estado, setEstado] = useState<EstadoSessaoCidada>("carregando");
  const [token, setToken] = useState<string | null>(null);

  useEffect(() => {
    let vivo = true;
    const t = tokenDev();
    (async () => {
      if (!modoReal() && !t) {
        if (vivo) setEstado("anonima");
        return;
      }
      try {
        const r = await apiFetch("/api/eu", { token: t ?? undefined, cache: "no-store" });
        if (!vivo) return;
        if (r.status === 401) return setEstado("anonima");
        if (!r.ok) return setEstado("erro");
        const d = (await r.json()) as { ator?: { "ente-id"?: unknown } };
        if (!vivo) return;
        setToken(t);
        setEstado(d?.ator?.["ente-id"] === ente ? "cidada" : "outra-casa");
      } catch {
        if (vivo) setEstado("erro");
      }
    })();
    return () => {
      vivo = false;
    };
  }, [ente]);

  return { estado, token };
}
