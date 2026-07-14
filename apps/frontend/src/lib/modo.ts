// Modo de operação da auth do FE. "Real" = produção: a sessão é o cookie opaco httpOnly (BFF/PKCE); o
// navegador não tem token e a authz real é server-side (o 401/403 do backend é o portão). "Dev" =
// não-produção: ?token=/NEXT_PUBLIC_DEV_TOKEN carrega claims e vira Authorization: Bearer.
export const modoReal = (): boolean => process.env.NODE_ENV === "production";

// Um hook só deve abortar por "falta de credencial" no modo DEV sem token. No modo real o token é sempre
// null (o cookie rideia via apiFetch) e o backend decide auth — então NÃO se aborta. Retorna true só
// quando não há como autenticar do lado do cliente.
export const semCredencial = (token: string | null | undefined): boolean => !token && !modoReal();
