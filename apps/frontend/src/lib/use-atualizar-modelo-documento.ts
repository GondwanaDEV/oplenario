"use client";

// Hook de mutação — PATCH /api/legislativo/documento-modelos/:id (Onda B Slice 6, fatia de escrita — aba
// "Modelos", editar/desativar). Mirror EXATO de use-editar-documento.ts.

import { useEffect, useRef, useState } from "react";
import { apiFetch } from "./api-fetch";
import { camelizarChaves } from "./boundary";
import type { DocumentoModeloDetalheOut } from "./contrato-legislativo.gen";
import { semCredencial } from "./modo";

export type AtualizarModeloDocumentoIn = {
  lockVersion: number;
  nome?: string;
  corpoTemplate?: string;
  ativo?: boolean;
};

type Estado = "ocioso" | "enviando" | "erro";

function paraKebab(chave: string): string {
  return chave.replace(/[A-Z]/g, (c) => `-${c.toLowerCase()}`);
}

function corpoKebab(corpo: AtualizarModeloDocumentoIn): Record<string, unknown> {
  return Object.fromEntries(
    Object.entries(corpo)
      .filter(([, v]) => v !== undefined)
      .map(([k, v]) => [paraKebab(k), v]),
  );
}

export function useAtualizarModeloDocumento(token: string | null, id: string | null) {
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

  async function atualizar(corpo: AtualizarModeloDocumentoIn): Promise<DocumentoModeloDetalheOut> {
    if (semCredencial(token)) {
      throw new Error("sem token de autenticacao");
    }
    if (!id) {
      throw new Error("modelo ainda nao foi criado");
    }
    if (enviandoRef.current) {
      throw new Error("envio em andamento");
    }
    enviandoRef.current = true;
    setEstado("enviando");
    setErro(null);
    let tratado = false;
    try {
      const r = await apiFetch(`/api/legislativo/documento-modelos/${encodeURIComponent(id)}`, {
        token: token ?? undefined,
        method: "PATCH",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(corpoKebab(corpo)),
      });
      if (!r.ok) {
        const corpoErro = await r.json().catch(() => null);
        const msg = corpoErro?.erro ?? `falha ao salvar modelo (status ${r.status})`;
        tratado = true;
        if (vivoRef.current) {
          setEstado("erro");
          setErro(msg);
        }
        throw new Error(msg);
      }
      const dados = camelizarChaves(await r.json()) as DocumentoModeloDetalheOut;
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

  return { atualizar, estado, erro };
}
