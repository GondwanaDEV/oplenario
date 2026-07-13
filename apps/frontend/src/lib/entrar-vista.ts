// View-model puro de /entrar/[ente] (Task 12, Onda D Slice 2 — telas de login PKCE). Deriva o estado de
// exibição da tela a partir do resultado bruto de GET /auth/descoberta/:ente (backend T3,
// phase2-shared-decisions.md §3).
//
// CORREÇÃO DE INTERFACE sobre o brief: o brief prosa dizia que a tela "mostra o nome da câmara (via
// descoberta)", mas a descoberta real do T3 devolve só {ente-id, realm, base-url, client-id} — SEM nome.
// Aqui o campo `nome`/`nome-camara` é lido de forma OPCIONAL e forward-compatible: se o backend um dia
// passar a incluir um desses campos, a tela já mostra; sem eles, `nome` fica `null` e o caller (page.tsx)
// cai num heading neutro. Nunca exigimos o campo.
//
// Diferente do fail-closed de api/auth/login/route.ts (que colapsa 400/404/rede no MESMO redirect — não
// quer vazar se um tenant existe, por segurança), esta tela PRECISA diferenciar: validar o `ente` da URL
// e dizer ao usuário se a Câmara existe é a própria função dela. Por isso 404 vira um estado dedicado
// ("nao-encontrada", amigável) e qualquer outra falha (400 uuid malformado, rede, corpo malformado) vira
// "erro" genérico — em NENHUM dos dois o botão "Entrar" aparece (fail-closed: nunca oferecer login para
// uma Câmara que não confirmamos existir).

export type EstadoEntrada = "ok" | "nao-encontrada" | "erro";

export interface RespostaDescoberta {
  "ente-id"?: unknown;
  nome?: unknown;
  "nome-camara"?: unknown;
  [chave: string]: unknown;
}

export interface ResultadoDescoberta {
  status: number;
  corpo: RespostaDescoberta | null;
}

export interface VistaEntrada {
  estado: EstadoEntrada;
  enteId: string | null;
  nome: string | null;
}

export function derivarVistaEntrada(resultado: ResultadoDescoberta): VistaEntrada {
  if (resultado.status === 404) {
    return { estado: "nao-encontrada", enteId: null, nome: null };
  }
  if (resultado.status !== 200 || !resultado.corpo) {
    return { estado: "erro", enteId: null, nome: null };
  }

  const enteIdBruto = resultado.corpo["ente-id"];
  const enteId = typeof enteIdBruto === "string" ? enteIdBruto : null;

  const nomeBruto = resultado.corpo.nome ?? resultado.corpo["nome-camara"];
  const nome = typeof nomeBruto === "string" ? nomeBruto : null;

  return { estado: "ok", enteId, nome };
}
