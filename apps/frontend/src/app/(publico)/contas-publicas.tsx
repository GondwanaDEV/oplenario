"use client";

// As CONTAS no portal do cidadão (ADR-0021 Parte B): por exercício, o parecer prévio do Tribunal de Contas, a situação
// ou o resultado do julgamento em palavras e os documentos DO TRIBUNAL para baixar (parecer, relatório, decisão — o
// servidor só entrega esses). As contas de governo do Prefeito, que a Câmara julga; as de gestão da Câmara, que o
// Tribunal julga e a Câmara só acompanha. Nenhum valor da prestação é reproduzido: está nos documentos.

import { useEffect, useState } from "react";
import { buscarPublico } from "@/lib/portal-api";
import { dataLegivel, ordenarPrestacoes, rotuloDocumento, rotuloParecer, situacaoPublica } from "@/lib/contas-vista";
import { ROTAS_CONTAS, doFio, formaValida, type ListaPrestacoesPublicasOut, type PrestacaoPublica } from "@/lib/contrato-contas";
import "./contas-publicas.css";

type Carga = { fase: "carregando" } | { fase: "pronto"; dado: PrestacaoPublica[] } | { fase: "erro" };

function useContasPublicas(ente: string): Carga {
  const [r, setR] = useState<{ de: string | null; carga: Carga }>({ de: null, carga: { fase: "carregando" } });
  useEffect(() => {
    let vivo = true;
    (async () => {
      const bruto = await buscarPublico<unknown>(...ROTAS_CONTAS.portalSegmentos(ente));
      const d = bruto === null ? null : doFio.publica(bruto);
      if (!vivo) return;
      setR({ de: ente, carga: d && formaValida.publica(d) ? { fase: "pronto", dado: (d as ListaPrestacoesPublicasOut).prestacoes } : { fase: "erro" } });
    })();
    return () => {
      vivo = false;
    };
  }, [ente]);
  return r.de === ente ? r.carga : { fase: "carregando" };
}

export function ContasPublicas({ ente }: { ente: string }) {
  const carga = useContasPublicas(ente);
  const todas = carga.fase === "pronto" ? ordenarPrestacoes(carga.dado) : [];
  const governo = todas.filter((p) => p.tipo === "governo_prefeito");
  const mesa = todas.filter((p) => p.tipo === "gestao_camara");
  return (
    <div className="contas-publicas">
      <div className="pg-cab">
        <span className="rotulo-secao">Controle externo</span>
        <h1>Contas do Prefeito e da Câmara</h1>
        <p className="sub">
          A Câmara julga as contas de governo do Prefeito com base no parecer prévio do Tribunal de Contas. O parecer só
          deixa de prevalecer pelo voto de dois terços dos vereadores (Constituição Federal, art. 31, §2º). As contas de
          gestão da Câmara são julgadas pelo próprio Tribunal.
        </p>
        <p className="sub">Os valores e os índices de cada prestação estão nos documentos do Tribunal, que você pode baixar aqui.</p>
      </div>

      {carga.fase === "carregando" && <p className="estado">Carregando…</p>}
      {carga.fase === "erro" && (
        <p className="estado" role="alert">
          Não foi possível carregar as contas agora. Tente novamente em instantes.
        </p>
      )}
      {carga.fase === "pronto" && (
        <>
          <Grupo id="cp-governo" ente={ente} titulo="Contas de governo do Prefeito" itens={governo} vazio="Nenhuma prestação de contas do Prefeito registrada até agora." />
          <Grupo id="cp-mesa" ente={ente} titulo="Contas de gestão da Câmara" itens={mesa} vazio="Nenhuma prestação de contas da Câmara registrada até agora." />
        </>
      )}
    </div>
  );
}

function Grupo({ id, ente, titulo, itens, vazio }: { id: string; ente: string; titulo: string; itens: PrestacaoPublica[]; vazio: string }) {
  return (
    <section aria-labelledby={id}>
      <h2 className="secao-tit" id={id}>
        {titulo}
      </h2>
      {itens.length === 0 ? (
        <p className="estado">{vazio}</p>
      ) : (
        <ul className="cp-lista" aria-label={titulo}>
          {itens.map((p) => (
            <li key={p.id}>
              <Prestacao ente={ente} p={p} />
            </li>
          ))}
        </ul>
      )}
    </section>
  );
}

function Prestacao({ ente, p }: { ente: string; p: PrestacaoPublica }) {
  return (
    <article className="cp-item" aria-label={`Exercício ${p.exercicio}`}>
      <h3>Exercício {p.exercicio}</h3>
      <dl className="cp-dl">
        <dt>Responsável</dt>
        <dd>{p.responsavel}</dd>
        {p.parecerPrevio && (
          <>
            <dt>Parecer prévio do Tribunal</dt>
            <dd>{rotuloParecer(p.parecerPrevio)}</dd>
          </>
        )}
        <dt>Situação</dt>
        <dd>{situacaoPublica(p)}</dd>
        {p.julgadaEm && (
          <>
            <dt>Julgadas em</dt>
            <dd>{dataLegivel(p.julgadaEm)}</dd>
          </>
        )}
        {p.proposicaoRotulo && (
          <>
            <dt>Decreto legislativo</dt>
            <dd>{p.proposicaoRotulo}</dd>
          </>
        )}
      </dl>
      {p.fraseResultado && <p className="cp-frase">{p.fraseResultado}</p>}
      {p.documentos.length > 0 ? (
        <ul className="cp-docs" aria-label={`Documentos do Tribunal — exercício ${p.exercicio}`}>
          {p.documentos.map((d) => (
            <li key={d.id}>
              <a href={ROTAS_CONTAS.portalDocumento(ente, p.id, d.id)} download={d.nome}>
                Baixar: {rotuloDocumento(d.tipo)}
              </a>
              <span className="cp-doc-nome">{d.nome}</span>
            </li>
          ))}
        </ul>
      ) : (
        <p className="estado">Os documentos do Tribunal ainda não foram publicados.</p>
      )}
    </article>
  );
}
