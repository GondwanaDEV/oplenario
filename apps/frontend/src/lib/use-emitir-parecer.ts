"use client";

// Hook de mutação — POST /api/legislativo/pareceres/:id/emissao (Onda B Slice 5, "Emitir parecer"). Mirror
// EXATO de use-salvar-rascunho-parecer.ts. `lockVersion` é OBRIGATÓRIO no corpo (CAS otimista real —
// review HIGH fe-11-parecer, wire/in.EmitirParecer): sem ele o servidor nunca detecta que o parecer mudou
// entre o GET do editor e o clique em "Emitir". `votoRelator` é vocabulário regimental ABERTO — o hook não
// valida seu conteúdo (o form já restringe às 3 opções fixas, parecer-vista.ts); o backend valida
// não-branco e o CAS de fato.

import { useEffect, useRef, useState } from "react";
import { apiFetch } from "./api-fetch";
import { camelizarChaves } from "./boundary";
import type { ParecerEditorOut } from "./contrato-legislativo.gen";
import { semCredencial } from "./modo";

export type EmitirParecerIn = { votoRelator: string; lockVersion: number };

type Estado = "ocioso" | "enviando" | "erro";

function paraKebab(chave: string): string {
  return chave.replace(/[A-Z]/g, (c) => `-${c.toLowerCase()}`);
}

function corpoKebab(corpo: EmitirParecerIn): Record<string, unknown> {
  return Object.fromEntries(Object.entries(corpo).map(([k, v]) => [paraKebab(k), v]));
}

export function useEmitirParecer(token: string | null, id: string) {
  const [estado, setEstado] = useState<Estado>("ocioso");
  const [erro, setErro] = useState<string | null>(null);
  const vivoRef = useRef(true);
  const enviandoRef = useRef(false);

  useEffect(() => {
    return () => {
      vivoRef.current = false;
    };
  }, []);

  async function emitir(corpo: EmitirParecerIn): Promise<ParecerEditorOut> {
    if (semCredencial(token)) {
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
      const r = await apiFetch(`/api/legislativo/pareceres/${id}/emissao`, {
        token: token ?? undefined,
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(corpoKebab(corpo)),
      });
      if (!r.ok) {
        const corpoErro = await r.json().catch(() => null);
        const msg = corpoErro?.erro ?? `falha ao emitir parecer (status ${r.status})`;
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

  return { emitir, estado, erro };
}
