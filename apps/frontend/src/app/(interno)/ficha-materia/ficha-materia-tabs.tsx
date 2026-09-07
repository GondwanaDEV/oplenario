"use client";

// FichaMateriaTabs — as 5 abas do corpo da ficha (Onda B Slice 3): Texto vigente, Tramitação, Pareceres,
// Emendas, Anexos. Porte de ficha-materia.html:180-236 (padrão ARIA tabs: role=tablist/tab/tabpanel,
// roving tabindex, ArrowLeft/ArrowRight com wraparound — mesmo script inline da tela-fonte, portado pra
// React). Pareceres/Emendas mostram TODOS os estados (decisão da fatia, ver ficha-materia-vista.ts) — só
// rotulam, nunca filtram. Anexos não tem backend nesta fatia -> <EmBreve> honesto (mesma disciplina de
// AcoesCard/BalcaoLgpd).

import { rotularVoto } from "@/lib/parecer-vista";
import { useMemo, useRef, useState } from "react";
import Link from "next/link";
import { EmBreve } from "@/lib/em-breve";
import { derivarTimelineTramitacao, derivarPareceres, derivarEmendas } from "@/lib/ficha-materia-vista";
import { formatarData } from "@/lib/formatar-data";
import { comToken } from "@/lib/nav";
import type { FichaMateriaOut } from "@/lib/contrato-legislativo.gen";

type Aba = { id: string; rotulo: string; contagem?: number };

