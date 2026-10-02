// ADR-0018 (fatia 2, Eixo 4.4 a + c) — o portal de uma Câmara que deixou de usar O Plenário. Os dados dela foram
// apagados e o backend responde 410 em toda rota dela; quem chega por um link antigo merece saber o que houve e para
// onde foram os documentos públicos (se a Câmara informou). Microcopy sóbria: a data e o caminho, sem o motivo.
// Server Component (sem estado), usado pelo layout de /portal/casa/[ente] — cobre todas as páginas da Câmara.

import type { ResolucaoCasa } from "@/lib/portal-api";

type Encerrada = Extract<ResolucaoCasa, { estado: "encerrada" }>;

const DATA_POR_EXTENSO = new Intl.DateTimeFormat("pt-BR", {
  day: "numeric",
  month: "long",
  year: "numeric",
  timeZone: "America/Fortaleza",
});

export function dataPorExtenso(iso: string | null): string | null {
  if (!iso) return null;
  const d = new Date(iso);
  return Number.isNaN(d.getTime()) ? null : DATA_POR_EXTENSO.format(d);
}

export function CasaEncerrada({ casa }: { casa: Encerrada }) {
  const desde = dataPorExtenso(casa.encerradaEm);
  return (
    <main id="conteudo" className="envelope casa-encerrada">
      <div className="em-breve" role="status">
        <p className="em-breve-titulo">
          {casa.nome ? `${casa.nome} não usa mais O Plenário` : "Esta Câmara não usa mais O Plenário"}
        </p>
        <p className="em-breve-motivo">
          {desde ? `Desde ${desde}, ` : ""}
          {desde ? "o portal desta Câmara não é mais publicado aqui." : "O portal desta Câmara não é mais publicado aqui."}
        </p>
        {casa.destinoAcervoUrl ? (
          <p className="em-breve-motivo">
            As leis, atas, votações e demais documentos públicos estão em{" "}
            <a href={casa.destinoAcervoUrl} rel="noopener noreferrer">{casa.destinoAcervoUrl}</a>.
          </p>
        ) : (
          <p className="em-breve-motivo">
            Para consultar as leis, atas, votações e demais documentos públicos, procure diretamente a Câmara.
          </p>
        )}
      </div>
    </main>
  );
}
