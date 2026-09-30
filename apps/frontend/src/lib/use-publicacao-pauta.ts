"use client";

// Acesso a "publicar a pauta" (ADR-0019 fatia 3): a tela (GET /api/sessoes/:id/pauta/publicacao), o ato (POST na
// mesma rota) e a regra da Casa (GET/PUT /api/regra-da-pauta). A authz real é o backend (a regra de quem publica
// roda no servidor, com o cargo lido de cadastros); aqui só se traduz cada resposta em frase honesta. Uma leitura que
// falha vira erro na tela, nunca um "pode publicar" fingido.

import { useCallback, useEffect, useState } from "react";
import { apiFetch } from "./api-fetch";
import { camelizarChaves } from "./boundary";
import { semCredencial } from "./modo";
import type { PautaPublicadaOut, PublicacaoPautaOut, RegraPautaOut } from "./contrato-sessoes.gen";
import { mensagemDeRecusa, type QuemPublica } from "./publicacao-pauta-vista";

export type Resultado<T> = { ok: true; dado: T } | { ok: false; status: number; mensagem: string };
export type Carga<T> = { fase: "carregando" } | { fase: "pronto"; dado: T } | { fase: "erro"; mensagem: string };

async function chamar<T>(
  token: string | null,
  caminho: string,
  init: { method?: string; corpo?: unknown } = {},
  mensagem: (status: number, corpo: { erro?: string; motivo?: string }) => string,
): Promise<Resultado<T>> {
  if (semCredencial(token)) return { ok: false, status: 401, mensagem: "Sua sessão expirou. Entre de novo." };
  try {
    const r = await apiFetch(caminho, {
      token: token ?? undefined,
      cache: "no-store",
      method: init.method,
      ...(init.corpo !== undefined
        ? { headers: { "Content-Type": "application/json" }, body: JSON.stringify(init.corpo) }
        : {}),
    });
    const corpo = (await r.json().catch(() => ({}))) as { erro?: string; motivo?: string };
    if (!r.ok) return { ok: false, status: r.status, mensagem: mensagem(r.status, corpo) };
    return { ok: true, dado: camelizarChaves(corpo) as T };
  } catch {
    return { ok: false, status: 0, mensagem: mensagem(0, {}) };
  }
}

const caminhoPublicacao = (sessaoId: string) => `/api/sessoes/${encodeURIComponent(sessaoId)}/pauta/publicacao`;

/** Carrega a tela de publicar da sessão; `chave` muda quando a pauta viva muda (refaz a leitura dos avisos). */
export function usePublicacaoPauta(token: string | null, sessaoId: string | null, chave = "") {
  const [carga, setCarga] = useState<{ de: string | null; estado: Carga<PublicacaoPautaOut> }>({
    de: null,
    estado: { fase: "carregando" },
  });
  const [rev, setRev] = useState(0);
  const recarregar = useCallback(() => setRev((v) => v + 1), []);
  useEffect(() => {
    if (!sessaoId || semCredencial(token)) return;
    let vivo = true;
    (async () => {
      const r = await chamar<PublicacaoPautaOut>(token, caminhoPublicacao(sessaoId), {}, (s) =>
        s === 403 ? "Você não tem acesso à publicação desta pauta." : "Não foi possível carregar a publicação da pauta.",
      );
      if (vivo) setCarga({ de: sessaoId, estado: r.ok ? { fase: "pronto", dado: r.dado } : { fase: "erro", mensagem: r.mensagem } });
    })();
    return () => {
      vivo = false;
    };
  }, [token, sessaoId, chave, rev]);
  const estado: Carga<PublicacaoPautaOut> = carga.de === sessaoId ? carga.estado : { fase: "carregando" };
  return { estado, recarregar };
}

/** O ATO de publicar. `justificativa` só é enviada quando há texto (a republicação a exige). */
export function publicarPauta(token: string | null, sessaoId: string, justificativa?: string) {
  const texto = justificativa?.trim();
  return chamar<PautaPublicadaOut>(
    token,
    caminhoPublicacao(sessaoId),
    { method: "POST", corpo: texto ? { justificativa: texto } : {} },
    (s, c) => mensagemDeRecusa(s, c.motivo, c.erro),
  );
}

export function useRegraPauta(token: string | null) {
  const [estado, setEstado] = useState<Carga<RegraPautaOut>>({ fase: "carregando" });
  useEffect(() => {
    if (semCredencial(token)) return;
    let vivo = true;
    (async () => {
      const r = await chamar<RegraPautaOut>(token, "/api/regra-da-pauta", {}, () => "Não foi possível carregar a regra da pauta.");
      if (vivo) setEstado(r.ok ? { fase: "pronto", dado: r.dado } : { fase: "erro", mensagem: r.mensagem });
    })();
    return () => {
      vivo = false;
    };
  }, [token]);
  return { estado, definir: (r: RegraPautaOut) => setEstado({ fase: "pronto", dado: r }) };
}

export function salvarRegraPauta(token: string | null, quemPublica: QuemPublica, antecedenciaMinimaHoras: number | null) {
  return chamar<RegraPautaOut>(
    token,
    "/api/regra-da-pauta",
    { method: "PUT", corpo: { "quem-publica": quemPublica, "antecedencia-minima-horas": antecedenciaMinimaHoras } },
    (s) =>
      s === 403
        ? "Só o administrador da Casa muda a regra da pauta."
        : s === 400
          ? "Confira os campos: a antecedência vai de 1 a 720 horas."
          : "Não foi possível salvar a regra da pauta.",
  );
}
