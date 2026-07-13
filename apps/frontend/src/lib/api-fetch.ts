// Boundary único de auth (Onda D Slice 2, Fase 3 — cutover do anexo de token). Toda chamada de dados do FE
// (hooks) passa a fluir por aqui em vez de montar `Authorization: Bearer <token>` na mão em ~14 call sites.
// Dois modos, resolvidos por um único sinal (a presença de `token`):
//   - modo REAL (produção, sem `token`): a sessão é o cookie opaco `sessao` (setado pelo BFF no callback
//     PKCE); ele viaja sozinho em requests same-origin — NÃO inventar Authorization aqui.
//   - modo DEV (`?token=` de bypass do middleware, ver `oplenario-proxima-sessao`): injeta
//     `Authorization: Bearer <token>` — é o que os hooks fazem hoje, preservado só neste ponto único.
// `credentials: 'same-origin'` é forçado nos dois modos (não configurável pelo caller): é o que faz o
// cookie `sessao` viajar no modo real, e não custa nada no modo dev. Documentado aqui por decisão, não
// esquecimento — se algum dia precisar de cross-origin, é uma mudança deliberada deste arquivo, não um
// `init.credentials` disperso pelos hooks.

export type ApiFetchInit = Omit<RequestInit, "credentials"> & { token?: string };

export async function apiFetch(
  path: string,
  init: ApiFetchInit = {},
  opts?: { fetchImpl?: typeof fetch },
): Promise<Response> {
  const { token, headers: callerHeaders, ...rest } = init;
  const headers = new Headers(callerHeaders);
  if (token) headers.set("Authorization", `Bearer ${token}`);

  const f = opts?.fetchImpl ?? fetch;
  return f(path, { ...rest, headers, credentials: "same-origin" });
}
