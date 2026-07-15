// Modo de operação da auth do FE — FONTE ÚNICA (modo.ts é o único lugar que lê a env de modo; auth.tsx e
// os hooks consomem daqui). "Real" = a sessão é o cookie opaco httpOnly (BFF/PKCE); o navegador não tem
// token e a authz real é server-side (o 401/403 do backend é o portão). "Dev" = ?token=/NEXT_PUBLIC_DEV_TOKEN
// carrega claims e vira Authorization: Bearer (só vale contra o `idp-dev` do backend, que não verifica
// assinatura).
//
// NEXT_PUBLIC_APP_ENV espelha o APP_ENV do backend (mesmos valores, mesma regra: só "dev"/"test" ligam o
// modo dev; ausente/vazio/desconhecido ⇒ modo real). Duas razões p/ o nome e a forma:
//   - `NEXT_PUBLIC_` é o único prefixo que o Next entrega ao bundle do client, e é onde `modoReal()` roda;
//   - o sufixo `APP_ENV` é literal de propósito — as duas pontas têm de andar juntas, e o docker-compose
//     as alimenta da MESMA variável (`${OPLENARIO_APP_ENV:-dev}`). Subir com OPLENARIO_APP_ENV=production
//     põe backend E frontend em modo real de uma vez; não há como uma ponta acordar sem a outra.
//
// Por que NÃO é mais `NODE_ENV === "production"`: o NODE_ENV é do BUILD, não do DEPLOY. Amarrado a ele, o
// caminho de produção do FE era INEXERCITÁVEL em `next dev` (NODE_ENV="development" ⇒ modoReal()=false ⇒
// semCredencial(null)=true ⇒ todo hook abortava antes de buscar, mesmo com cookie de sessão Keycloak
// válido e backend em APP_ENV=production). O modo de auth é config de deploy, e o default é o SEGURO:
// esquecer a env deixa o FE em modo real (que no pior caso mostra 401 do backend), nunca em modo dev.
//
// Ressalva conhecida (build-time): o Next inlina `process.env.NEXT_PUBLIC_*` no bundle do client no
// momento do BUILD. No container de dev (`target: dev`, `next dev` compila com o env do runtime) isto lê o
// que o compose passou; num build de produção o valor precisa estar presente no `next build` — e se não
// estiver, o default cai em modo real, que é o lado seguro.
const appEnv = (): string => process.env.NEXT_PUBLIC_APP_ENV ?? "";

// Whitelist (não blacklist): espelha `sistema/idp-para` no backend. Só estes dois valores ligam o dev.
export const modoDev = (): boolean => appEnv() === "dev" || appEnv() === "test";

export const modoReal = (): boolean => !modoDev();

// Um hook só deve abortar por "falta de credencial" no modo DEV sem token. No modo real o token é sempre
// null (o cookie rideia via apiFetch) e o backend decide auth — então NÃO se aborta. Retorna true só
// quando não há como autenticar do lado do cliente.
export const semCredencial = (token: string | null | undefined): boolean => !token && !modoReal();
