// Boundary kebab->camel (extraído de use-mesa.ts, Task 0.1, Fatia A2.0 — Portal do Cidadão). jsonista
// (backend, apps/backend/src/oplenario/http.clj:json-resposta) serializa keywords Clojure VERBATIM —
// :por-estado vira a chave JSON literal "por-estado", nunca camelCase. Todo código downstream (view-models,
// componentes) é escrito contra nomes camelCase. Sem esta transformação, o acesso por propriedade camelCase
// resolve pra `undefined` contra um payload real do backend (bug B7b). Aplicada UMA VEZ em cada boundary de
// fetch — dependency-free, mesmo espírito "zero-dep, hand-rolled" do codegen.

export function paraCamel(chave: string): string {
  return chave.replace(/-+([a-z0-9])/g, (_, c: string) => c.toUpperCase());
}

export function camelizarChaves(valor: unknown): unknown {
  if (Array.isArray(valor)) return valor.map(camelizarChaves);
  if (valor !== null && typeof valor === "object") {
    return Object.fromEntries(
      Object.entries(valor as Record<string, unknown>).map(([k, v]) => [paraCamel(k), camelizarChaves(v)]),
    );
  }
  return valor;
}
