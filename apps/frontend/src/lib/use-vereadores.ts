"use client";

// Hook da lista de vereadores (Cadastro de Vereadores, Task 8) — GET /api/cadastros/vereadores. Mirror
// EXATO de use-tramitacao-board.ts / use-mesa.ts: fetch autenticado (Bearer token), camelizarChaves do
// boundary.ts, estados carregando/pronto/erro, cleanup por `vivo` (abort-safe), caso sem token derivado no
// retorno (nunca setState síncrono dentro do effect, exigido por eslint-plugin-react-hooks v7
// `set-state-in-effect`).
//
// `versao` (Task 9) é um token de refetch puro — a página o incrementa depois de uma escrita bem-sucedida
// (criar/editar vereador) pra forçar a lista a se refazer; o valor em si não é usado no corpo do fetch.

import { useEffect, useState } from "react";
import { apiFetch } from "./api-fetch";
import { camelizarChaves } from "./boundary";
import type { ListaVereadoresOut, VereadorLinhaOut } from "./contrato-cadastros.gen";
import { semCredencial } from "./modo";

type Estado = "carregando" | "pronto" | "erro";

export function useVereadores(token: string | null, versao = 0) {
  const [dados, setDados] = useState<VereadorLinhaOut[]>([]);
  const [estado, setEstado] = useState<Estado>("carregando");

  useEffect(() => {
    if (semCredencial(token)) return; // caso de erro sem token é derivado no retorno (sem setState síncrono no effect)
    let vivo = true;
    (async () => {
      try {
        const r = await apiFetch("/api/cadastros/vereadores", { token: token ?? undefined, cache: "no-store" });
        if (!vivo) return;
        if (!r.ok) {
          setEstado("erro");
          return;
        }
        const corpo = camelizarChaves(await r.json()) as ListaVereadoresOut;
        if (!vivo) return;
        setDados(corpo.vereadores);
        setEstado("pronto");
      } catch {
        if (vivo) setEstado("erro");
      }
    })();
    return () => {
      vivo = false;
    };
  }, [token, versao]);

  // caso de erro sem token é derivado aqui (mantém o effect livre de setState síncrono)
  if (semCredencial(token)) {
    return { dados: [] as VereadorLinhaOut[], estado: "erro" as Estado };
  }
  return { dados, estado };
}
