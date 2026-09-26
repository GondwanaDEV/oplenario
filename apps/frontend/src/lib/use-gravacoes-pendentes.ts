"use client";

// Hook da FILA DE GRAVAÇÕES sem sessão (Faixa A / A.2): GET /api/gravacoes/pendentes (papel "secretario") e o
// vínculo POST /api/sessoes/:id/gravacao/:seg/vincular. Tipo GERADO (contrato-sessoes.gen.ts, fonte
// wire/out.clj GravacoesPendentesOut). Mesmo idioma 3-estados + recarregar() de use-recebimentos-pendentes.ts.

import { useCallback, useEffect, useRef, useState } from "react";
import { apiFetch } from "./api-fetch";
import { camelizarChaves } from "./boundary";
import { semCredencial } from "./modo";
import type { GravacaoPendenteOut, GravacoesPendentesOut } from "./contrato-sessoes.gen";

type Estado = "carregando" | "pronto" | "erro";

async function buscar(token: string | null): Promise<GravacaoPendenteOut[] | null> {
  const r = await apiFetch("/api/gravacoes/pendentes", { token: token ?? undefined, cache: "no-store" });
  if (!r.ok) return null;
  return (camelizarChaves(await r.json()) as GravacoesPendentesOut).segmentos;
}

/** Vincula a gravação à sessão. Devolve null em sucesso, ou a mensagem para a tela. */
export async function vincularGravacao(
  token: string | null,
  sessaoId: string,
  gravacaoId: string,
  lockVersion: number,
): Promise<string | null> {
  const r = await apiFetch(`/api/sessoes/${sessaoId}/gravacao/${gravacaoId}/vincular`, {
    token: token ?? undefined,
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ "lock-version": lockVersion }),
  });
  if (r.ok) return null;
  if (r.status === 409) {
    try {
      const j = (await r.json()) as { erro?: string };
      if (j.erro?.includes("nao realizada")) return "Essa sessão não foi realizada: ela não recebe gravação.";
    } catch {
      /* corpo não é JSON: cai na mensagem genérica */
    }
    return "Essa gravação já foi vinculada por outra pessoa. A lista foi atualizada.";
  }
  return "Não foi possível vincular agora. Tente de novo.";
}

export function useGravacoesPendentes(token: string | null) {
  const [itens, setItens] = useState<GravacaoPendenteOut[]>([]);
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
    if (semCredencial(token)) return;
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
