// Tela /entrar/[ente] (Task 12, Onda D Slice 2 — telas de login PKCE). Valida a Câmara via descoberta
// (GET ${BACKEND_URL}/auth/descoberta/:ente, backend T3) e, se ela existir, oferece o botão real
// "Entrar" -> GET /api/auth/login?ente=<uuid> (T8): uma navegação de topo (<a>, NUNCA um fetch) — o
// browser precisa seguir o 307 até o Keycloak do tenant, o que um fetch client-side não faz.
//
// CORREÇÃO DE INTERFACE sobre o brief: o brief prosa dizia "mostra o nome da câmara (via descoberta)",
// mas a descoberta real do T3 devolve só {ente-id, realm, base-url, client-id} — SEM nome. O `nome` aqui
// é OPCIONAL e forward-compatible (ver entrar-vista.ts): se ausente, cai num heading neutro
// ("Entrar na sua Câmara"), nunca exigido.
//
// Fail-closed: 404 (`nao-encontrada`) e qualquer outra falha (400 uuid malformado, rede, corpo
// malformado -> `erro`) NUNCA mostram o botão "Entrar" — nunca oferecemos login para uma Câmara que não
// confirmamos existir.
//
// Server Component: o fetch de descoberta roda no SERVIDOR, direto ao backend — mesma convenção de
// lib/portal-api.ts:buscarNomeCasa (BACKEND_URL, SEM prefixo /api; o prefixo /api só existe para fetches
// client-side, que passam pelo rewrite same-origin de next.config.ts). `redirect` (se presente na URL,
// posto pelo middleware T11 ao gatear uma rota protegida) é repassado cru ao login — quem revalida
// origem/allowlist é o próprio route handler de login (resolveRedirectPath), aqui só encaminhamos.

import "../entrar.css";
import { SeloPlenario } from "../selo-plenario";
import { derivarVistaEntrada, type RespostaDescoberta } from "@/lib/entrar-vista";

const backend = process.env.BACKEND_URL ?? "http://localhost:8888";

async function buscarDescoberta(ente: string): Promise<{ status: number; corpo: RespostaDescoberta | null }> {
  try {
    const r = await fetch(`${backend}/auth/descoberta/${encodeURIComponent(ente)}`, { cache: "no-store" });
    if (!r.ok) return { status: r.status, corpo: null };
    return { status: r.status, corpo: (await r.json()) as RespostaDescoberta };
  } catch {
    // backend inacessível/timeout: cai no MESMO bucket de erro genérico que um 400 (fail-closed) —
    // nunca deixamos a exceção estourar dentro do Server Component (derrubaria a página inteira).
    return { status: 0, corpo: null };
  }
}

export default async function PaginaEntrarComEnte({
  params,
  searchParams,
}: {
  params: Promise<{ ente: string }>;
  searchParams: Promise<{ redirect?: string }>;
}) {
  const { ente } = await params;
  const { redirect } = await searchParams;
  const resultado = await buscarDescoberta(ente);
  const vista = derivarVistaEntrada(resultado);

  const loginHref =
    `/api/auth/login?ente=${encodeURIComponent(ente)}` +
    (redirect ? `&redirect=${encodeURIComponent(redirect)}` : "");

  return (
    <div className="entrar-pagina">
      <main className="entrar-cartao">
        <div className="entrar-faixa" aria-hidden="true">
          <span className="a" />
          <span className="b" />
          <span className="c" />
          <span className="d" />
        </div>
        <div className="entrar-corpo">
          <SeloPlenario tamanho={44} className="entrar-selo" />

          {vista.estado === "ok" && (
            <>
              <div className="entrar-titulo">
                <h1>{vista.nome ? `Entrar em ${vista.nome}` : "Entrar na sua Câmara"}</h1>
                <p>Você será redirecionado para o login oficial da sua Câmara.</p>
              </div>
              <a className="btn btn-primaria entrar-acao" href={loginHref}>
                Entrar
              </a>
            </>
          )}

          {vista.estado === "nao-encontrada" && (
            <div role="alert">
              <div className="entrar-titulo">
                <h1>Câmara não encontrada</h1>
                <p>
                  Este link não corresponde a nenhuma Câmara cadastrada. Confira o endereço recebido da
                  sua Câmara e tente novamente.
                </p>
              </div>
            </div>
          )}

          {vista.estado === "erro" && (
            <div role="alert">
              <div className="entrar-titulo">
                <h1>Não foi possível verificar sua Câmara</h1>
                <p>
                  Tente novamente em instantes. Se o problema continuar, contate o suporte da sua
                  Câmara.
                </p>
              </div>
            </div>
          )}
        </div>
        <div className="entrar-rodape">
          <span className="plat">
            <SeloPlenario tamanho={18} className="sig" />
            Plataforma <b>O Plenário</b>
          </span>
        </div>
      </main>
    </div>
  );
}
