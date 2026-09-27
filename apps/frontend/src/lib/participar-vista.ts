// View-model puro da tela "Entrar para participar" (/portal/casa/[ente]/participar — design entrar-govbr.html,
// ADR-0015). Deriva o que mostrar a partir de GET /auth/descoberta/:ente: o botão "Entrar com gov.br" só aparece
// quando a Casa existe E o realm dela tem o broker gov.br. Sem broker, a tela diz honestamente que a entrada ainda
// não está disponível — consultar continua livre.

import type { ResultadoDescoberta } from "./entrar-vista";

export type EstadoParticipar = "pronto" | "indisponivel" | "nao-encontrada" | "erro";

export interface VistaParticipar {
  estado: EstadoParticipar;
  nome: string | null;
}

function texto(v: unknown): string | null {
  return typeof v === "string" && v.trim() !== "" ? v : null;
}

export function derivarVistaParticipar(resultado: ResultadoDescoberta, erro: string | undefined): VistaParticipar {
  if (resultado.status === 404) return { estado: "nao-encontrada", nome: null };
  const c = resultado.corpo;
  if (resultado.status !== 200 || !c) return { estado: "erro", nome: null };
  const nome = texto(c["nome-oficial"]) ?? texto(c["nome-curto"]);
  // `erro=indisponivel` vem do BFF, que acabou de ver a descoberta sem gov.br: vale o que ele viu.
  if (erro === "indisponivel" || c.govbr !== true) return { estado: "indisponivel", nome };
  return { estado: "pronto", nome };
}

// Navegação de topo (<a>, nunca fetch): o navegador precisa seguir o 307 até o Keycloak da Casa e dali ao gov.br.
// Quem revalida o `redirect` (same-origin) é o próprio BFF de login.
export function hrefEntrarComGovbr(ente: string, redirect: string | undefined): string {
  const base = `/api/auth/login?ente=${encodeURIComponent(ente)}&via=govbr`;
  return redirect ? `${base}&redirect=${encodeURIComponent(redirect)}` : base;
}
