"use client";

// O assistente da Casa (Faixa B / B.3): POST /api/agente/perguntas devolve a conversa em SSE. A tela guarda as
// perguntas desta visita em ordem (nada vai para o armazenamento do navegador: é consulta, não histórico) e uma
// pergunta de cada vez — enquanto o assistente responde, o campo espera.

import { useCallback, useState } from "react";
import { apiFetch } from "./api-fetch";
import { lerConversa, mensagemDeErroAssistente, type Conversa } from "./assistente-vista";

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
): Promise<Conversa | string> {
  try {
    const r = await apiFetch("/api/agente/perguntas", {
      token: token ?? undefined,
      method: "POST",
      headers: { "Content-Type": "application/json", Accept: "text/event-stream" },
      body: JSON.stringify(publico ? { pergunta, publico } : { pergunta }),
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
  const ocupado = turnos.some((t) => t.fase === "respondendo");

  const perguntar = useCallback(
    async (pergunta: string) => {
      setTurnos((ts) => [...ts, { pergunta, fase: "respondendo" }]);
      const r = await perguntarAoAssistente(token, pergunta, publico);
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

  return { turnos, ocupado, perguntar };
}
