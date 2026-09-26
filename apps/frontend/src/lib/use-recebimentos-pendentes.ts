"use client";

// Hook de leitura da FILA DE CARGAS não recebidas da Casa (fatia 2b): GET /api/legislativo/recebimentos-pendentes
// (papel "secretario"). Mais antigas primeiro — o backend ordena por quem espera há mais tempo. Tipo GERADO
// (contrato-legislativo.gen.ts, fonte wire/out/proposicao.clj RecebimentosPendentesOut).
//
// Mesmo idioma 3-estados + recarregar() de use-fila-moderacao.ts.

import { useCallback, useEffect, useRef, useState } from "react";
import { apiFetch } from "./api-fetch";
import { camelizarChaves } from "./boundary";
import { semCredencial } from "./modo";
import type { RecebimentoPendenteItemOut, RecebimentosPendentesOut } from "./contrato-legislativo.gen";

export type { RecebimentoPendenteItemOut } from "./contrato-legislativo.gen";

type Estado = "carregando" | "pronto" | "erro";

async function buscar(token: string | null): Promise<RecebimentoPendenteItemOut[] | null> {
  const r = await apiFetch("/api/legislativo/recebimentos-pendentes", { token: token ?? undefined, cache: "no-store" });
  if (!r.ok) return null;
  return (camelizarChaves(await r.json()) as RecebimentosPendentesOut).itens;
}

export function useRecebimentosPendentes(token: string | null) {
  const [itens, setItens] = useState<RecebimentoPendenteItemOut[]>([]);
  const [estado, setEstado] = useState<Estado>("carregando");
  const vivoRef = useRef(true);

  useEffect(() => {
    vivoRef.current = true;
    return () => {
      vivoRef.current = false;
    };
  }, []);

  const recarregar = useCallback(async () => {
    if (semCredencial(token)) return;
    try {
      const r = await buscar(token);
      if (!vivoRef.current) return;
      if (r === null) {
        setEstado("erro");
        return;
      }
      setItens(r);
      setEstado("pronto");
    } catch {
      if (vivoRef.current) setEstado("erro");
    }
  }, [token]);

  useEffect(() => {
    if (semCredencial(token)) return; // sem credencial: o estado "erro" é derivado no render, abaixo
    let ativo = true;
    (async () => {
      try {
        const r = await buscar(token);
        if (!ativo) return;
        if (r === null) {
          setEstado("erro");
          return;
        }
        setItens(r);
        setEstado("pronto");
      } catch {
        if (ativo) setEstado("erro");
      }
    })();
    return () => {
      ativo = false;
    };
  }, [token]);

  return { itens, estado: semCredencial(token) ? ("erro" as Estado) : estado, recarregar };
}
