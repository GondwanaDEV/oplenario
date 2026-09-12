"use client";

// Hook de GET /api/portal/acompanhamentos (fatia "demo-tres-consertos" #3) — a cidadã autenticada (vínculo
// `cidadao`, SEM papel) lendo as matérias que ela segue. Mirror de use-minhas-notificacoes.ts/
// use-meu-painel.ts: sempre busca via `apiFetch` (dev e real), sem parâmetro de identidade (é sempre "as
// minhas", resolvidas do ator na borda — anti-forja por construção).
//
// `transparencia` ainda não participa do codegen Malli->TS (contrato-portal.gen.ts não tem
// MinhaMateriaOut/MeusAcompanhamentosOut) — tipo à mão aqui, mesmo precedente de use-meu-voto.ts/use-eu.ts
// pra rotas pequenas sem geração ainda.
//
// `MinhaMateriaOut.indisponivel` (frente "truncamento-familia", achado real): a projeção de transparência
// pode não ter chegado ainda pra uma matéria seguida (LEFT JOIN sem par) — a tela TEM de mostrar isso com
// rótulo honesto, nunca esconder a linha nem quebrar lendo `tipo`/`ementa` como se existissem.

import { useEffect, useState } from "react";
import { apiFetch } from "./api-fetch";
import { camelizarChaves } from "./boundary";
import { semCredencial } from "./modo";

export type MinhaMateria = {
  proposicaoId: string;
  tipo: string | null;
  ano: number | null;
  sequencial: number | null;
  urnLex: string | null;
  ementa: string | null;
  estado: string | null;
  seguidoEm: string;
  indisponivel: boolean;
};

export type MeusAcompanhamentosOut = { acompanhamentos: MinhaMateria[]; acompanhamentosTotal: number };

type Estado = "carregando" | "pronto" | "erro";

function comoMinhaMateria(d: unknown): MinhaMateria | null {
  if (d === null || typeof d !== "object") return null;
  const m = d as Record<string, unknown>;
  if (typeof m.proposicaoId !== "string" || typeof m.seguidoEm !== "string") return null;
  return {
    proposicaoId: m.proposicaoId,
    tipo: typeof m.tipo === "string" ? m.tipo : null,
    ano: typeof m.ano === "number" ? m.ano : null,
    sequencial: typeof m.sequencial === "number" ? m.sequencial : null,
    urnLex: typeof m.urnLex === "string" ? m.urnLex : null,
    ementa: typeof m.ementa === "string" ? m.ementa : null,
    estado: typeof m.estado === "string" ? m.estado : null,
    seguidoEm: m.seguidoEm,
    indisponivel: Boolean(m.indisponivel),
  };
}

function comoMeusAcompanhamentos(d: unknown): MeusAcompanhamentosOut | null {
  if (d === null || typeof d !== "object") return null;
  const { acompanhamentos, acompanhamentosTotal } = d as Record<string, unknown>;
  if (!Array.isArray(acompanhamentos) || typeof acompanhamentosTotal !== "number") return null;
  const lista = acompanhamentos.map(comoMinhaMateria);
  if (lista.some((m) => m === null)) return null; // um item malformado -> corpo inteiro suspeito, fail-closed
  return { acompanhamentos: lista as MinhaMateria[], acompanhamentosTotal };
}

async function buscar(token: string | null): Promise<MeusAcompanhamentosOut | null> {
  const r = await apiFetch("/api/portal/acompanhamentos", { token: token ?? undefined, cache: "no-store" });
  if (!r.ok) return null;
  return comoMeusAcompanhamentos(camelizarChaves(await r.json()));
}

export function useMeusAcompanhamentos(token: string | null): { dados: MeusAcompanhamentosOut | null; estado: Estado } {
  const [dados, setDados] = useState<MeusAcompanhamentosOut | null>(null);
  const [estado, setEstado] = useState<Estado>("carregando");

  useEffect(() => {
    if (semCredencial(token)) return;
    let vivo = true;
    (async () => {
      try {
        const resultado = await buscar(token);
        if (!vivo) return;
        if (resultado === null) {
          setEstado("erro");
          return;
        }
        setDados(resultado);
        setEstado("pronto");
      } catch {
        if (vivo) setEstado("erro");
      }
    })();
    return () => {
      vivo = false;
    };
  }, [token]);

  if (semCredencial(token)) return { dados: null, estado: "erro" };
  return { dados, estado };
}
