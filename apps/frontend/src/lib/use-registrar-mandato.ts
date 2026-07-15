"use client";

// Hook de mutação — POST /api/cadastros/vereadores/:id/mandatos (Onda D Slice 4). Variante de
// use-criar-vereador.ts: mesmo shape ocioso/enviando/erro + corpoKebab, mas o alvo é o vereador (fixo no
// path) e o corpo é o novo mandato (partido/vigenciaFim opcionais). Guarda `!vereadorId` espelha
// use-editar-vereador.ts.

import { useEffect, useRef, useState } from "react";
import { apiFetch } from "./api-fetch";
import { camelizarChaves } from "./boundary";
import { semCredencial } from "./modo";

export type RegistrarMandatoIn = {
  legislaturaId: string;
  partido?: string;
  natureza: string;
  vigenciaInicio: string;
  vigenciaFim?: string;
};
type Estado = "ocioso" | "enviando" | "erro";

function paraKebab(chave: string): string {
  return chave.replace(/[A-Z]/g, (c) => `-${c.toLowerCase()}`);
}
function corpoKebab(corpo: Record<string, unknown>): Record<string, unknown> {
  return Object.fromEntries(
    Object.entries(corpo).filter(([, v]) => v !== undefined).map(([k, v]) => [paraKebab(k), v]),
  );
}

export function useRegistrarMandato(token: string | null, vereadorId: string | null) {
  const [estado, setEstado] = useState<Estado>("ocioso");
  const [erro, setErro] = useState<string | null>(null);
  const vivoRef = useRef(true);
  const enviandoRef = useRef(false);
  useEffect(() => () => { vivoRef.current = false; }, []);

  async function registrar(corpo: RegistrarMandatoIn): Promise<{ id: string }> {
    if (semCredencial(token)) throw new Error("sem token de autenticacao");
    if (!vereadorId) throw new Error("vereador nao identificado");
    if (enviandoRef.current) throw new Error("envio em andamento");
    enviandoRef.current = true;
    setEstado("enviando");
    setErro(null);
    let tratado = false;
    try {
      const r = await apiFetch(`/api/cadastros/vereadores/${encodeURIComponent(vereadorId)}/mandatos`, {
        token: token ?? undefined, method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(corpoKebab(corpo)),
      });
      if (!r.ok) {
        const corpoErro = await r.json().catch(() => null);
        const msg = corpoErro?.erro ?? `falha ao registrar mandato (status ${r.status})`;
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
  return { registrar, estado, erro };
}
