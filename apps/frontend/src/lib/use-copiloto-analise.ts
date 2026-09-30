"use client";

// O copiloto do relator (ADR-0019, Eixo 5): POST /api/legislativo/pareceres/:id/copiloto (a secretaria, no editor dela)
// ou /api/meu/pareceres/:id/copiloto (o vereador-relator). Estado próprio, fora do salvar do editor: enquanto o
// assistente monta, o formulário continua utilizável. Nada é gravado — o rascunho só vai ao campo se a pessoa pedir.

import { apiFetch } from "./api-fetch";
import { camelizarChaves } from "./boundary";
import type { CopilotoAnaliseOut } from "./contrato-legislativo.gen";
import { lerResultadoAnalise, mensagemDeErroAnalise, type ResultadoAnalise } from "./copiloto-analise-vista";
import { semCredencial } from "./modo";

export type BordaParecer = "legislativo" | "meu";

export function caminhoDoCopiloto(borda: BordaParecer, id: string): string {
  return `/api/${borda}/pareceres/${encodeURIComponent(id)}/copiloto`;
}

export async function pedirAnalise(token: string | null, borda: BordaParecer, id: string): Promise<ResultadoAnalise> {
  if (semCredencial(token)) return { tipo: "nada", mensagem: mensagemDeErroAnalise(401) };
  try {
    const r = await apiFetch(caminhoDoCopiloto(borda, id), {
      token: token ?? undefined,
      method: "POST",
      cache: "no-store",
    });
    if (!r.ok) return { tipo: "nada", mensagem: mensagemDeErroAnalise(r.status) };
    return lerResultadoAnalise(camelizarChaves(await r.json()) as CopilotoAnaliseOut);
  } catch {
    return { tipo: "nada", mensagem: mensagemDeErroAnalise(0) };
  }
}
