// Tela /entrar/escolher (ADR-0024) — quem tem acesso a MAIS DE UMA Câmara escolhe em qual entrar, depois do CPF.
//
// A lista vem do cookie httpOnly `entrar_escolha` que /api/auth/entrar acabou de gravar (5 min), nunca da URL. Cada
// Câmara é um link para /api/auth/login?ente=<id>, que lê o mesmo cookie e leva o usuário já conferido como
// `login_hint` — a pessoa só digita a senha. Sem cookie (venceu, ou alguém abriu esta URL direto) -> volta a /entrar.
// Cada Câmara tem a sua senha (um realm por Câmara): a frase abaixo diz isso. Os links das Câmaras são <a> de
// propósito: /api/auth/login é rota do BFF (navegação de topo até o Keycloak), não página do Next.

import "../entrar.css";
import Link from "next/link";
import { cookies } from "next/headers";
import { redirect } from "next/navigation";
import { SeloPlenario } from "../selo-plenario";
import { COOKIE_ESCOLHA, lerEscolha } from "@/lib/entrada-cpf";

export default async function PaginaEscolherCamara() {
  const escolha = lerEscolha((await cookies()).get(COOKIE_ESCOLHA)?.value);
  if (!escolha) redirect("/entrar?erro=escolha");

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
            <h1>Em qual Câmara?</h1>
            <p>Você tem acesso a mais de uma. Cada Câmara tem a sua senha.</p>
          </div>
          <ul className="entrar-camaras">
            {escolha.casas.map((c) => (
              <li key={c.enteId}>
                <a className="entrar-camara" href={`/api/auth/login?ente=${encodeURIComponent(c.enteId)}`}>
                  <SeloPlenario tamanho={28} />
                  <span>{c.nome}</span>
                </a>
              </li>
            ))}
          </ul>
          <p className="entrar-trocar">
            <Link href="/entrar">Entrar com outro CPF</Link>
          </p>
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
