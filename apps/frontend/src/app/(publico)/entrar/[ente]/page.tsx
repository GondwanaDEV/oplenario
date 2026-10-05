// Tela /entrar/[ente] — o link de entrada que a Câmara divulga. Valida a Câmara via descoberta
// (GET ${BACKEND_URL}/auth/descoberta/:ente) e, se ela existir, pede o CPF (ADR-0025): o formulário posta para
// /api/auth/entrar com o `ente` escondido, e a pessoa cai direto na senha DESTA Câmara (mesmo que tenha acesso a
// outras). CPF sem acesso a esta Câmara volta aqui com `?erro=sem-acesso-nesta`.
//
// Fail-closed: 404 (`nao-encontrada`) e qualquer outra falha (400 uuid malformado, rede, corpo
// malformado -> `erro`) NUNCA mostram o formulário — nunca oferecemos login para uma Câmara que não
// confirmamos existir.
//
// Server Component: o fetch de descoberta roda no SERVIDOR, direto ao backend — mesma convenção de
// lib/portal-api.ts:buscarNomeCasa (BACKEND_URL, SEM prefixo /api; o prefixo /api só existe para fetches
// client-side, que passam pelo rewrite same-origin de next.config.ts). `redirect` (se presente na URL)
// vai escondido no formulário — quem revalida origem é o próprio BFF (pedidoDeRedirect).

import "../entrar.css";
import { SeloPlenario } from "../selo-plenario";
import { FormularioCpf } from "../formulario-cpf";
import { mensagemErroEntrada } from "@/lib/entrar-erro";
import { derivarVistaEntrada, type RespostaDescoberta } from "@/lib/entrar-vista";

const backend = process.env.BACKEND_URL ?? "http://localhost:8888";

async function buscarDescoberta(ente: string): Promise<{ status: number; corpo: RespostaDescoberta | null }> {
  try {
    const r = await fetch(`${backend}/auth/descoberta/${encodeURIComponent(ente)}`, { cache: "no-store" });
    // ADR-0018 (fatia 2): a Câmara encerrada responde 410 com o nome e o destino do acervo
    if (r.status === 410) return { status: 410, corpo: (await r.json().catch(() => null)) as RespostaDescoberta | null };
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
  searchParams: Promise<{ redirect?: string; erro?: string }>;
}) {
  const { ente } = await params;
  const { redirect, erro } = await searchParams;
  const resultado = await buscarDescoberta(ente);
  const vista = derivarVistaEntrada(resultado);
  const mensagemErro = mensagemErroEntrada(erro);

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
                <p>Servidores e vereadores da Câmara.</p>
              </div>
              {mensagemErro && (
                <div className="entrar-erro" role="alert">
                  {mensagemErro}
                </div>
              )}
              <FormularioCpf ente={vista.enteId ?? ente} redirect={redirect} />
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

          {vista.estado === "encerrada" && (
            <div role="status">
              <div className="entrar-titulo">
                <h1>{vista.nome ? `${vista.nome} não usa mais O Plenário` : "Esta Câmara não usa mais O Plenário"}</h1>
                <p>
                  O acesso por aqui foi encerrado.{" "}
                  {vista.destinoAcervoUrl ? (
                    <>Os documentos públicos da Câmara estão em <a href={vista.destinoAcervoUrl} rel="noopener noreferrer">{vista.destinoAcervoUrl}</a>.</>
                  ) : (
                    <>Para consultar os documentos públicos, procure diretamente a Câmara.</>
                  )}
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
