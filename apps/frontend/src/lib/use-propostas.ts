"use client";

// As propostas de ato do assistente (Faixa B / B.6, ADR-0012): GET /api/propostas, GET /api/propostas/:id, e a
// decisão da pessoa — POST .../confirmacao ou .../recusa. A authz real é o backend (só a própria pessoa, e a ação
// confere o papel dela ao executar).

import { useCallback, useEffect, useState } from "react";
import { apiFetch } from "./api-fetch";
import { camelizarChaves } from "./boundary";
import { mensagemDeErroProposta, type PropostaOut } from "./propostas-vista";

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
    return { ok: false, mensagem: mensagemDeErroProposta(r.status, (corpo as { causa?: string }).causa) };
  } catch {
    return { ok: false, mensagem: mensagemDeErroProposta(0) };
  }
}

type Carga<T> = { fase: "carregando" } | { fase: "pronto"; dado: T } | { fase: "erro"; mensagem: string };

export function usePropostas(token: string | null) {
  const [estado, setEstado] = useState<Carga<PropostaOut[]>>({ fase: "carregando" });
  useEffect(() => {
    let vivo = true;
    (async () => {
      const r = await pedir<{ itens: PropostaOut[] }>(token, "/api/propostas");
      if (vivo) setEstado(r.ok ? { fase: "pronto", dado: r.dado.itens } : { fase: "erro", mensagem: r.mensagem });
    })();
    return () => {
      vivo = false;
    };
  }, [token]);
  return estado;
}

export function useProposta(token: string | null, id: string) {
  const [estado, setEstado] = useState<Carga<PropostaOut>>({ fase: "carregando" });
  const [enviando, setEnviando] = useState(false);
  const [erro, setErro] = useState<string | null>(null);

  useEffect(() => {
    let vivo = true;
    (async () => {
      const r = await pedir<PropostaOut>(token, `/api/propostas/${id}`);
      if (vivo) setEstado(r.ok ? { fase: "pronto", dado: r.dado } : { fase: "erro", mensagem: r.mensagem });
    })();
    return () => {
      vivo = false;
    };
  }, [token, id]);

  const decidir = useCallback(
    async (decisao: "confirmacao" | "recusa") => {
      setEnviando(true);
      setErro(null);
      const r = await pedir<PropostaOut>(token, `/api/propostas/${id}/${decisao}`, { method: "POST", body: "{}" });
      setEnviando(false);
      if (r.ok) setEstado({ fase: "pronto", dado: r.dado });
      else setErro(r.mensagem);
      return r.ok;
    },
    [token, id],
  );

  return { estado, enviando, erro, confirmar: () => decidir("confirmacao"), recusar: () => decidir("recusa") };
}
