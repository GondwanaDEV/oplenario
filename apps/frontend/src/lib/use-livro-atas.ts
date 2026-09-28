"use client";

// Hooks do LIVRO DE ATAS (Onda E) — o mesmo par para as duas superfícies:
//   • interno: GET /api/atas e /api/atas/{sessaoId}[?versao=N] (autenticado; a visibilidade da ata de sessão secreta
//     é decidida no servidor, por linha);
//   • portal:  GET /api/portal/casa/{ente}/atas e /atas/{sessaoId}[?versao=N] (anônimo; só sessões públicas).
// 404 da ata (inexistente, invisível ou versão que não existe) e falha de rede colapsam em "erro": a tela não
// distingue — separar seria dizer ao visitante do portal que uma sessão secreta existe.

import { useEffect, useState } from "react";
import { apiFetch } from "./api-fetch";
import { camelizarChaves } from "./boundary";
import { comToken } from "./nav";
import type { AtaDoLivroOut, LivroAtasOut } from "./contrato-sessoes.gen";

export type FonteLivro = { tipo: "interno"; token: string | null } | { tipo: "publico"; ente: string };

export type Carga<T> = { fase: "carregando" } | { fase: "erro" } | { fase: "pronto"; dado: T };

function base(f: FonteLivro): string {
  return f.tipo === "interno" ? "/api/atas" : `/api/portal/casa/${encodeURIComponent(f.ente)}/atas`;
}

async function buscar<T>(f: FonteLivro, caminho: string): Promise<T | null> {
  try {
    const r =
      f.tipo === "interno"
        ? await apiFetch(caminho, { token: f.token ?? undefined, cache: "no-store" })
        : await fetch(caminho, { cache: "no-store" });
    if (!r.ok) return null;
    return camelizarChaves(await r.json()) as T;
  } catch {
    return null;
  }
}

export function caminhoDaAta(f: FonteLivro, sessaoId: string, versao: number | null): string {
  const q = versao ? `?versao=${encodeURIComponent(String(versao))}` : "";
  return `${base(f)}/${encodeURIComponent(sessaoId)}${q}`;
}

/** O link de uma ata do livro na superfície da fonte: /atas (interno, com o dev-token) ou o portal da Casa. */
export function hrefAta(f: FonteLivro, sessaoId: string, versao?: number): string {
  const q = `?sessao=${encodeURIComponent(sessaoId)}${versao ? `&versao=${versao}` : ""}`;
  return f.tipo === "interno" ? comToken(`/atas${q}`, f.token) : `/portal/casa/${encodeURIComponent(f.ente)}/atas${q}`;
}

function chave(f: FonteLivro): string {
  return f.tipo === "interno" ? `i:${f.token ?? ""}` : `p:${f.ente}`;
}

function useCarga<T>(f: FonteLivro, caminho: string | null): Carga<T> {
  const [estado, setEstado] = useState<{ de: string | null; carga: Carga<T> }>({
    de: null,
    carga: { fase: "carregando" },
  });
  const k = caminho ? `${chave(f)} ${caminho}` : null;

  useEffect(() => {
    if (!k || !caminho) return;
    let vivo = true;
    buscar<T>(f, caminho).then((d) => {
      if (vivo) setEstado({ de: k, carga: d ? { fase: "pronto", dado: d } : { fase: "erro" } });
    });
    return () => {
      vivo = false;
    };
    // `f` entra pela chave (`k`): objeto novo a cada render não pode disparar nova busca.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [k]);

  // resultado de uma busca ANTERIOR (outra ata, outra versão) nunca aparece sob a chave nova
  return estado.de === k ? estado.carga : { fase: "carregando" };
}

export function useLivroAtas(f: FonteLivro): Carga<LivroAtasOut> {
  return useCarga<LivroAtasOut>(f, base(f));
}

/** `sessaoId` nulo = nada a abrir ainda (o livro está carregando ou vazio). */
export function useAtaDoLivro(f: FonteLivro, sessaoId: string | null, versao: number | null): Carga<AtaDoLivroOut> {
  return useCarga<AtaDoLivroOut>(f, sessaoId ? caminhoDaAta(f, sessaoId, versao) : null);
}
