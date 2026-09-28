// Rota pública do Portal do Cidadão — o livro de atas da Casa (Onda E, `livro-atas`): GET /portal/casa/{ente}/atas.
// Server Component fino (mesmo split das demais páginas do portal): resolve o nome da Casa no servidor e entrega a
// ata pedida na URL ao componente cliente, que faz o fetch. Só atas de sessões públicas: o backend filtra; a de
// sessão secreta simplesmente não existe aqui.

import { BarraInstitucional } from "../../../../barra-institucional";
import { RodapeInstitucional } from "../../../../rodape-institucional";
import { LivroAtas } from "@/app/livro-atas";
import { buscarNomeCasa } from "@/lib/portal-api";

export default async function PaginaLivroAtasPublico({
  params,
  searchParams,
}: {
  params: Promise<{ ente: string }>;
  searchParams: Promise<{ sessao?: string; versao?: string }>;
}) {
  const { ente } = await params;
  const { sessao, versao } = await searchParams;
  const nomeCasa = (await buscarNomeCasa(ente))?.nomeOficial ?? ente;
  const v = Number(versao);
  return (
    <>
      <a className="pular" href="#conteudo">
        Pular para o conteúdo
      </a>
      <BarraInstitucional ente={ente} nomeCasa={nomeCasa} />
      <main id="conteudo" className="envelope">
        <LivroAtas
          fonte={{ tipo: "publico", ente }}
          sessao={sessao ?? null}
          versao={Number.isInteger(v) && v > 0 ? v : null}
          nomeCasa={nomeCasa}
        />
      </main>
      <RodapeInstitucional nomeCasa={nomeCasa} />
    </>
  );
}

