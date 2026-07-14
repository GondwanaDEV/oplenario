// View-model puro de /entrar/[ente] (Task 12, Onda D Slice 2 — telas de login PKCE). Deriva o estado de
// exibição da tela a partir do resultado bruto de GET /auth/descoberta/:ente (backend T3,
// phase2-shared-decisions.md §3).
//
// A descoberta agora devolve também o nome público da Câmara (`nome-oficial`/`nome-curto`, Slice 2b) —
// deixou de ser o caso "sem nome". Preferimos o `nome-curto` (mais curto p/ o heading), caindo no
// `nome-oficial` quando só ele vier. Os aliases legados `nome`/`nome-camara` continuam suportados como
// fallback defensivo (forward-compat com formas antigas do backend). O caso sem nenhum campo de nome
// segue suportado: `nome` fica `null` e o caller (page.tsx) cai num heading neutro. Nunca exigimos o campo.
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
  "nome-oficial"?: unknown;
  "nome-curto"?: unknown;
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

  const nomeBruto =
    resultado.corpo["nome-curto"] ?? resultado.corpo["nome-oficial"] ??
    resultado.corpo.nome ?? resultado.corpo["nome-camara"];
  const nome = typeof nomeBruto === "string" ? nomeBruto : null;

  return { estado: "ok", enteId, nome };
}