export function FichaMateriaTabs({
  ficha,
  token = null,
}: {
  ficha: FichaMateriaOut;
  // token dev opcional (Onda B Slice 5) — só pra preservar ?token= no link "Abrir parecer"; recebido via
  // prop (não `useAuth()` aqui) porque esta suíte de teste renderiza o componente SEM <AuthProvider>.
  token?: string | null;
}) {
  // useMemo: todos os 5 painéis ficam montados simultaneamente (só `hidden` alterna, ver abaixo) — sem
  // isto, o sort()+map() das 3 derivações reroda a cada keypress de navegação das abas (ArrowLeft/Right/
  // Home/End), mesmo quando `ficha` não mudou (achado do review desta fatia).
  const timeline = useMemo(() => derivarTimelineTramitacao(ficha.tramitacao), [ficha.tramitacao]);
  const pareceres = useMemo(() => derivarPareceres(ficha.pareceres), [ficha.pareceres]);
  const emendas = useMemo(() => derivarEmendas(ficha.emendas), [ficha.emendas]);

  const abas: Aba[] = [
    { id: "texto", rotulo: "Texto vigente" },
    { id: "tram", rotulo: "Tramitação", contagem: timeline.length },
    { id: "pareceres", rotulo: "Pareceres", contagem: pareceres.length },
    { id: "emendas", rotulo: "Emendas", contagem: emendas.length },
    { id: "anexos", rotulo: "Anexos" },
  ];

  const [selecionada, setSelecionada] = useState(0);
  const botoesRef = useRef<(HTMLButtonElement | null)[]>([]);

  // roving tabindex real: o foco do DOM sempre segue a seleção, seja por teclado OU clique (mesmo
  // comportamento do script inline de ficha-materia.html, `sel()` chamando `.focus()`) — um único caminho
  // pra ambas as modalidades evita que aria-selected/tabIndex desincronizem do foco real do DOM (ex.
  // Safari não move foco pra <button> clicado por padrão sem "Full Keyboard Access", achado do review).
  function selecionar(i: number) {
    setSelecionada(i);
    botoesRef.current[i]?.focus();
  }

  function aoTeclar(e: React.KeyboardEvent<HTMLButtonElement>) {
    if (e.key === "ArrowRight") {
      e.preventDefault();
      selecionar((selecionada + 1) % abas.length);
    } else if (e.key === "ArrowLeft") {
      e.preventDefault();
      selecionar((selecionada - 1 + abas.length) % abas.length);
    } else if (e.key === "Home") {
      e.preventDefault();
      selecionar(0);
    } else if (e.key === "End") {
      e.preventDefault();
      selecionar(abas.length - 1);
    }
  }

  return (
    <div>
      <h2 className="sr-only">Conteúdo da matéria</h2>
      <div className="abas" role="tablist" aria-label="Seções da matéria">
        {abas.map((aba, i) => (
          <button
            key={aba.id}
            ref={(el) => {
              botoesRef.current[i] = el;
            }}
            className="aba"
            role="tab"
            id={`t-${aba.id}`}
            aria-selected={i === selecionada}
            aria-controls={`p-${aba.id}`}
            tabIndex={i === selecionada ? 0 : -1}
            onClick={() => selecionar(i)}
            onKeyDown={aoTeclar}
            type="button"
          >
            {aba.rotulo}
            {aba.contagem !== undefined && <span className="cont">{aba.contagem}</span>}
          </button>
        ))}
      </div>

      <section
        className="painel"
        id="p-texto"
        role="tabpanel"
        aria-labelledby="t-texto"
        tabIndex={0}
        hidden={selecionada !== 0}
      >
        {ficha.proposicao.texto ? (
          <div className="texto-lei">
            <p>{ficha.proposicao.texto}</p>
          </div>
        ) : (
          <p>Nenhum texto vigente registrado ainda para esta matéria.</p>
        )}
      </section>

      <section
        className="painel"
        id="p-tram"
        role="tabpanel"
        aria-labelledby="t-tram"
        tabIndex={0}
        hidden={selecionada !== 1}
      >
        {timeline.length === 0 ? (
          <p>Nenhuma transição de tramitação registrada ainda.</p>
        ) : (
          <ol className="tempo">
            {timeline.map((item, i) => (
              <li key={`${item.ocorridoEm}-${i}`}>
                <span className="data">{formatarData(item.ocorridoEm)}</span>
                <p className="evt">
                  {item.rotuloDe} → {item.rotuloPara}
                </p>
                <span className="quem">{item.gatilho}</span>
              </li>
            ))}
          </ol>
        )}
      </section>

      <section
        className="painel"
        id="p-pareceres"
        role="tabpanel"
        aria-labelledby="t-pareceres"
        tabIndex={0}
        hidden={selecionada !== 2}
      >
        {pareceres.length === 0 ? (
          <p>Nenhum parecer registrado ainda.</p>
        ) : (
          <ul className="tempo">
            {pareceres.map((p) => (
              <li key={p.id}>
                <span className={`chip chip-${p.categoria}`}>{p.rotuloEstado}</span>
                <p className="evt">{p.comissaoRotulo}</p>
                <span className="quem">
                  {p.votoRelator
                    ? `Voto do relator: ${rotularVoto(p.votoRelator)}`
                    : "Sem voto de relator registrado"}
                </span>
                <Link className="ir" href={comToken(`/parecer/${p.id}`, token)}>
                  Abrir parecer
                </Link>
              </li>
            ))}
          </ul>
        )}
      </section>

      <section
        className="painel"
        id="p-emendas"
        role="tabpanel"
        aria-labelledby="t-emendas"
        tabIndex={0}
        hidden={selecionada !== 3}
      >
        {emendas.length === 0 ? (
          <p>Nenhuma emenda apresentada ainda.</p>
        ) : (
          <ul className="tempo">
            {emendas.map((e) => (
              <li key={e.id}>
                <span className={`chip chip-${e.categoria}`}>{e.rotuloEstado}</span>
                <p className="evt">
                  Emenda <b>{e.rotuloTipo}</b> nº {e.numeroLocal}
                </p>
                <span className="quem">{e.autorTexto ?? "Autoria não informada"}</span>
              </li>
            ))}
          </ul>
        )}
      </section>

      <section
        className="painel"
        id="p-anexos"
        role="tabpanel"
        aria-labelledby="t-anexos"
        tabIndex={0}
        hidden={selecionada !== 4}
      >
        <EmBreve
          titulo="Anexos"
          motivo="A lista de anexos (texto assinado ICP-Brasil, pareceres em PDF, estudos de impacto) ainda não tem backend fiado nesta fatia."
        />
      </section>
    </div>
  );
}
