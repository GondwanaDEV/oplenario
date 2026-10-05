"use client";

// A LISTA dos vereadores em exercício no portal do cidadão (GET /portal/casa/{ente}/vereadores). O perfil público
// de cada um (`/vereadores/[vereadorId]`) só abria por UUID; esta é a porta de entrada. Cada cartão leva ao perfil.
// O que a lista mostra é o que o contrato publica: nome parlamentar (ou civil), partido e cargo na Mesa — nada de
// contato, e nenhum número de atuação (isso é do perfil).
//
// Quatro estados, todos com texto: carregando (região viva), erro (a rota falhou, ou a Casa não existe — `buscarPublico`
// colapsa os dois no mesmo `null`, e a tela não afirma qual), vazio (Casa que existe e ainda não tem vereador em
// exercício cadastrado) e pronto.

import { useEffect, useState } from "react";
import { buscarPublico } from "@/lib/portal-api";
import type { VereadoresOut } from "@/lib/contrato-portal.gen";
import { derivarLista, type ListaVista } from "@/lib/vereadores-vista";
import "./lista-vereadores.css";

type Carga = { fase: "carregando" } | { fase: "pronto"; lista: ListaVista } | { fase: "erro" };

function useListaDeVereadores(ente: string): Carga {
  // o resultado guarda DE QUAL Casa é: trocar de `ente` nunca mostra a lista da anterior por um frame.
  const [r, setR] = useState<{ de: string | null; carga: Carga }>({ de: null, carga: { fase: "carregando" } });
  useEffect(() => {
    let vivo = true;
    (async () => {
      const d = await buscarPublico<VereadoresOut>(ente, "vereadores");
      if (!vivo) return;
      setR({ de: ente, carga: d && Array.isArray(d.vereadores) ? { fase: "pronto", lista: derivarLista(d, ente) } : { fase: "erro" } });
    })();
    return () => {
      vivo = false;
    };
  }, [ente]);
  return r.de === ente ? r.carga : { fase: "carregando" };
}

export function ListaVereadores({ ente }: { ente: string }) {
  const carga = useListaDeVereadores(ente);
  return (
    <section className="lista-vereadores" aria-labelledby="lista-vereadores-titulo">
      <div className="pg-cab">
        <span className="rotulo-secao">Câmara Municipal</span>
        <h1 id="lista-vereadores-titulo">Vereadores em exercício</h1>
        <p className="sub">
          Quem exerce o mandato hoje. Escolha um nome para ver as matérias de autoria, os votos nominais e a presença
          em sessões.
        </p>
      </div>

      {carga.fase === "carregando" && (
        <p className="estado" role="status" aria-live="polite">
          Carregando os vereadores…
        </p>
      )}
      {carga.fase === "erro" && (
        <p className="estado" role="alert">
          Não foi possível carregar a lista de vereadores agora. Tente novamente em instantes.
        </p>
      )}
      {carga.fase === "pronto" && carga.lista.vereadores.length === 0 && (
        <p className="estado" role="status">
          Nenhum vereador em exercício registrado até agora.
        </p>
      )}
      {carga.fase === "pronto" && carga.lista.vereadores.length > 0 && (
        <>
          <p className="lv-resumo">{carga.lista.resumo}</p>
          <ul className="lv-lista" aria-label="Vereadores em exercício">
            {carga.lista.vereadores.map((v) => (
              <li key={v.vereadorId}>
                <a className="lv-cartao" href={v.href}>
                  <span className="lv-foto" aria-hidden="true">
                    {v.iniciais}
                  </span>
                  <span className="lv-corpo">
                    <b className="lv-nome">{v.nome}</b>
                    {v.nomeSecundario && <span className="lv-civil">{v.nomeSecundario}</span>}
                    {(v.partido || v.cargoMesa) && (
                      <span className="lv-tags">
                        {v.cargoMesa && <span className="lv-tag mesa">{v.cargoMesa}</span>}
                        {v.partido && <span className="lv-tag">{v.partido}</span>}
                      </span>
                    )}
                  </span>
                </a>
              </li>
            ))}
          </ul>
        </>
      )}
    </section>
  );
}
