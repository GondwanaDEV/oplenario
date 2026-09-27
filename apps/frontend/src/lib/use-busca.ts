"use client";

// A busca intra-câmara (Faixa A / A.5): GET /api/busca?q=&tipos= (papel "secretario"). Busca no ENVIO, não a cada
// tecla (cada consulta passa pelo índice da IA). A resposta mais nova vence: uma consulta antiga que chega depois
// não sobrescreve a tela.

import { useCallback, useRef, useState } from "react";
import { apiFetch } from "./api-fetch";
import { camelizarChaves } from "./boundary";
import type { BuscaOut, TipoResultado } from "./busca-vista";

export type EstadoBusca =
  | { fase: "ocioso" }
  | { fase: "buscando"; consulta: string }
  | { fase: "pronto"; consulta: string; resposta: BuscaOut }
  | { fase: "erro"; consulta: string; mensagem: string };

export async function pedirBusca(token: string | null, consulta: string, tipos: TipoResultado[]): Promise<BuscaOut | string> {
  const qs = new URLSearchParams({ q: consulta, tipos: tipos.join(",") });
  try {
    const r = await apiFetch(`/api/busca?${qs}`, { token: token ?? undefined, cache: "no-store" });
    if (r.ok) return camelizarChaves(await r.json()) as BuscaOut;
    if (r.status === 400) return "Escreva de 2 a 300 caracteres para buscar.";
    return "Não foi possível buscar agora. Tente de novo.";
  } catch {
    return "Não foi possível buscar agora. Tente de novo.";
  }
}

export function useBusca(token: string | null) {
  const [estado, setEstado] = useState<EstadoBusca>({ fase: "ocioso" });
  const ultimaRef = useRef(0);

  const buscar = useCallback(
    async (consulta: string, tipos: TipoResultado[]) => {
      const n = ++ultimaRef.current;
      setEstado({ fase: "buscando", consulta });
      const r = await pedirBusca(token, consulta, tipos);
      if (n !== ultimaRef.current) return;
      setEstado(typeof r === "string" ? { fase: "erro", consulta, mensagem: r } : { fase: "pronto", consulta, resposta: r });
    },
    [token],
  );

  return { estado, buscar };
}
