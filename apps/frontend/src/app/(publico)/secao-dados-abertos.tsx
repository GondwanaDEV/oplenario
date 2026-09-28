"use client";

// Os DADOS ABERTOS da Casa no portal (Onda E, `dados-abertos`). Porte de produto/design-system/o-plenario/telas/
// dados-abertos.html: um cartão por dataset, com formato, volume, última atualização, o botão de baixar e o
// dicionário das colunas (o que cada uma significa — sem ele o arquivo não é "legível por máquina" de verdade).
// O que o design tem e o dado não sustenta está em lib/dados-abertos-vista.ts.

import { useEffect, useState } from "react";
import { buscarPublico } from "@/lib/portal-api";
import type { DadosAbertosOut } from "@/lib/contrato-portal.gen";
import { derivarDadosAbertos, FORA, type DatasetVista } from "@/lib/dados-abertos-vista";
import { EmBreve } from "@/lib/em-breve";
import "./dados-abertos.css";

type Estado = { fase: "carregando" } | { fase: "erro" } | { fase: "pronto"; datasets: DatasetVista[] };

export function SecaoDadosAbertos({ ente }: { ente: string }) {
  const [estado, setEstado] = useState<Estado>({ fase: "carregando" });

  useEffect(() => {
    let vivo = true;
    buscarPublico<DadosAbertosOut>(ente, "dados-abertos").then((d) => {
      if (vivo) setEstado(d ? { fase: "pronto", datasets: derivarDadosAbertos(d, ente) } : { fase: "erro" });
    });
    return () => {
      vivo = false;
    };
  }, [ente]);

  return (
    <section className="dados-abertos" aria-labelledby="dados-abertos-titulo">
      <div className="pg-cab">
        <span className="rotulo-secao">Transparência ativa</span>
        <h1 id="dados-abertos-titulo">Dados abertos</h1>
        <p className="sub">
          Os dados que a Câmara publica neste portal, em formato aberto, para baixar e usar livremente. Cada arquivo
          traz tudo o que está publicado, não uma amostra.
        </p>
      </div>

      {estado.fase === "carregando" && <p className="estado">Carregando os dados…</p>}
      {estado.fase === "erro" && (
        <p className="estado" role="alert">
          Não foi possível carregar os dados abertos agora. Tente novamente em instantes.
        </p>
      )}
      {estado.fase === "pronto" && (
        <ul className="lista-ds" aria-label="Conjuntos de dados">
          {estado.datasets.map((d) => (
            <li key={d.chave} className="ds">
              <div>
                <h2>{d.titulo}</h2>
                <p>{d.descricao}</p>
                <div className="meta">
                  <span className="fmt">{d.formato}</span>
                  <span className="quando">{d.registros}</span>
                  {d.atualizado && <span className="quando">{d.atualizado}</span>}
                </div>
              </div>
              <a className="baixar" href={d.href} download={d.arquivo} aria-label={`Baixar ${d.titulo} (${d.formato})`}>
                Baixar
              </a>
              <details className="dicionario">
                <summary>O que cada coluna significa</summary>
                <dl>
                  {d.colunas.map((c) => (
                    <div key={c.nome}>
                      <dt>{c.nome}</dt>
                      <dd>{c.descricao}</dd>
                    </div>
                  ))}
                </dl>
              </details>
            </li>
          ))}
        </ul>
      )}

      <h2 className="secao-tit">Ainda não publicados aqui</h2>
      <ul className="lista-fora">
        {FORA.map((f) => (
          <li key={f.titulo}>
            <EmBreve titulo={f.titulo} motivo={f.motivo} />
          </li>
        ))}
      </ul>

      <p className="nota">
        Arquivos em CSV (UTF-8, separados por vírgula), com a primeira linha de cabeçalho. Datas e horários em ISO 8601
        (UTC). Os mesmos dados estão na API do portal, em JSON. Uso livre, citando a fonte (Lei 12.527/2011, art. 8º
        §3º).
      </p>
    </section>
  );
}
