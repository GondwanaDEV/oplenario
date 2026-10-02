"use client";

// ADR-0018 (fatia 1) — a faixa "Sistema da Câmara com acesso restrito desde DD/MM". A Casa suspensa continua no ar: o
// portal é transparência ativa (LAI art. 8), os protocolos do cidadão seguem e o servidor lê, exporta e responde a
// eles. O que não segue é a operação legislativa — o servidor recusa com 423, e esta faixa diz por quê ANTES do clique.
//
// Dois públicos, duas falas: o interno (servidor, vereador) vê o motivo; a cidadã e o portal só "acesso restrito" — o
// motivo comercial não é público. Quem decide o que vem no fio é o backend (`/eu` só manda `motivo` ao interno; o portal
// nunca o recebe); aqui só se mostra o que chegou.

import { useEffect, useState } from "react";
import { apiFetch } from "./api-fetch";
import { camelizarChaves } from "./boundary";

export type AcessoRestrito = { desde: string; motivo?: string | null };

const MOTIVOS: Record<string, string> = {
  inadimplencia: "inadimplência do contrato",
  pedido_da_casa: "pedido da própria Câmara",
  ordem_judicial: "ordem judicial",
  incidente_de_seguranca: "incidente de segurança",
  encerramento_em_curso: "encerramento do contrato em curso",
};

export function rotuloMotivoRestricao(motivo: string): string {
  return MOTIVOS[motivo] ?? motivo;
}

const DIA_MES = new Intl.DateTimeFormat("pt-BR", { day: "2-digit", month: "2-digit", timeZone: "America/Fortaleza" });

/** "DD/MM" no fuso da Casa (o dia em que a restrição começou, não o dia UTC). */
export function diaMes(iso: string): string {
  try {
    return DIA_MES.format(new Date(iso));
  } catch {
    return iso;
  }
}

export function textoDaFaixa({ desde, motivo }: AcessoRestrito): string {
  const base = `Sistema da Câmara com acesso restrito desde ${diaMes(desde)}`;
  return motivo ? `${base} — motivo: ${rotuloMotivoRestricao(motivo)}` : base;
}

/** A frase do recibo do protocolo do cidadão numa Casa suspensa (Eixo 2): o pedido vale e o prazo corre. */
export function avisoNoRecibo(desde: string): string {
  return `O sistema desta Câmara está com acesso restrito desde ${diaMes(desde)}. Seu pedido foi recebido normalmente e o prazo legal de resposta está correndo.`;
}

export function FaixaAcessoRestrito({ restricao, interno }: { restricao: AcessoRestrito | null; interno?: boolean }) {
  if (!restricao) return null;
  return (
    <div className="faixa-acesso-restrito" role="status">
      <p>
        <b>{textoDaFaixa(restricao)}.</b>{" "}
        {interno
          ? "Você continua lendo, exportando e respondendo os pedidos do cidadão (e-SIC, ouvidoria, LGPD); o resto fica parado até a Câmara ser reativada."
          : "O portal segue no ar e os pedidos de informação, de ouvidoria e de dados pessoais continuam sendo recebidos."}
      </p>
    </div>
  );
}

/** A restrição da Casa da sessão, lida de `/eu` (mesmo rewrite dos outros hooks). Falha = sem faixa (a regra é do
 *  servidor; a faixa é aviso, não controle). */
export function useAcessoRestrito(token: string | null): AcessoRestrito | null {
  const [restricao, setRestricao] = useState<AcessoRestrito | null>(null);
  useEffect(() => {
    let vivo = true;
    (async () => {
      try {
        const r = await apiFetch("/api/eu", { token: token ?? undefined, cache: "no-store" });
        if (!r.ok) return;
        const d = camelizarChaves(await r.json()) as { acessoRestrito?: AcessoRestrito | null };
        if (vivo && d?.acessoRestrito?.desde) setRestricao(d.acessoRestrito);
      } catch {
        /* sem faixa */
      }
    })();
    return () => {
      vivo = false;
    };
  }, [token]);
  return restricao;
}

/** A faixa das áreas autenticadas (interno, vereador, cidadã): o motivo aparece só se o backend o mandou. */
export function FaixaAcessoRestritoDaSessao({ token }: { token: string | null }) {
  const restricao = useAcessoRestrito(token);
  return <FaixaAcessoRestrito restricao={restricao} interno={!!restricao?.motivo} />;
}
