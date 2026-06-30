// Route Handler do SSE do plenário. Tem precedência sobre o rewrite afterFiles do next.config (que segue
// cuidando dos demais /api/*) só p/ este path, porque o stream ao vivo exige proxy com stream REAL — ver
// a justificativa de causa-raiz em src/lib/sse-proxy.ts. `force-dynamic` + runtime nodejs garantem que o
// ReadableStream upstream atravesse sem ser bufferizado/otimizado estaticamente.

import { proxiarPlenario } from "@/lib/sse-proxy";

export const dynamic = "force-dynamic";
export const runtime = "nodejs";

export async function GET(req: Request, ctx: { params: Promise<{ id: string }> }) {
  const { id } = await ctx.params;
  return proxiarPlenario(req, id);
}
