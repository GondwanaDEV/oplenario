"use client";

// Hook de mutação — POST /api/cadastros/vereadores (Onda D Slice 4). Mirror de use-registrar-resposta.ts:
// estado ocioso/enviando/erro, corpoKebab filtrando `undefined`, apiFetch (boundary de auth), camelizar a
// resposta {id}. `criar` lança em falha (o caller decide a UX); `estado`/`erro` alimentam o form.

import { useEffect, useRef, useState } from "react";
import { apiFetch } from "./api-fetch";
import { camelizarChaves } from "./boundary";
import { semCredencial } from "./modo";

export type CriarVereadorIn = { nome: string; nomeParlamentar?: string };
type Estado = "ocioso" | "enviando" | "erro";

function paraKebab(chave: string): string {
  return chave.replace(/[A-Z]/g, (c) => `-${c.toLowerCase()}`);
}
function corpoKebab(corpo: Record<string, unknown>): Record<string, unknown> {
  return Object.fromEntries(
    Object.entries(corpo).filter(([, v]) => v !== undefined).map(([k, v]) => [paraKebab(k), v]),
  );
}

export function useCriarVereador(token: string | null) {
  const [estado, setEstado] = useState<Estado>("ocioso");
  const [erro, setErro] = useState<string | null>(null);
  const vivoRef = useRef(true);
  const enviandoRef = useRef(false);
  useEffect(() => {
    // RE-ARMA no mount. Sem esta linha o ref nasce FALSE sob React StrictMode (dev), porque o
    // StrictMode roda cleanup+setup no primeiro mount — e entao TODO setEstado pos-resposta vira
    // no-op e nenhuma mensagem de erro do servidor chega na tela. Medido: 1,5s depois de um 409 o
    // botao seguia "Salvando..." com zero alerta na pagina. Os hooks de LEITURA ja faziam assim.
    vivoRef.current = true;
    return () => {
      vivoRef.current = false;
    };
  }, []);

  async function criar(corpo: CriarVereadorIn): Promise<{ id: string }> {
    if (semCredencial(token)) throw new Error("sem token de autenticacao");
    if (enviandoRef.current) throw new Error("envio em andamento");
    enviandoRef.current = true;
    setEstado("enviando");
    setErro(null);
    let tratado = false;
    try {
      const r = await apiFetch("/api/cadastros/vereadores", {
        token: token ?? undefined, method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(corpoKebab(corpo)),
      });
      if (!r.ok) {
        const corpoErro = await r.json().catch(() => null);
        const msg = corpoErro?.erro ?? `falha ao criar vereador (status ${r.status})`;
        tratado = true;
        if (vivoRef.current) { setEstado("erro"); setErro(msg); }
        throw new Error(msg);
      }
      const dados = camelizarChaves(await r.json()) as { id: string };
      if (vivoRef.current) setEstado("ocioso");
      return dados;
    } catch (e) {
      if (vivoRef.current && !tratado) { setEstado("erro"); setErro("falha de rede — tente novamente"); }
      throw e;
    } finally {
      enviandoRef.current = false;
    }
  }
  return { criar, estado, erro };
}
