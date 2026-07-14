// Tela /entrar — placeholder sem `ente` na URL (Task 12, Onda D Slice 2 — telas de login PKCE). Alvo do
// middleware (T11, src/middleware.ts) quando falta o cookie `sessao` numa rota protegida: o middleware
// não conhece o tenant ali (só o link específico /entrar/<ente>, que a Câmara envia ao servidor/
// vereador, sabe). Por isso esta tela NUNCA oferece um botão de login genérico — não há qual Keycloak
// (qual realm) chamar sem um `ente` resolvido. Só orienta a buscar a URL certa.
//
// Atalho dev-token (mesmo guard de next.config.ts/middleware.ts: NODE_ENV !== "production"): é uma NOTA
// informativa, não um link funcional — o dev-token se anexa como `?token=` na URL da PÁGINA PROTEGIDA que
// se quer acessar (ver src/lib/auth.tsx), não em /entrar.
//
// `?erro=login` (fast-follow, mesma sessão de Task 12): o login handler (app/api/auth/login/route.ts)
// falha fechado redirecionando pra CÁ quando a descoberta do tenant dá 404/rede/erro — sem isso, quem
// chegava aqui via login falho via a MESMA cópia genérica de sempre, sem indicação de que algo deu
// errado. `mensagemErroEntrada` (lib/entrar-erro.ts) deriva a mensagem; aqui só decide renderizar.
// Server Component: `searchParams` é Promise (Next 16 App Router) — mesmo padrão de entrar/[ente]/page.tsx.

import "./entrar.css";
import { SeloPlenario } from "./selo-plenario";
import { mensagemErroEntrada } from "@/lib/entrar-erro";

export default async function PaginaEntrarPlaceholder({
  searchParams,
}: {
  searchParams: Promise<{ erro?: string }>;
}) {
  const { erro } = await searchParams;
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

          {mensagemErro && (
            <div className="entrar-erro" role="alert">
              {mensagemErro}
            </div>
          )}

          <div className="entrar-titulo">
            <h1>Acesse pela URL da sua Câmara</h1>
            <p>
              O login é específico de cada Câmara — não existe uma porta de entrada única. Use o link
              que a sua Câmara enviou (algo como <code>oplenario.app/entrar/sua-camara</code>).
            </p>
          </div>

          {process.env.NODE_ENV !== "production" && (
            <p className="entrar-dev-nota">
              Modo de desenvolvimento: para pular o login, adicione <code>?token=…</code> (claims em
              JSON) na URL da página protegida que deseja acessar — não nesta tela.
            </p>
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
