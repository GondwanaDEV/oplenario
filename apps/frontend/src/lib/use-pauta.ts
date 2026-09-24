"use client";

// Hook da pauta viva: GET /api/sessoes/:id/pauta (one-shot autenticado). A pauta AINDA NÃO é empurrada ao
// vivo (não há evento de pauta no canal) — então re-busca quando a `fase` da sessão muda (ex.: abertura),
// que é quando a pauta tende a mudar de forma observável. Mesmo padrão de auth/validação do use-plenario.

import { useCallback, useEffect, useState } from "react";
import { apiFetch } from "./api-fetch";
import type { PautaOut } from "./contrato";
import { semCredencial } from "./modo";

const ID_VALIDO = /^[a-zA-Z0-9_-]{1,128}$/;

export type EstadoPauta = "carregando" | "ok" | "erro";

export function usePauta(sessaoId: string, token: string | null, fase: string | null) {
  const [pauta, setPauta] = useState<PautaOut | null>(null);
  const [estado, setEstado] = useState<EstadoPauta>("carregando");
  const idValido = ID_VALIDO.test(sessaoId);
  // Sobe a cada `recarregar()` — depois de uma escrita na pauta (item extrapauta no cockpit, docs/23).
  const [versao, setVersao] = useState(0);
  const recarregar = useCallback(() => setVersao((v) => v + 1), []);

  useEffect(() => {
    if (semCredencial(token) || !idValido) return; // sem credencial/id válido: não busca (a UI degrada p/ "indisponível")
    const controller = new AbortController();
    let vivo = true;
    (async () => {
      try {
        const resp = await apiFetch(`/api/sessoes/${sessaoId}/pauta`, {
          token: token ?? undefined,
          signal: controller.signal,
          cache: "no-store",
        });
        if (!resp.ok) throw new Error(`pauta ${resp.status}`);
        const p = (await resp.json()) as PautaOut;
        if (!vivo) return;
        setPauta(p);
        setEstado("ok");
      } catch {
        if (!vivo || controller.signal.aborted) return;
        setEstado("erro"); // pauta é secundária no painel: erro aqui não derruba a tela, só some a seção
      }
    })();
    return () => {
      vivo = false;
      controller.abort();
    };
  }, [sessaoId, token, idValido, fase, versao]);

  return { pauta, estado, recarregar };
}
