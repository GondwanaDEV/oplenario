"use client";

// Hook de mutação — POST /api/legislativo/autografos/:id/resposta ("Registrar retorno" do Executivo,
// Onda B Slice 7). Mirror de use-protocolar-documento.ts (id — aqui o AUTÓGRAFO-id — fixo no path,
// `lock-version` obrigatório = CAS real, mesmo contrato de wire/in/documento.ProtocolarDocumento) +
// corpoKebab filtrando `undefined` de use-criar-proposicao.ts (`veto-tipo`/`veto-razoes` só fazem sentido
// p/ resultado "vetado"). A resposta 200 é o TramitacaoExecutivaOut atualizado (não o composto inteiro —
// o autógrafo em si não muda ao registrar a resposta).

import { useEffect, useRef, useState } from "react";
import { apiFetch } from "./api-fetch";
import { camelizarChaves } from "./boundary";
import type { TramitacaoExecutivaOut } from "./contrato-legislativo.gen";
import { semCredencial } from "./modo";

export type RegistrarRespostaIn = {
  lockVersion: number;
  resultado: string;
  vetoTipo?: string;
  vetoRazoes?: string;
};

type Estado = "ocioso" | "enviando" | "erro";

function paraKebab(chave: string): string {
  return chave.replace(/[A-Z]/g, (c) => `-${c.toLowerCase()}`);
}

function corpoKebab(corpo: RegistrarRespostaIn): Record<string, unknown> {
  return Object.fromEntries(
    Object.entries(corpo)
      .filter(([, v]) => v !== undefined)
      .map(([k, v]) => [paraKebab(k), v]),
  );
}

export function useRegistrarResposta(token: string | null, autografoId: string | null) {
  const [estado, setEstado] = useState<Estado>("ocioso");
  const [erro, setErro] = useState<string | null>(null);
  const vivoRef = useRef(true);
  const enviandoRef = useRef(false);

  useEffect(() => {
    // RE-ARMA no mount. Sem esta linha o ref nasce FALSE sob React StrictMode (dev), porque o
    // StrictMode roda cleanup+setup no primeiro mount — e entao TODO setEstado pos-resposta vira
    // no-op e nenhuma mensagem de erro do servidor chega na tela. Medido: 1,5s depois de um 409 o
    // botao seguia "Salvando..." com zero alerta na pagina. Os hooks de LEITURA ja faziam assim.
    vivoRef.current = true;
    return () => {
      vivoRef.current = false;
    };
    }, []);

  async function registrar(corpo: RegistrarRespostaIn): Promise<TramitacaoExecutivaOut> {
    if (semCredencial(token)) {
      throw new Error("sem token de autenticacao");
    }
    if (!autografoId) {
      throw new Error("autografo ainda nao foi gerado");
    }
    if (enviandoRef.current) {
      throw new Error("envio em andamento");
    }
    enviandoRef.current = true;
    setEstado("enviando");
    setErro(null);
    let tratado = false;
    try {
      const r = await apiFetch(`/api/legislativo/autografos/${encodeURIComponent(autografoId)}/resposta`, {
        token: token ?? undefined,
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(corpoKebab(corpo)),
      });
      if (!r.ok) {
        const corpoErro = await r.json().catch(() => null);
        const msg = corpoErro?.erro ?? `falha ao registrar retorno (status ${r.status})`;
        tratado = true;
        if (vivoRef.current) {
          setEstado("erro");
          setErro(msg);
        }
        throw new Error(msg);
      }
      const dados = camelizarChaves(await r.json()) as TramitacaoExecutivaOut;
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

  return { registrar, estado, erro };
}
