// Rota pública /portal/casa/{ente}/participar — "Entrar para participar" (design entrar-govbr.html, ADR-0015).
// Server Component: a descoberta (GET /auth/descoberta/:ente) diz se a Casa existe e se o realm dela tem o broker
// gov.br; o botão leva ao BFF de login com `via=govbr`, que manda o Keycloak da Casa direto ao gov.br. Depois do
// 1o login o cidadão cai na própria área (/acompanhamentos) ou no destino que pediu (`redirect`).

import "../../../../participar.css";
import { BarraInstitucional } from "../../../../barra-institucional";
import { RodapeInstitucional } from "../../../../rodape-institucional";
import { ParticiparCartao } from "../../../../participar-cartao";
import { derivarVistaParticipar, hrefEntrarComGovbr } from "@/lib/participar-vista";
import type { RespostaDescoberta } from "@/lib/entrar-vista";

const backend = process.env.BACKEND_URL ?? "http://localhost:8888";

async function buscarDescoberta(ente: string): Promise<{ status: number; corpo: RespostaDescoberta | null }> {
  try {
    const r = await fetch(`${backend}/auth/descoberta/${encodeURIComponent(ente)}`, { cache: "no-store" });
    if (!r.ok) return { status: r.status, corpo: null };
    return { status: r.status, corpo: (await r.json()) as RespostaDescoberta };
  } catch {
    return { status: 0, corpo: null };
  }
}

export default async function PaginaParticipar({
  params,
  searchParams,
}: {
  params: Promise<{ ente: string }>;
  searchParams: Promise<{ redirect?: string; erro?: string }>;
}) {
  const { ente } = await params;
  const { redirect, erro } = await searchParams;
  const vista = derivarVistaParticipar(await buscarDescoberta(ente), erro);

  if (vista.estado === "nao-encontrada" || vista.estado === "erro") {
    return (
      <main id="conteudo" className="envelope" style={{ maxWidth: 640, padding: "4rem 1.5rem" }}>
        <div className="em-breve" role="status">
          <p className="em-breve-titulo">
            {vista.estado === "nao-encontrada" ? "Câmara não encontrada" : "Não foi possível abrir esta página"}
          </p>
          <p className="em-breve-motivo">
            {vista.estado === "nao-encontrada"
              ? "Não existe uma Câmara publicada neste endereço — confira o endereço que a sua Câmara divulgou."
              : "Tente de novo em instantes. O estado da plataforma fica em "}
            {vista.estado === "erro" && <a href="/status">status</a>}
          </p>
        </div>
      </main>
    );
  }

  const nomeCasa = vista.nome ?? "Câmara";
  return (
    <>
      <a className="pular" href="#conteudo">
        Pular para o conteúdo
      </a>
      <BarraInstitucional ente={ente} nomeCasa={nomeCasa} />
      <main id="conteudo" className="envelope pt-wrap">
        <ParticiparCartao vista={vista} ente={ente} hrefEntrar={hrefEntrarComGovbr(ente, redirect)} />
      </main>
      <RodapeInstitucional nomeCasa={nomeCasa} />
    </>
  );
}
