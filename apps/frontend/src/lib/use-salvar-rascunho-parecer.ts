"use client";

// Hook de mutação — PATCH /api/legislativo/pareceres/:id (Onda B Slice 5, "Salvar rascunho"). Mirror EXATO
// de use-editar-proposicao.ts: guard de reentrância via ref (não via `estado`, capturado stale no
// closure), `tratado` distingue erro do backend (visível, nunca engolido — CRÍTICO corrigido na fatia
// anterior) de falha de rede crua. `relatorio`/`analise` são strings sempre (nunca opcionais no wire —
// wire/in.SalvarRascunhoParecer), então o corpo vai cru, sem filtro de `undefined`.

import { useEffect, useRef, useState } from "react";
import { camelizarChaves } from "./boundary";
import type { ParecerEditorOut } from "./contrato-legislativo.gen";

export type SalvarRascunhoParecerIn = { relatorio: string; analise: string };

type Estado = "ocioso" | "enviando" | "erro";

export function useSalvarRascunhoParecer(token: string | null, id: string) {
  const [estado, setEstado] = useState<Estado>("ocioso");
  const [erro, setErro] = useState<string | null>(null);
  const vivoRef = useRef(true);
  const enviandoRef = useRef(false);

  useEffect(() => {
    return () => {
      vivoRef.current = false;
    };
  }, []);

  async function salvar(corpo: SalvarRascunhoParecerIn): Promise<ParecerEditorOut> {
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
      const r = await fetch(`/api/legislativo/pareceres/${id}`, {
        method: "PATCH",
        headers: { Authorization: `Bearer ${token}`, "Content-Type": "application/json" },
        body: JSON.stringify(corpo),
      });
      if (!r.ok) {
        const corpoErro = await r.json().catch(() => null);
        const msg = corpoErro?.erro ?? `falha ao salvar rascunho (status ${r.status})`;
        tratado = true;
        if (vivoRef.current) {
          setEstado("erro");
          setErro(msg);
        }
        throw new Error(msg);
      }
      const dados = camelizarChaves(await r.json()) as ParecerEditorOut;
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

  return { salvar, estado, erro };
}
