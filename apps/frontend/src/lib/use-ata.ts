"use client";

// Hook da ATA DA SESSÃO (Faixa A / A.6a): GET /api/sessoes/:id/ata (a vigente + o histórico) e POST (publicar ou
// retificar). Depois de publicar, relê — a tela mostra o que o servidor congelou, nunca o que o cliente acha. Tipos GERADOS.

import { useEffect, useRef, useState } from "react";
import { apiFetch } from "./api-fetch";
import { camelizarChaves } from "./boundary";
import { semCredencial } from "./modo";
import { mensagemDeErroAta } from "./ata-vista";
import type { AtaRascunhoConteudoOut, AtaReciboOut, AtaSessaoOut } from "./contrato-sessoes.gen";

type Estado = { estado: "carregando" } | { estado: "erro" } | { estado: "pronto"; ata: AtaSessaoOut };

export type Publicacao = { texto: string; motivoRetificacao?: string; rascunhoId?: string };

/** Enquanto a IA redige, a tela relê a ata a cada tanto (o pedido vira 'pronto' ou 'falhou' sozinho). */
export const INTERVALO_ACOMPANHAR_MS = 5000;

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
        // releitura que falha (acompanhando a IA) NÃO apaga a tela já carregada — nem o texto no editor
        const falhou = (prev: Estado): Estado => (prev.estado === "pronto" ? prev : { estado: "erro" });
        if (!r.ok) return setEstado(falhou);
        const ata = camelizarChaves(await r.json()) as AtaSessaoOut;
        if (vivo.current) setEstado({ estado: "pronto", ata });
      } catch {
        if (vivo.current) setEstado((prev) => (prev.estado === "pronto" ? prev : { estado: "erro" }));
      }
    })();
    return () => {
      vivo.current = false;
    };
  }, [url, token, rev]);

  const redigindo = estado.estado === "pronto" && estado.ata.rascunho?.situacao === "solicitado";
  useEffect(() => {
    if (!redigindo) return;
    const t = setInterval(() => setRev((n) => n + 1), INTERVALO_ACOMPANHAR_MS);
    return () => clearInterval(t);
  }, [redigindo]);

  async function erroDe(r: Response, padrao: string): Promise<Error> {
    const corpo = (await r.json().catch(() => null)) as { erro?: string } | null;
    return new Error(r.status === 409 || r.status === 503 ? (corpo?.erro ?? padrao) : padrao);
  }

  /** Pede o rascunho à IA (202: ela redige em segundo plano). Lança Error com a frase da tela. */
  async function pedirRascunho(): Promise<void> {
    if (semCredencial(token)) throw new Error("Sessão expirada. Entre de novo.");
    let r: Response;
    try {
      r = await apiFetch(`${url}/rascunhos`, { token: token ?? undefined, method: "POST" });
    } catch {
      throw new Error("Falha de rede — o pedido não chegou. Tente de novo.");
    }
    if (!r.ok) throw await erroDe(r, "Não foi possível pedir o rascunho agora.");
    setRev((n) => n + 1);
  }

  /** O rascunho para revisar (lido da IA pelo core). IA fora: lança Error com a mensagem R-IA-1. */
  async function lerRascunho(rascunhoId: string): Promise<AtaRascunhoConteudoOut> {
    let r: Response;
    try {
      r = await apiFetch(`${url}/rascunhos/${encodeURIComponent(rascunhoId)}`, { token: token ?? undefined, cache: "no-store" });
    } catch {
      throw new Error("Falha de rede — não foi possível abrir o rascunho.");
    }
    if (!r.ok) throw await erroDe(r, "Não foi possível abrir o rascunho agora.");
    return camelizarChaves(await r.json()) as AtaRascunhoConteudoOut;
  }

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
          body: JSON.stringify({
            texto: p.texto,
            "motivo-retificacao": p.motivoRetificacao ?? null,
            ...(p.rascunhoId ? { "origem-redacao": "gerada_automaticamente", "rascunho-id": p.rascunhoId } : {}),
          }),
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

  return {
    ...(semCredencial(token) ? ({ estado: "erro" } as Estado) : estado),
    publicar,
    enviando,
    pedirRascunho,
    lerRascunho,
  };
}
