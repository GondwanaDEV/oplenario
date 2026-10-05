// Tela /entrar — a porta de entrada de servidores e vereadores (ADR-0025: a entrada pelo CPF).
//
// A pessoa digita o CPF; /api/auth/entrar descobre em quais Câmaras ela tem acesso e a leva direto à tela de senha da
// Câmara dela (o Keycloak com o tema do O Plenário, usuário já preenchido). Antes desta ADR esta tela era um
// placeholder ("acesse pela URL da sua Câmara"): sem o UUID da Câmara não havia como entrar — o `[GAP]` de entrada a
// frio da Onda D, fatia 2. O middleware manda para cá quem abre uma rota protegida sem sessão, com `?redirect=`.
//
// `?erro=` vem do login handler (`login`) ou da entrada pelo CPF; `mensagemErroEntrada` deriva a frase.
// Server Component: `searchParams` é Promise (Next 16 App Router).

import "./entrar.css";
import { SeloPlenario } from "./selo-plenario";
import { FormularioCpf } from "./formulario-cpf";
import { mensagemErroEntrada } from "@/lib/entrar-erro";

export default async function PaginaEntrar({
  searchParams,
}: {
  searchParams: Promise<{ erro?: string; redirect?: string }>;
}) {
  const { erro, redirect } = await searchParams;
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

          <div className="entrar-titulo">
            <h1>Entrar no O Plenário</h1>
            <p>Servidores e vereadores da Câmara.</p>
          </div>

          {mensagemErro && (
            <div className="entrar-erro" role="alert">
              {mensagemErro}
            </div>
          )}

          <FormularioCpf redirect={redirect} />

          <p className="entrar-cidadao">
            É cidadão? Acompanhe e participe pelo portal da sua Câmara, com a conta gov.br.
          </p>

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
