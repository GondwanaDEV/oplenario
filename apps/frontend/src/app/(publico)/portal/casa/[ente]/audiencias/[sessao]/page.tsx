// Rota pública /portal/casa/{ente}/audiencias/{sessao} — uma audiência pública (ADR-0021 A2/A5, porte de
// audiencia-publica.html): sobre a audiência, a matéria relacionada, "Quero falar" pelo gov.br e, depois de
// realizada, quem falou e a ata. Server Component fino: o nome da Casa vem do servidor; a sessão cidadã, a leitura e
// a inscrição moram no cliente (PaginaAudiencia).

import { BarraInstitucional } from "@/app/(publico)/barra-institucional";
import { RodapeInstitucional } from "@/app/(publico)/rodape-institucional";
import { PaginaAudiencia } from "@/app/(publico)/audiencias-publicas";
import { buscarNomeCasa } from "@/lib/portal-api";

export default async function PaginaAudienciaPublica({ params }: { params: Promise<{ ente: string; sessao: string }> }) {
  const { ente, sessao } = await params;
  const casa = await buscarNomeCasa(ente);
  const nomeCasa = casa?.nomeOficial ?? ente;
  return (
    <>
      <a className="pular" href="#conteudo">
        Pular para o conteúdo
      </a>
      {/* ADR-0018: na Casa suspensa a inscrição para falar não é aceita (423) — a faixa avisa antes */}
      <BarraInstitucional ente={ente} nomeCasa={nomeCasa} acessoRestritoDesde={casa?.acessoRestritoDesde} />
      <main id="conteudo" className="envelope">
        <PaginaAudiencia ente={ente} sessaoId={sessao} />
      </main>
      <RodapeInstitucional nomeCasa={nomeCasa} />
    </>
  );
}
