"use client";

// Hooks de mutação da NORMA (F3.8b) — os dois atos que fecham "da aprovação à lei":
//   * usePromulgarNorma: POST /api/legislativo/proposicoes/:id/norma (corpo vazio — espécie, número, URN, texto e
//     data saem do que já está registrado). 201 = PosAprovacaoOut inteiro, com a `norma` nova.
//   * usePublicarNorma: POST /api/legislativo/normas/:id/publicacao ({lock-version, veiculo-publicacao}). 200 =
//     NormaOut publicada.
// Mesma disciplina de use-apreciar-veto.ts: um envio por vez, `vivoRef` re-armado no mount (StrictMode), a
// mensagem do servidor vai para `erro` (o 409 da promulgação diz POR QUE ainda não se promulga).

import { useEffect, useRef, useState } from "react";
import { apiFetch } from "./api-fetch";
import { camelizarChaves } from "./boundary";
import type { NormaOut, PosAprovacaoOut } from "./contrato-legislativo.gen";
import { semCredencial } from "./modo";

type Estado = "ocioso" | "enviando" | "erro";

function useEnvio<T>(token: string | null, falha: string) {
  const [estado, setEstado] = useState<Estado>("ocioso");
  const [erro, setErro] = useState<string | null>(null);
  const vivoRef = useRef(true);
  const enviandoRef = useRef(false);

  useEffect(() => {
    vivoRef.current = true;
    return () => {
      vivoRef.current = false;
    };
  }, []);

  async function enviar(caminho: string, corpo: Record<string, unknown>): Promise<T> {
    if (semCredencial(token)) throw new Error("sem token de autenticacao");
    if (enviandoRef.current) throw new Error("envio em andamento");
    enviandoRef.current = true;
    setEstado("enviando");
    setErro(null);
    let tratado = false;
    try {
      const r = await apiFetch(caminho, {
        token: token ?? undefined,
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(corpo),
      });
      if (!r.ok) {
        const corpoErro = await r.json().catch(() => null);
        const msg = corpoErro?.erro ?? `${falha} (status ${r.status})`;
        tratado = true;
        if (vivoRef.current) {
          setEstado("erro");
          setErro(msg);
        }
        throw new Error(msg);
      }
      const dados = camelizarChaves(await r.json()) as T;
      if (vivoRef.current) setEstado("ocioso");
      return dados;
    } catch (e) {
      if (vivoRef.current && !tratado) {
        setEstado("erro");
        setErro("falha de rede — tente novamente");
      }
      throw e;
    } finally {
      enviandoRef.current = false;
    }
  }

  return { enviar, estado, erro };
}

export function usePromulgarNorma(token: string | null, proposicaoId: string) {
  const { enviar, estado, erro } = useEnvio<PosAprovacaoOut>(token, "falha ao promulgar");
  const promulgar = () => enviar(`/api/legislativo/proposicoes/${encodeURIComponent(proposicaoId)}/norma`, {});
  return { promulgar, estado, erro };
}

export function usePublicarNorma(token: string | null, normaId: string | null) {
  const { enviar, estado, erro } = useEnvio<NormaOut>(token, "falha ao registrar a publicação");
  async function publicar(corpo: { lockVersion: number; veiculoPublicacao: string }): Promise<NormaOut> {
    if (!normaId) throw new Error("a norma ainda nao existe");
    return enviar(`/api/legislativo/normas/${encodeURIComponent(normaId)}/publicacao`, {
      "lock-version": corpo.lockVersion,
      "veiculo-publicacao": corpo.veiculoPublicacao,
    });
  }
  return { publicar, estado, erro };
}
