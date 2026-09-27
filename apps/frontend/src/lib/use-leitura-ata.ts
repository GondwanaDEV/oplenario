"use client";

// Hook da LEITURA DA ATA ANTERIOR (Faixa A / A.7): GET /api/sessoes/:id/leitura-ata (a ata a ler + a leitura já
// registrada) e POST (registrar como foi apresentada). Depois de registrar, relê. Tipos GERADOS.

import { useEffect, useRef, useState } from "react";
import { apiFetch } from "./api-fetch";
import { camelizarChaves } from "./boundary";
import { semCredencial } from "./modo";
import type { LeituraAtaOut } from "./contrato-sessoes.gen";
import type { ModoLeitura } from "./leitura-ata-vista";

type Estado = { estado: "carregando" } | { estado: "erro" } | { estado: "pronto"; painel: LeituraAtaOut };

export function useLeituraAta(id: string, token: string | null) {
  const [estado, setEstado] = useState<Estado>({ estado: "carregando" });
  const [rev, setRev] = useState(0);
  const [enviando, setEnviando] = useState(false);
  const vivo = useRef(true);
  const url = `/api/sessoes/${encodeURIComponent(id)}/leitura-ata`;

  useEffect(() => {
    if (semCredencial(token)) return;
    vivo.current = true;
    (async () => {
      try {
        const r = await apiFetch(url, { token: token ?? undefined, cache: "no-store" });
        if (!vivo.current) return;
        if (!r.ok) return setEstado((p) => (p.estado === "pronto" ? p : { estado: "erro" }));
        const painel = camelizarChaves(await r.json()) as LeituraAtaOut;
        if (vivo.current) setEstado({ estado: "pronto", painel });
      } catch {
        if (vivo.current) setEstado((p) => (p.estado === "pronto" ? p : { estado: "erro" }));
      }
    })();
    return () => {
      vivo.current = false;
    };
  }, [url, token, rev]);

  /** Registra; lança Error com a frase da tela (409 traz a razão do servidor). */
  async function registrar(modo: ModoLeitura, ataSessaoId: string, ataVersao: number): Promise<void> {
    if (semCredencial(token)) throw new Error("Sessão expirada. Entre de novo.");
    setEnviando(true);
    try {
      let r: Response;
      try {
        r = await apiFetch(url, {
          token: token ?? undefined,
          method: "POST",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify({ modo, "ata-sessao-id": ataSessaoId, "ata-versao": ataVersao }),
        });
      } catch {
        throw new Error("Falha de rede — a leitura não foi registrada. Tente de novo.");
      }
      if (!r.ok) {
        const corpo = (await r.json().catch(() => null)) as { erro?: string } | null;
        throw new Error(r.status === 409 && corpo?.erro ? corpo.erro : "Não foi possível registrar a leitura agora.");
      }
      setRev((n) => n + 1);
    } finally {
      if (vivo.current) setEnviando(false);
    }
  }

  return {
    ...(semCredencial(token) ? ({ estado: "erro" } as Estado) : estado),
    registrar,
    enviando,
    recarregar: () => setRev((n) => n + 1),
  };
}
