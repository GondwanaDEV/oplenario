"use client";

// Hook da tela "Minha atuação" (app do vereador). Compõe três leituras que JÁ existem, sem rota nova:
//   1. GET /api/eu          → o `ente-id` da sessão (a Casa onde o vereador está logado);
//   2. GET /api/meu/painel  → o `vereadorId` resolvido do ator (anti-forja: nunca vem da URL) e os
//                             pareceres em que ele é relator;
//   3. GET /api/portal/casa/{ente}/vereadores/{vereadorId} → o perfil público (autoria, leis, presença,
//                             votos por opção) — os MESMOS números que o cidadão vê.
// 1 e 2 em paralelo; 3 depende dos dois. Qualquer falha colapsa em "erro"; um ator sem cadastro de
// vereador nesta Casa (`vereadorId` nulo) é estado próprio, não erro.

import { useEffect, useState } from "react";
import { apiFetch } from "./api-fetch";
import { camelizarChaves } from "./boundary";
import { buscarPublico } from "./portal-api";
import { semCredencial } from "./modo";
import type { MeuPainelOut } from "./contrato-legislativo.gen";
import type { PerfilVereadorOut } from "./contrato-portal.gen";

export type EstadoAtuacao =
  | { fase: "carregando" }
  | { fase: "erro" }
  | { fase: "sem-vereador" }
  | { fase: "pronto"; perfil: PerfilVereadorOut; painel: MeuPainelOut };

async function enteDaSessao(token: string | null): Promise<string | null> {
  const r = await apiFetch("/api/eu", { token: token ?? undefined, cache: "no-store" });
  if (!r.ok) return null;
  const d = (await r.json()) as { ator?: { "ente-id"?: unknown } };
  const ente = d?.ator?.["ente-id"];
  return typeof ente === "string" && ente.length > 0 ? ente : null;
}

async function painelDoVereador(token: string | null): Promise<MeuPainelOut | null> {
  const r = await apiFetch("/api/meu/painel", { token: token ?? undefined, cache: "no-store" });
  if (!r.ok) return null;
  return camelizarChaves(await r.json()) as MeuPainelOut;
}

export async function carregarAtuacao(token: string | null): Promise<EstadoAtuacao> {
  try {
    const [ente, painel] = await Promise.all([enteDaSessao(token), painelDoVereador(token)]);
    if (!ente || !painel) return { fase: "erro" };
    if (!painel.vereadorId) return { fase: "sem-vereador" };
    const perfil = await buscarPublico<PerfilVereadorOut>(ente, "vereadores", painel.vereadorId);
    if (!perfil) return { fase: "erro" };
    return { fase: "pronto", perfil, painel };
  } catch {
    return { fase: "erro" };
  }
}

export function useAtuacao(token: string | null): EstadoAtuacao {
  const [estado, setEstado] = useState<EstadoAtuacao>({ fase: "carregando" });

  useEffect(() => {
    if (semCredencial(token)) return; // derivado no retorno, sem setState síncrono no effect
    let vivo = true;
    carregarAtuacao(token).then((e) => {
      if (vivo) setEstado(e);
    });
    return () => {
      vivo = false;
    };
  }, [token]);

  if (semCredencial(token)) return { fase: "erro" };
  return estado;
}
