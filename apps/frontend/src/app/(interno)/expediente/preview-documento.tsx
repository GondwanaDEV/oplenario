"use client";

// PreviewDocumento — "o documento em papel" do mockup (expediente.html:432-485), o herói visual da tela: o
// carimbo do Protocolo Geral + timbre + corpo com merge sublinhado em telha. Antes de o documento existir
// (fase de composição, `documento === null`) mostra um estado vazio honesto — nada de papel fabricado com
// dados que ainda não existem (Global Constraint "sem dado falso").
//
// `valoresMerge` = os valores que o PRÓPRIO usuário digitou em `dados` nesta geração (o mesmo mapa que foi
// pro POST) — usado por destacarMerge (expediente-vista.ts) pra sublinhar no `corpo` já renderizado pelo
// servidor os trechos que vieram do merge (heurística honesta: o backend não expõe posição de placeholder,
// então destacamos por correspondência exata de string, não por marcação de servidor).
//
// Timbre: "Câmara Municipal" é o MESMO rótulo estático já usado em topo.tsx (../topo.tsx) — não há resolução
// de nome do ente nesta fatia; endereço/CNPJ do mockup são específicos demais pra inventar sem fonte.

import { destacarMerge, rotularTipoDocumento, textoCarimbo } from "@/lib/expediente-vista";
import type { DocumentoOut } from "@/lib/contrato-legislativo.gen";

export function PreviewDocumento({
  documento,
  valoresMerge,
}: {
  documento: DocumentoOut | null;
  valoresMerge: string[];
}) {
  if (!documento) {
    return (
      <div className="documento-wrap">
        <div className="documento documento-vazio" role="status">
          <p>O documento aparecerá aqui após você escolher um modelo e clicar em &ldquo;Gerar documento&rdquo;.</p>
        </div>
      </div>
    );
  }

  const carimbo = textoCarimbo(documento);
  const reservar = carimbo === "a reservar";
  const segmentos = destacarMerge(documento.corpo, valoresMerge);

  return (
    <div className="documento-wrap">
      <article className="documento" role="document" aria-label="Pré-visualização do documento">
        <div
          className={`carimbo${reservar ? " reservar" : ""}`}
          aria-label={reservar ? "Protocolo Geral a reservar ao protocolar." : `Protocolo Geral nº ${carimbo}.`}
        >
          <span className="titulo">Protocolo Geral</span>
          <span className="num">{carimbo}</span>
        </div>

        <div className="timbre">
          <p className="orgao">Câmara Municipal</p>
          <p className="setor">Secretaria Legislativa</p>
        </div>

        <div className="doc-ref">
          <span className="num-of">{rotularTipoDocumento(documento.tipoDocumento)}</span>
        </div>

        <p className="doc-assunto">
          <b>Assunto:</b> {documento.assunto}.
        </p>

        <p className="corpo">
          {segmentos.map((s, i) =>
            s.merge ? (
              <span className="merge" key={i}>
                {s.texto}
              </span>
            ) : (
              <span key={i}>{s.texto}</span>
            ),
          )}
        </p>
      </article>

      <p className="doc-legenda">
        <span className="amostra">trecho assim</span> = preenchido pelo formulário de preenchimento.
        Confira antes de protocolar.
      </p>
    </div>
  );
}
