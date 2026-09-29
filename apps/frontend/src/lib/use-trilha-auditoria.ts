"use client";

// Hooks da TRILHA DE AUDITORIA (ADR-0017): GET /api/auditoria (a página do escopo do papel, mais recente primeiro;
// "carregar mais" pede `antes-de` = o último seq visto), GET /api/auditoria/integridade (a cadeia conferida inteira —
// só para quem vê a Casa inteira) e o download do CSV. Tudo por `apiFetch` (Bearer em dev, cookie em produção): o
// link cru do CSV não levaria a credencial no modo dev, por isso o download é um fetch + blob.

import { useCallback, useEffect, useState } from "react";
import { apiFetch } from "./api-fetch";
import { camelizarChaves } from "./boundary";
import { semCredencial } from "./modo";
import { queryDoFiltro, type Filtro, type Integridade, type Trilha } from "./trilha-auditoria-vista";

type Estado = "carregando" | "pronto" | "erro" | "sem-sessao" | "invalido";

async function pagina(q: string, token: string | null): Promise<{ status: number; trilha: Trilha | null }> {
  const r = await apiFetch(`/api/auditoria${q ? `?${q}` : ""}`, { token: token ?? undefined, cache: "no-store" });
  if (!r.ok) return { status: r.status, trilha: null };
  return { status: r.status, trilha: camelizarChaves(await r.json()) as Trilha };
}

export function useTrilhaAuditoria(filtro: Filtro, token: string | null, agora: Date) {
  // O resultado guarda PARA QUAL query ele vale: trocar o filtro volta a "carregando" por derivação, sem setState
  // síncrono dentro do effect.
  const [res, setRes] = useState<{ q: string; estado: Estado; trilha: Trilha | null }>({ q: "", estado: "carregando", trilha: null });
  const [maisEstado, setMaisEstado] = useState<"ocioso" | "carregando" | "erro">("ocioso");
  const q = queryDoFiltro(filtro, agora);

  useEffect(() => {
    if (semCredencial(token)) return;
    let vivo = true;
    (async () => {
      try {
        const { status, trilha: t } = await pagina(q, token);
        if (!vivo) return;
        if (status === 401) return setRes({ q, estado: "sem-sessao", trilha: null });
        if (status === 400) return setRes({ q, estado: "invalido", trilha: null });
        if (!t) return setRes({ q, estado: "erro", trilha: null });
        setRes({ q, estado: "pronto", trilha: t });
      } catch {
        if (vivo) setRes({ q, estado: "erro", trilha: null });
      }
    })();
    return () => {
      vivo = false;
    };
  }, [q, token]);

  const trilha = res.q === q ? res.trilha : null;
  const carregarMais = useCallback(async () => {
    if (!trilha?.proximo) return;
    setMaisEstado("carregando");
    try {
      const { trilha: t } = await pagina(queryDoFiltro(filtro, agora, trilha.proximo), token);
      if (!t) return setMaisEstado("erro");
      setRes({ q, estado: "pronto", trilha: { ...trilha, registros: [...trilha.registros, ...t.registros], proximo: t.proximo } });
      setMaisEstado("ocioso");
    } catch {
      setMaisEstado("erro");
    }
  }, [trilha, filtro, agora, token, q]);

  if (semCredencial(token)) return { trilha: null, estado: "erro" as Estado, carregarMais, maisEstado };
  return { trilha, estado: res.q === q ? res.estado : ("carregando" as Estado), carregarMais, maisEstado };
}

export function useIntegridade(ativo: boolean, token: string | null) {
  const [dados, setDados] = useState<Integridade | null>(null);
  const [estado, setEstado] = useState<"carregando" | "pronto" | "erro">("carregando");
  useEffect(() => {
    if (!ativo || semCredencial(token)) return;
    let vivo = true;
    (async () => {
      try {
        const r = await apiFetch("/api/auditoria/integridade", { token: token ?? undefined, cache: "no-store" });
        if (!vivo) return;
        if (!r.ok) return setEstado("erro");
        setDados(camelizarChaves(await r.json()) as Integridade);
        setEstado("pronto");
      } catch {
        if (vivo) setEstado("erro");
      }
    })();
    return () => {
      vivo = false;
    };
  }, [ativo, token]);
  return { dados, estado };
}

/** Baixa o CSV do filtro atual (o próprio download fica registrado na trilha, pelo servidor). */
export async function exportarTrilha(filtro: Filtro, token: string | null, agora: Date): Promise<void> {
  const q = queryDoFiltro(filtro, agora);
  const r = await apiFetch(`/api/auditoria/exportar.csv${q ? `?${q}` : ""}`, { token: token ?? undefined, cache: "no-store" });
  if (!r.ok) throw new Error(`exportar ${r.status}`);
  const url = URL.createObjectURL(await r.blob());
  try {
    const a = document.createElement("a");
    a.href = url;
    a.download = "trilha-de-auditoria.csv";
    document.body.appendChild(a);
    a.click();
    a.remove();
  } finally {
    URL.revokeObjectURL(url);
  }
}
