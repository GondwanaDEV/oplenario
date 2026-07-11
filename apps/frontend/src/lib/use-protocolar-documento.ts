"use client";

// Hook de mutação — POST /api/legislativo/documentos/:id/protocolo (Onda B Slice 6, "Protocolar e
// numerar"). Mirror EXATO de use-emitir-parecer.ts: `lockVersion` OBRIGATÓRIO no corpo (CAS otimista real —
// sem ele o servidor nunca detecta que o documento mudou entre o GET do editor e o clique). `id` NULO lança
// (defesa em profundidade — a UI só mostra o botão depois que o documento existe).

import { useEffect, useRef, useState } from "react";
import { camelizarChaves } from "./boundary";
import type { DocumentoOut } from "./contrato-legislativo.gen";

export type ProtocolarDocumentoIn = { lockVersion: number };

type Estado = "ocioso" | "enviando" | "erro";

function paraKebab(chave: string): string {
  return chave.replace(/[A-Z]/g, (c) => `-${c.toLowerCase()}`);
}

function corpoKebab(corpo: ProtocolarDocumentoIn): Record<string, unknown> {
  return Object.fromEntries(Object.entries(corpo).map(([k, v]) => [paraKebab(k), v]));
}

export function useProtocolarDocumento(token: string | null, id: string | null) {
  const [estado, setEstado] = useState<Estado>("ocioso");
  const [erro, setErro] = useState<string | null>(null);
  const vivoRef = useRef(true);
  const enviandoRef = useRef(false);

  useEffect(() => {
    return () => {
      vivoRef.current = false;
    };
  }, []);

  async function protocolar(corpo: ProtocolarDocumentoIn): Promise<DocumentoOut> {
    if (!token) {
      throw new Error("sem token de autenticacao");
    }
    if (!id) {
      throw new Error("documento ainda nao foi gerado");
    }
    if (enviandoRef.current) {
      throw new Error("envio em andamento");
    }
    enviandoRef.current = true;
    setEstado("enviando");
    setErro(null);
    let tratado = false;
    try {
      const r = await fetch(`/api/legislativo/documentos/${id}/protocolo`, {
        method: "POST",
        headers: { Authorization: `Bearer ${token}`, "Content-Type": "application/json" },
        body: JSON.stringify(corpoKebab(corpo)),
      });
      if (!r.ok) {
        const corpoErro = await r.json().catch(() => null);
        const msg = corpoErro?.erro ?? `falha ao protocolar documento (status ${r.status})`;
        tratado = true;
        if (vivoRef.current) {
          setEstado("erro");
          setErro(msg);
        }
        throw new Error(msg);
      }
      const dados = camelizarChaves(await r.json()) as DocumentoOut;
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

  return { protocolar, estado, erro };
}
