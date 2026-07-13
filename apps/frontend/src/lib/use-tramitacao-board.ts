"use client";

// Hook da tela "Tramitação" (Onda B Slice 4) — espelha o padrão exato de use-mesa.ts: fetch autenticado
// (Bearer token), camelizarChaves do boundary.ts, estados carregando/pronto/erro, cleanup por `vivo`
// (abort-safe). Só uma rota (a mesma GET /api/paineis/tramitacao já usada por useMesa como "chamada de
// detalhe") — aqui ela é a chamada PRINCIPAL da página, não um enriquecimento best-effort.

import { useEffect, useState } from "react";
import { apiFetch } from "./api-fetch";
import { camelizarChaves } from "./boundary";
import type { ItemBoardOut, TramitacaoBoardOut } from "./contrato-mesa.gen";

type Estado = "carregando" | "pronto" | "erro";

export function useTramitacaoBoard(token: string | null) {
  const [itens, setItens] = useState<ItemBoardOut[] | null>(null);
  const [estado, setEstado] = useState<Estado>("carregando");

  useEffect(() => {
    if (!token) return; // caso de erro sem token é derivado no retorno (sem setState síncrono no effect)
    let vivo = true;
    (async () => {
      try {
        const r = await apiFetch("/api/paineis/tramitacao", { token, cache: "no-store" });
        if (!vivo) return;
        if (!r.ok) {
          setEstado("erro");
          return;
        }
        const corpo = camelizarChaves(await r.json()) as TramitacaoBoardOut;
        if (!vivo) return;
        setItens(corpo.itens);
        setEstado("pronto");
      } catch {
        if (vivo) setEstado("erro");
      }
    })();
    return () => {
      vivo = false;
    };
  }, [token]);

  // caso de erro sem token é derivado aqui (mantém o effect livre de setState síncrono)
  if (!token) {
    return { itens: null, estado: "erro" as Estado };
  }
  return { itens, estado };
}
