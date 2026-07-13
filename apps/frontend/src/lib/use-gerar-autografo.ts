"use client";

// Hook de mutação — POST /api/legislativo/proposicoes/:id/autografo ("Gerar autógrafo e enviar ao
// Executivo", Onda B Slice 7). Mirror de use-protocolar-documento.ts (id fixo no PATH, guard de `id` nulo)
// + corpoKebab/vivoRef/enviandoRef de use-criar-proposicao.ts. A resposta 201 é a leitura COMPOSTA
// PosAprovacaoOut inteira (autógrafo + tramitação executiva recém-aberta, mesma tx no backend, spec
// §3.1/3.2) — não só o AutografoOut — então o caller substitui o estado local inteiro numa única
// atribuição, sem round-trip extra.

import { useEffect, useRef, useState } from "react";
import { apiFetch } from "./api-fetch";
import { camelizarChaves } from "./boundary";
import type { PosAprovacaoOut } from "./contrato-legislativo.gen";

export type GerarAutografoIn = { prazoRespostaEm?: string };

type Estado = "ocioso" | "enviando" | "erro";

function paraKebab(chave: string): string {
  return chave.replace(/[A-Z]/g, (c) => `-${c.toLowerCase()}`);
}

function corpoKebab(corpo: GerarAutografoIn): Record<string, unknown> {
  return Object.fromEntries(
    Object.entries(corpo)
      .filter(([, v]) => v !== undefined)
      .map(([k, v]) => [paraKebab(k), v]),
  );
}

export function useGerarAutografo(token: string | null, proposicaoId: string | null) {
  const [estado, setEstado] = useState<Estado>("ocioso");
  const [erro, setErro] = useState<string | null>(null);
  const vivoRef = useRef(true);
  const enviandoRef = useRef(false);

  useEffect(() => {
    return () => {
      vivoRef.current = false;
    };
  }, []);

  async function gerar(corpo: GerarAutografoIn = {}): Promise<PosAprovacaoOut> {
    if (!token) {
      throw new Error("sem token de autenticacao");
    }
    if (!proposicaoId) {
      throw new Error("proposicao nao informada");
    }
    if (enviandoRef.current) {
      throw new Error("envio em andamento");
    }
    enviandoRef.current = true;
    setEstado("enviando");
    setErro(null);
    let tratado = false;
    try {
      const r = await apiFetch(`/api/legislativo/proposicoes/${encodeURIComponent(proposicaoId)}/autografo`, {
        token,
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(corpoKebab(corpo)),
      });
      if (!r.ok) {
        const corpoErro = await r.json().catch(() => null);
        const msg = corpoErro?.erro ?? `falha ao gerar autografo (status ${r.status})`;
        tratado = true;
        if (vivoRef.current) {
          setEstado("erro");
          setErro(msg);
        }
        throw new Error(msg);
      }
      const dados = camelizarChaves(await r.json()) as PosAprovacaoOut;
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

  return { gerar, estado, erro };
}
