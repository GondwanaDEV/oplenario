"use client";

// As escritas do cidadão no portal (formulários do cidadão, ADR-0015): pedido de e-SIC, recurso, solicitação LGPD,
// manifestação de ouvidoria, comentário e acompanhar/deixar de acompanhar. Um hook só — todas são "manda um JSON
// para /api/portal/..., recebe o recibo" — com o mesmo guard de reentrância e a mesma tradução de erro para
// linguagem de gente. O backend responde erro opaco ("requisicao invalida"); a mensagem que a pessoa lê sai daqui.

import { useCallback, useEffect, useRef, useState } from "react";
import { apiFetch } from "./api-fetch";
import { camelizarChaves } from "./boundary";

type Estado = "ocioso" | "enviando" | "erro";

const MENSAGENS: Record<number, string> = {
  400: "Confira os campos e tente de novo.",
  401: "Sua sessão terminou. Entre de novo com o gov.br para enviar.",
  403: "Você não pode fazer isto com esta conta.",
  404: "Não encontramos o que você quer atualizar.",
  409: "Isto não pode ser feito agora.",
  413: "O texto ficou grande demais. Resuma e tente de novo.",
};
const GENERICA = "Não foi possível enviar agora. Tente de novo em instantes.";

export function useEnvioCidadao(token: string | null) {
  const [estado, setEstado] = useState<Estado>("ocioso");
  const [erro, setErro] = useState<string | null>(null);
  const enviandoRef = useRef(false);
  const vivoRef = useRef(true);
  useEffect(() => {
    vivoRef.current = true;
    return () => {
      vivoRef.current = false;
    };
  }, []);

  const enviar = useCallback(
    async <T = unknown>(
      caminho: string,
      corpo?: unknown,
      metodo: "POST" | "DELETE" = "POST",
      mensagens: Partial<Record<number, string>> = {},
    ): Promise<T> => {
      if (enviandoRef.current) throw new Error("já enviando");
      enviandoRef.current = true;
      setEstado("enviando");
      setErro(null);
      try {
        let r: Response;
        try {
          r = await apiFetch(`/api${caminho}`, {
            token: token ?? undefined,
            method: metodo,
            cache: "no-store",
            ...(corpo === undefined
              ? {}
              : { headers: { "Content-Type": "application/json" }, body: JSON.stringify(corpo) }),
          });
        } catch {
          throw new Error(GENERICA);
        }
        if (!r.ok) throw new Error(mensagens[r.status] ?? MENSAGENS[r.status] ?? GENERICA);
        const dados = camelizarChaves(await r.json().catch(() => ({}))) as T;
        if (vivoRef.current) setEstado("ocioso");
        return dados;
      } catch (e) {
        const msg = e instanceof Error ? e.message : GENERICA;
        if (vivoRef.current) {
          setEstado("erro");
          setErro(msg);
        }
        throw new Error(msg);
      } finally {
        enviandoRef.current = false;
      }
    },
    [token],
  );

  return { enviar, estado, erro };
}
