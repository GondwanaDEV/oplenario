"use client";

// GET /api/portal/meus-protocolos (formulários do cidadão, ADR-0015): o que a cidadã protocolou na Casa da sessão
// — pedidos de e-SIC, solicitações LGPD e manifestações identificadas —, cada um com estado, prazo e a resposta
// mais recente. Mesmo molde de use-meus-acompanhamentos (apiFetch nos dois modos; "as minhas" vêm do ator na borda).

import { useCallback, useEffect, useState } from "react";
import { apiFetch } from "./api-fetch";
import { camelizarChaves } from "./boundary";

export type Resposta = { corpo: string; respondidaEm: string };
type Base = {
  id: string;
  protocolo: string;
  estado: string;
  reciboEm: string;
  venceEm: string | null;
  diasRestantes: number | null;
  resposta: Resposta | null;
};
export type RecursoEsic = { protocolo: string; estado: string; reciboEm: string; resposta: Resposta | null };
/** A prorrogação do prazo (LAI art. 11 §2º): as duas datas (AAAA-MM-DD), a justificativa da Câmara e quando foi (ISO). */
export type Prorrogacao = { deData: string; paraData: string; justificativa: string; prorrogadoEm: string };
export type PedidoEsic = Base & { assunto: string; recurso?: RecursoEsic | null; prorrogacao?: Prorrogacao | null };
export type SolicitacaoLgpd = Base & { tipo: string };
export type Manifestacao = Base & { tipo: string; assunto: string; prorrogacao?: Prorrogacao | null };
export type MeusProtocolos = { pedidosEsic: PedidoEsic[]; solicitacoesLgpd: SolicitacaoLgpd[]; manifestacoes: Manifestacao[] };

type Estado = "carregando" | "pronto" | "erro";

export function useMeusProtocolos(token: string | null) {
  const [dados, setDados] = useState<MeusProtocolos | null>(null);
  const [estado, setEstado] = useState<Estado>("carregando");
  const [versao, setVersao] = useState(0);

  useEffect(() => {
    let vivo = true;
    (async () => {
      try {
        const r = await apiFetch("/api/portal/meus-protocolos", { token: token ?? undefined, cache: "no-store" });
        if (!r.ok) throw new Error(`meus-protocolos ${r.status}`);
        const d = camelizarChaves(await r.json()) as Partial<MeusProtocolos>;
        if (!vivo) return;
        setDados({
          pedidosEsic: Array.isArray(d.pedidosEsic) ? d.pedidosEsic : [],
          solicitacoesLgpd: Array.isArray(d.solicitacoesLgpd) ? d.solicitacoesLgpd : [],
          manifestacoes: Array.isArray(d.manifestacoes) ? d.manifestacoes : [],
        });
        setEstado("pronto");
      } catch {
        if (vivo) setEstado("erro");
      }
    })();
    return () => {
      vivo = false;
    };
  }, [token, versao]);

  const recarregar = useCallback(() => setVersao((v) => v + 1), []);
  return { dados, estado, recarregar };
}
