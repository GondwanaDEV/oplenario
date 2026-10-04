"use client";

// GET /api/portal/meus-protocolos (formulários do cidadão, ADR-0015): o que a cidadã protocolou na Casa da sessão
// — pedidos de e-SIC, solicitações LGPD e manifestações identificadas —, cada um com estado, prazo e a resposta
// mais recente. Mesmo molde de use-meus-acompanhamentos (apiFetch nos dois modos; "as minhas" vêm do ator na borda).

import { useCallback, useEffect, useState } from "react";
import { apiFetch } from "./api-fetch";
import { CAMPO_DO_ANEXO, mensagemDeErroDoAnexoDoRequerente } from "./anexos-do-atendimento";
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
/** Um arquivo do protocolo — da Casa (na resposta) ou do requerente (no pedido), pela `origem`. O servidor não manda a chave do
 *  object storage, nem quem enviou, nem o MOTIVO de uma retirada. `retiradoEm`: a Casa retirou o arquivo (incidente de
 *  conteúdo) — sem link para baixar, o download é 404. */
export type Anexo = { id: string; nome: string; tipoMidia: string; bytes: number; origem: string; enviadoEm: string; retiradoEm?: string | null };
/** `podeAnexar`: o requerente ainda pode juntar arquivo ao PRÓPRIO pedido (10 minutos do protocolo, até 5 seus). */
export type PedidoEsic = Base & { assunto: string; recurso?: RecursoEsic | null; prorrogacao?: Prorrogacao | null; anexos?: Anexo[]; podeAnexar?: boolean };
export type SolicitacaoLgpd = Base & { tipo: string; anexos?: Anexo[]; podeAnexar?: boolean };
export type Manifestacao = Base & { tipo: string; assunto: string; prorrogacao?: Prorrogacao | null; anexos?: Anexo[]; podeAnexar?: boolean };
export type EspecieDoPortal = "esic" | "ouvidoria" | "lgpd";
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

const LISTA_DA_ESPECIE: Record<EspecieDoPortal, "pedidosEsic" | "manifestacoes" | "solicitacoesLgpd"> = {
  esic: "pedidosEsic",
  ouvidoria: "manifestacoes",
  lgpd: "solicitacoesLgpd",
};

/** O recibo do protocolo traz o NÚMERO (o id interno não sai nele). Para anexar é preciso o id, que o dono lê em
 *  "Meus protocolos": acha o item pelo número. null = não achou (ou a leitura falhou). */
export async function acharIdDoProtocolo(token: string | null, especie: EspecieDoPortal, protocolo: string): Promise<string | null> {
  try {
    const r = await apiFetch("/api/portal/meus-protocolos", { token: token ?? undefined, cache: "no-store" });
    if (!r.ok) return null;
    const d = camelizarChaves(await r.json()) as Partial<Record<(typeof LISTA_DA_ESPECIE)[EspecieDoPortal], { id: string; protocolo: string }[]>>;
    return (d[LISTA_DA_ESPECIE[especie]] ?? []).find((i) => i.protocolo === protocolo)?.id ?? null;
  } catch {
    return null;
  }
}

/** O requerente anexa UM arquivo ao PRÓPRIO protocolo (multipart, campo `arquivo`; sem Content-Type: o navegador põe o
 *  boundary). Falha vira frase da tela, nunca silêncio. */
export async function anexarAoMeuProtocolo(
  token: string | null,
  especie: EspecieDoPortal,
  id: string,
  arquivo: File,
): Promise<{ ok: true } | { ok: false; status: number; mensagem: string }> {
  try {
    const fd = new FormData();
    fd.append(CAMPO_DO_ANEXO, arquivo, arquivo.name);
    const r = await apiFetch(`/api/portal/meus-protocolos/${especie}/${encodeURIComponent(id)}/anexos`, {
      token: token ?? undefined,
      method: "POST",
      cache: "no-store",
      body: fd,
    });
    if (r.ok) return { ok: true };
    const corpo = (await r.json().catch(() => ({}))) as { erro?: unknown };
    return { ok: false, status: r.status, mensagem: mensagemDeErroDoAnexoDoRequerente(r.status, typeof corpo.erro === "string" ? corpo.erro : undefined) };
  } catch {
    return { ok: false, status: 0, mensagem: mensagemDeErroDoAnexoDoRequerente(0) };
  }
}
