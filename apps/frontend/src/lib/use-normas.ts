"use client";

// Acesso às normas de referência (Faixa B / B.4): GET /api/normas, POST /api/normas/versoes, GET e POST de uma
// versão. Papel "secretario" (a authz real é o backend).

import { useEffect, useState } from "react";
import { apiFetch } from "./api-fetch";
import { camelizarChaves } from "./boundary";
import { mensagemDeErroNormas, type NormaResumoOut, type VersaoOut } from "./normas-vista";

type Resultado<T> = { ok: true; dado: T } | { ok: false; mensagem: string };

async function pedir<T>(token: string | null, caminho: string, init: RequestInit = {}): Promise<Resultado<T>> {
  try {
    const r = await apiFetch(caminho, {
      token: token ?? undefined,
      cache: "no-store",
      ...init,
      headers: init.body ? { "Content-Type": "application/json" } : undefined,
    });
    const corpo = await r.json().catch(() => ({}));
    if (r.ok) return { ok: true, dado: camelizarChaves(corpo) as T };
    return { ok: false, mensagem: mensagemDeErroNormas(r.status, (corpo as { erro?: string }).erro) };
  } catch {
    return { ok: false, mensagem: mensagemDeErroNormas(0) };
  }
}

export type ImportacaoNorma = {
  especie: string;
  titulo: string;
  numero?: string;
  data?: string;
  "consolidada-ate"?: string;
  fonte: string;
  texto: string;
};

export function importarNorma(token: string | null, p: ImportacaoNorma) {
  return pedir<VersaoOut>(token, "/api/normas/versoes", { method: "POST", body: JSON.stringify(p) });
}

export function conferirVersao(token: string | null, id: string, decisao: "publicar" | "descartar") {
  return pedir<VersaoOut>(token, `/api/normas/versoes/${id}/conferencia`, {
    method: "POST",
    body: JSON.stringify({ decisao }),
  });
}

function useCarregar<T>(token: string | null, caminho: string | null) {
  const [estado, setEstado] = useState<{ fase: "carregando" } | { fase: "pronto"; dado: T } | { fase: "erro"; mensagem: string }>(
    { fase: "carregando" },
  );
  useEffect(() => {
    if (!caminho) return;
    let vivo = true;
    (async () => {
      const r = await pedir<T>(token, caminho);
      if (vivo) setEstado(r.ok ? { fase: "pronto", dado: r.dado } : { fase: "erro", mensagem: r.mensagem });
    })();
    return () => {
      vivo = false;
    };
  }, [token, caminho]);
  return { estado, setEstado };
}

export function useNormas(token: string | null) {
  return useCarregar<{ normas: NormaResumoOut[] }>(token, "/api/normas");
}

export function useVersao(token: string | null, id: string | null) {
  return useCarregar<VersaoOut>(token, id ? `/api/normas/versoes/${id}` : null);
}
