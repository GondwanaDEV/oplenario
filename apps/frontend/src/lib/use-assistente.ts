"use client";

// A Clara, assistente da Casa (Faixa B / B.3): POST /api/agente/perguntas devolve a conversa em SSE. A tela mostra as
// perguntas desta conversa em ordem e uma de cada vez — enquanto a Clara responde, o campo espera. O registro é do
// core (ADR-0024): cada pergunta vira uma linha do histórico da Casa, e a próxima pergunta leva o `conversa` que o
// `fim` devolveu, para continuar na mesma conversa. Nada vai para o armazenamento do navegador.

import { useCallback, useRef, useState } from "react";
import { apiFetch } from "./api-fetch";
import {
  lerConversa,
  mensagemDeErroAssistente,
  type Conversa,
  type ConversaGuardada,
  type ItemHistorico,
} from "./assistente-vista";
import { camelizarChaves } from "./boundary";

export type Turno = { pergunta: string } & (
  | { fase: "respondendo" }
  | { fase: "pronto"; conversa: Conversa }
  | { fase: "erro"; mensagem: string }
);

/** `publico`: qual conjunto de ferramentas o agente oferece (o core confere contra os papéis). Quem tem os dois papéis
 *  escolhe pela tela onde está: o app do vereador pede "vereador"; sem ele, o core escolhe a secretaria. */
export async function perguntarAoAssistente(
  token: string | null,
  pergunta: string,
  publico?: "secretaria" | "vereador",
  conversa?: string | null,
): Promise<Conversa | string> {
  try {
    const r = await apiFetch("/api/agente/perguntas", {
      token: token ?? undefined,
      method: "POST",
      headers: { "Content-Type": "application/json", Accept: "text/event-stream" },
      body: JSON.stringify({ pergunta, ...(publico ? { publico } : {}), ...(conversa ? { conversa } : {}) }),
      cache: "no-store",
    });
    if (!r.ok) return mensagemDeErroAssistente(r.status);
    return lerConversa(await r.text());
  } catch {
    return mensagemDeErroAssistente(0);
  }
}

export function useAssistente(token: string | null, publico?: "secretaria" | "vereador") {
  const [turnos, setTurnos] = useState<Turno[]>([]);
  // A conversa em curso (ADR-0024): vem do `fim` da primeira resposta guardada. Ref, e não estado: só é lida ao enviar.
  // `geracao` descarta a resposta que chega depois de "Nova conversa".
  const conversaId = useRef<string | null>(null);
  const geracao = useRef(0);
  const ocupado = turnos.some((t) => t.fase === "respondendo");

  const perguntar = useCallback(
    async (pergunta: string) => {
      const g = geracao.current;
      setTurnos((ts) => [...ts, { pergunta, fase: "respondendo" }]);
      const r = await perguntarAoAssistente(token, pergunta, publico, conversaId.current);
      if (g !== geracao.current) return;
      if (typeof r !== "string" && r.conversaId) conversaId.current = r.conversaId;
      setTurnos((ts) =>
        ts.map((t, i) =>
          i === ts.length - 1 && t.fase === "respondendo"
            ? typeof r === "string"
              ? { pergunta, fase: "erro", mensagem: r }
              : { pergunta, fase: "pronto", conversa: r }
            : t,
        ),
      );
    },
    [token, publico],
  );

  const novaConversa = useCallback(() => {
    geracao.current += 1;
    conversaId.current = null;
    setTurnos([]);
  }, []);

  return { turnos, ocupado, perguntar, novaConversa };
}

// ---------------------------------------------------------------------------
// O histórico da Clara (ADR-0024): cada pessoa lê o seu. Erro vira frase, nunca exceção.
// ---------------------------------------------------------------------------

export type PaginaDoHistorico = { interacoes: ItemHistorico[]; mais: boolean; antes: string | null };

export const ERRO_HISTORICO = "Não foi possível abrir o histórico agora. Tente de novo em instantes.";
export const ERRO_CONVERSA_GUARDADA = "Não foi possível abrir esta conversa agora. Tente de novo em instantes.";

export async function lerHistorico(token: string | null, antes?: string | null): Promise<PaginaDoHistorico | string> {
  try {
    const q = antes ? `?antes=${encodeURIComponent(antes)}` : "";
    const r = await apiFetch(`/api/agente/historico${q}`, { token: token ?? undefined, cache: "no-store" });
    if (!r.ok) return ERRO_HISTORICO;
    const p = camelizarChaves(await r.json()) as PaginaDoHistorico;
    return { interacoes: p.interacoes ?? [], mais: Boolean(p.mais), antes: p.antes ?? null };
  } catch {
    return ERRO_HISTORICO;
  }
}

export async function lerConversaGuardada(token: string | null, conversaId: string): Promise<ConversaGuardada | string> {
  try {
    const r = await apiFetch(`/api/agente/conversas/${encodeURIComponent(conversaId)}`, {
      token: token ?? undefined,
      cache: "no-store",
    });
    if (!r.ok) return ERRO_CONVERSA_GUARDADA;
    return camelizarChaves(await r.json()) as ConversaGuardada;
  } catch {
    return ERRO_CONVERSA_GUARDADA;
  }
}
