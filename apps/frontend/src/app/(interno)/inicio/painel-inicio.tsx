"use client";

// PainelInicio — a parte APRESENTACIONAL da tela inicial: recebe a vista já derivada (inicio-vista.ts) e
// só desenha. Sem hooks, sem fetch, sem decisão de persona — por isso é testável renderizando direto, sem
// providers. Toda a regra ("há sessão agora?", "para onde esta persona vai") mora no view-model puro.

import Link from "next/link";
import type { AcaoInicio, InicioVista } from "@/lib/inicio-vista";

function Acao({ acao }: { acao: AcaoInicio }) {
  return (
    <Link className={`btn ${acao.tom === "primaria" ? "btn-primaria" : "btn-contorno"}`} href={acao.href}>
      {acao.rotulo}
    </Link>
  );
}

function CardAtalho({ acao }: { acao: AcaoInicio }) {
  return (
    <Link className="atalho" href={acao.href}>
      <b>{acao.rotulo}</b>
      {acao.descricao && <span>{acao.descricao}</span>}
    </Link>
  );
}

export function PainelInicio({ vista }: { vista: InicioVista }) {
  const { sessao, atalhos } = vista;
  // `carregando` usa aria-busy em vez de texto de erro/vazio: a tela nunca afirma "não há sessão" antes de
  // saber (mesma lição do defeito #16 que a home do vereador pagou caro).
  const carregando = sessao.situacao === "carregando";

  return (
    <main className="envelope inicio">
      <section className={`card inicio-sessao inicio-sessao--${sessao.situacao}`} aria-busy={carregando}>
        <p className="eyebrow">
          {sessao.situacao === "ao-vivo" ? "Ao vivo" : "A sessão"}
        </p>
        <h1>{sessao.titulo}</h1>
        {sessao.detalhe && <p className="inicio-detalhe">{sessao.detalhe}</p>}
        {sessao.acoes.length > 0 && (
          <div className="acoes">
            {sessao.acoes.map((a) => (
              <Acao key={a.href + a.rotulo} acao={a} />
            ))}
          </div>
        )}
        {sessao.acoes.some((a) => a.descricao) && (
          <ul className="inicio-legenda">
            {sessao.acoes
              .filter((a) => a.descricao)
              .map((a) => (
                <li key={`leg-${a.href}`}>
                  <b>{a.rotulo}</b> — {a.descricao}
                </li>
              ))}
          </ul>
        )}
      </section>

      <section aria-labelledby="inicio-atalhos-titulo">
        <h2 id="inicio-atalhos-titulo" className="inicio-secao-tit">
          {vista.persona === "secretaria" ? "Áreas de trabalho" : "Sua área"}
        </h2>
        <div className="atalhos">
          {atalhos.map((a) => (
            <CardAtalho key={a.href} acao={a} />
          ))}
        </div>
      </section>
    </main>
  );
}
