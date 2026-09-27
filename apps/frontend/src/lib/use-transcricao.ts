"use client";

// Hook da TRANSCRIÇÃO DA SESSÃO (Faixa A / A.3): GET /api/sessoes/:id/transcricoes (as situações) e, para cada
// gravação com transcrição concluída, GET /api/sessoes/:id/transcricoes/:tid (o texto, que o core lê da IA).
// IA fora do ar (503) não é erro da tela: vira a mensagem R-IA-1 daquela gravação. Tipos GERADOS.

import { useEffect, useState } from "react";
import { apiFetch } from "./api-fetch";
import { camelizarChaves } from "./boundary";
import { semCredencial } from "./modo";
import { atuaisPorGravacao } from "./transcricao-vista";
import type { TranscricaoConteudoOut, TranscricaoPonteiroOut, TranscricoesOut } from "./contrato-sessoes.gen";

export type GravacaoTranscrita = {
  ponteiro: TranscricaoPonteiroOut;
  conteudo: TranscricaoConteudoOut | null;
  indisponivel: string | null;
};

type Estado = { estado: "carregando" } | { estado: "erro" } | { estado: "pronto"; gravacoes: GravacaoTranscrita[] };

async function conteudoDe(id: string, p: TranscricaoPonteiroOut, token: string | null): Promise<GravacaoTranscrita> {
  if (p.situacao !== "concluida" || !p.transcricaoId) return { ponteiro: p, conteudo: null, indisponivel: null };
  const r = await apiFetch(`/api/sessoes/${encodeURIComponent(id)}/transcricoes/${encodeURIComponent(p.transcricaoId)}`,
    { token: token ?? undefined, cache: "no-store" });
  if (r.ok) return { ponteiro: p, conteudo: camelizarChaves(await r.json()) as TranscricaoConteudoOut, indisponivel: null };
  let msg = "Não foi possível abrir o texto desta transcrição agora.";
  if (r.status === 503) {
    try {
      msg = ((await r.json()) as { erro?: string }).erro ?? msg;
    } catch {
      /* corpo não-JSON: fica a mensagem padrão */
    }
  }
  return { ponteiro: p, conteudo: null, indisponivel: msg };
}

export function useTranscricao(id: string, token: string | null): Estado {
  const [estado, setEstado] = useState<Estado>({ estado: "carregando" });
  useEffect(() => {
    if (semCredencial(token)) return;
    let ativo = true;
    (async () => {
      try {
        const r = await apiFetch(`/api/sessoes/${encodeURIComponent(id)}/transcricoes`, { token: token ?? undefined, cache: "no-store" });
        if (!r.ok) {
          if (ativo) setEstado({ estado: "erro" });
          return;
        }
        const lista = camelizarChaves(await r.json()) as TranscricoesOut;
        const gravacoes = await Promise.all(atuaisPorGravacao(lista.itens).map((p) => conteudoDe(id, p, token)));
        if (ativo) setEstado({ estado: "pronto", gravacoes });
      } catch {
        if (ativo) setEstado({ estado: "erro" });
      }
    })();
    return () => {
      ativo = false;
    };
  }, [id, token]);
  return semCredencial(token) ? { estado: "erro" } : estado;
}
