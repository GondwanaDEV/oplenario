// Cliente de leitura pública do Portal do Cidadão (Task 0.3, Fatia A2.0). Espelha `buscarOuNull` de
// use-mesa.ts, mas para a superfície SEM auth (nenhuma rota autenticada nesta fatia): sem header
// Authorization, `!ok` ou throw sempre viram `null` — "degradação por seção" (Global Constraints do
// plano): uma seção com fetch falho/vazio degrada sozinha, nunca derruba a página inteira.

import { camelizarChaves } from "./boundary";

export async function buscarPublico<T>(caminho: string): Promise<T | null> {
  try {
    const r = await fetch(`/api/portal/casa/${caminho}`, { cache: "no-store" });
    if (!r.ok) return null;
    return camelizarChaves(await r.json()) as T;
  } catch {
    return null;
  }
}
