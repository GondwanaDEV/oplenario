"use client";

// Hook da ATA DA SESSÃO (Faixa A / A.6a): GET /api/sessoes/:id/ata (a vigente + o histórico) e POST (publicar ou
// retificar). Depois de publicar, relê — a tela mostra o que o servidor congelou, nunca o que o cliente acha. Tipos GERADOS.

import { useEffect, useRef, useState } from "react";
import { apiFetch } from "./api-fetch";
import { camelizarChaves } from "./boundary";
import { semCredencial } from "./modo";
import { mensagemDeErroAta } from "./ata-vista";
import type { AtaReciboOut, AtaSessaoOut } from "./contrato-sessoes.gen";

type Estado = { estado: "carregando" } | { estado: "erro" } | { estado: "pronto"; ata: AtaSessaoOut };

export type Publicacao = { texto: string; motivoRetificacao?: string };

export function useAta(id: string, token: string | null) {
  const [estado, setEstado] = useState<Estado>({ estado: "carregando" });
  const [enviando, setEnviando] = useState(false);
  const vivo = useRef(true);
  const url = `/api/sessoes/${encodeURIComponent(id)}/ata`;

  // `rev` sobe a cada publicação: o efeito relê a ata (o que o servidor congelou, nunca o que o cliente acha).
  const [rev, setRev] = useState(0);

  useEffect(() => {
    if (semCredencial(token)) return;
    vivo.current = true;
    (async () => {
      try {
        const r = await apiFetch(url, { token: token ?? undefined, cache: "no-store" });
        if (!vivo.current) return;
        if (!r.ok) return setEstado({ estado: "erro" });
        const ata = camelizarChaves(await r.json()) as AtaSessaoOut;
        if (vivo.current) setEstado({ estado: "pronto", ata });
      } catch {
        if (vivo.current) setEstado({ estado: "erro" });
      }
    })();
    return () => {
      vivo.current = false;
    };
  }, [url, token, rev]);

  /** Publica; devolve o recibo, ou lança Error com a frase da tela. */
  async function publicar(p: Publicacao): Promise<AtaReciboOut> {
    if (semCredencial(token)) throw new Error("Sessão expirada. Entre de novo.");
    setEnviando(true);
    try {
      let r: Response;
      try {
        r = await apiFetch(url, {
          token: token ?? undefined,
          method: "POST",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify({ texto: p.texto, "motivo-retificacao": p.motivoRetificacao ?? null }),
        });
      } catch {
        throw new Error("Falha de rede — nada foi publicado. Tente de novo.");
      }
      if (!r.ok) {
        const corpo = (await r.json().catch(() => null)) as { erro?: string } | null;
        throw new Error(mensagemDeErroAta(r.status, corpo?.erro));
      }
      const recibo = camelizarChaves(await r.json()) as AtaReciboOut;
      setRev((n) => n + 1);
      return recibo;
    } finally {
      if (vivo.current) setEnviando(false);
    }
  }

  return { ...(semCredencial(token) ? ({ estado: "erro" } as Estado) : estado), publicar, enviando };
}
