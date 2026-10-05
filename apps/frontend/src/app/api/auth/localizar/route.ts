// ADR-0025 — /api/auth/localizar NÃO chega ao backend. Sem esta rota, o proxy de `/api/:path*` (next.config.ts) levaria o
// pedido direto ao `POST /auth/localizar`, com o `X-Forwarded-For` que o cliente escrevesse na frente — e o limite por
// IP contaria o IP inventado. A única porta da entrada pelo CPF é o BFF (`POST /api/auth/entrar`), que escolhe o IP
// certo (`ipDoCliente`).
import { NextResponse } from "next/server";

function naoExiste(): NextResponse {
  return new NextResponse(null, { status: 404 });
}

export const GET = naoExiste;
export const POST = naoExiste;
