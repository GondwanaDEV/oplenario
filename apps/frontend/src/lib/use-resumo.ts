"use client";

// Hook do RESUMO CIDADÃO de uma proposição (Faixa A / A.8): GET /api/legislativo/proposicoes/:id/resumo (o rascunho da
// IA, a versão publicada e o histórico), a leitura do rascunho (vem da IA pelo core) e o POST de publicação. Depois de
// publicar, relê — a tela mostra o que o servidor congelou. Tipos GERADOS (contrato-legislativo.gen.ts).

import { useCallback, useEffect, useRef, useState } from "react";
import { apiFetch } from "./api-fetch";
import { camelizarChaves } from "./boundary";
import { semCredencial } from "./modo";
import { mensagemDeErroResumo } from "./resumo-vista";
import type { RascunhoResumoOut, ResumoProposicaoOut, ResumoReciboOut } from "./contrato-legislativo.gen";

// `negado`: o vereador tambem abre a ficha, mas o resumo e' da secretaria (403) — a aba diz isso em vez de "erro".
type Estado =
  | { estado: "carregando" }
  | { estado: "erro" }
  | { estado: "negado" }
  | { estado: "pronto"; resumo: ResumoProposicaoOut };

export function useResumo(proposicaoId: string, token: string | null) {
  const [estado, setEstado] = useState<Estado>({ estado: "carregando" });
  const [enviando, setEnviando] = useState(false);
  const [rev, setRev] = useState(0);
  const vivo = useRef(true);
  const url = `/api/legislativo/proposicoes/${encodeURIComponent(proposicaoId)}/resumo`;

  useEffect(() => {
    if (semCredencial(token)) return;
    vivo.current = true;
    (async () => {
      try {
        const r = await apiFetch(url, { token: token ?? undefined, cache: "no-store" });
        if (!vivo.current) return;
        if (r.status === 403) return setEstado({ estado: "negado" });
        if (!r.ok) return setEstado((prev) => (prev.estado === "pronto" ? prev : { estado: "erro" }));
        const resumo = camelizarChaves(await r.json()) as ResumoProposicaoOut;
        if (vivo.current) setEstado({ estado: "pronto", resumo });
      } catch {
        if (vivo.current) setEstado((prev) => (prev.estado === "pronto" ? prev : { estado: "erro" }));
      }
    })();
    return () => {
      vivo.current = false;
    };
  }, [url, token, rev]);

  /** O rascunho para revisar. IA fora: lança Error com a mensagem R-IA-1 do servidor. */
  const lerRascunho = useCallback(
    async (rascunhoId: string): Promise<RascunhoResumoOut> => {
      let r: Response;
      try {
        r = await apiFetch(`${url}/rascunhos/${encodeURIComponent(rascunhoId)}`, { token: token ?? undefined, cache: "no-store" });
      } catch {
        throw new Error("Falha de rede — não foi possível abrir o rascunho.");
      }
      if (!r.ok) {
        const corpo = (await r.json().catch(() => null)) as { erro?: string } | null;
        throw new Error(r.status === 503 && corpo?.erro ? corpo.erro : "Não foi possível abrir o rascunho agora.");
      }
      return camelizarChaves(await r.json()) as RascunhoResumoOut;
    },
    [url, token],
  );

  /** Publica; devolve o recibo, ou lança Error com a frase da tela. */
  async function publicar(texto: string, rascunhoId: string | null): Promise<ResumoReciboOut> {
    if (semCredencial(token)) throw new Error("Sessão expirada. Entre de novo.");
    setEnviando(true);
    try {
      let r: Response;
      try {
        r = await apiFetch(url, {
          token: token ?? undefined,
          method: "POST",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify({ texto, ...(rascunhoId ? { "rascunho-id": rascunhoId } : {}) }),
        });
      } catch {
        throw new Error("Falha de rede — nada foi publicado. Tente de novo.");
      }
      if (!r.ok) {
        const corpo = (await r.json().catch(() => null)) as { erro?: string } | null;
        throw new Error(mensagemDeErroResumo(r.status, corpo?.erro));
      }
      const recibo = camelizarChaves(await r.json()) as ResumoReciboOut;
      setRev((n) => n + 1);
      return recibo;
    } finally {
      if (vivo.current) setEnviando(false);
    }
  }

  return { ...(semCredencial(token) ? ({ estado: "erro" } as Estado) : estado), enviando, lerRascunho, publicar };
}
