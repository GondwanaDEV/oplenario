"use client";

// Onda E — Observabilidade da IA no console do operador (porte de observabilidade-ia.html, arquétipo cockpit). Todas
// as Casas juntas, sem identificar nenhuma: só contagens, tempos e custo do registro da Camada de Confiança. Os desvios
// do design estão em lib/observabilidade-ia-vista.ts.

import { useState } from "react";
import { useAuth } from "@/lib/auth";
import { derivarObservabilidade } from "@/lib/observabilidade-ia-vista";
import { useObservabilidadeIA } from "@/lib/use-operacao";

const JANELAS = [
  { horas: 24, rotulo: "24 horas" },
  { horas: 168, rotulo: "7 dias" },
] as const;

export default function ObservabilidadeDaIA() {
  const { token } = useAuth();
  const [horas, setHoras] = useState<number>(24);
  const { dados, estado } = useObservabilidadeIA(horas, token);
  const v = dados ? derivarObservabilidade(dados) : null;

  return (
    <>
      <div className="op-pg-cab">
        <div>
          <p className="eyebrow">Console do operador</p>
          <h1>Observabilidade da IA</h1>
          <p className="sub">
            Saúde do modelo de linguagem que alimenta o copiloto — em todas as câmaras, sem identificar nenhuma. A IA
            sugere; o servidor revisa e assume.
          </p>
        </div>
        <div className="op-janela" role="group" aria-label="Janela">
          {JANELAS.map((j) => (
            <button key={j.horas} type="button" aria-pressed={horas === j.horas} onClick={() => setHoras(j.horas)}>
              {j.rotulo}
            </button>
          ))}
        </div>
      </div>

      {estado === "sem-sessao" && (
        <p className="op-aviso erro" role="alert">
          Sua sessão do console expirou. <a className="op-link" href="/operacao/entrar">Entrar de novo</a>
        </p>
      )}
      {estado === "erro" && <p className="op-aviso erro" role="alert">Não deu para carregar a observabilidade agora.</p>}
      {dados && !dados.disponivel && (
        <p className="op-aviso" role="status">
          <b>A plataforma de IA não respondeu agora.</b> Os números aparecem quando ela voltar; as câmaras seguem
          trabalhando pela tela, sem IA.
        </p>
      )}
      {estado === "carregando" && <p className="op-vazio">Carregando a observabilidade…</p>}

      {v && (
        <>
          <div className="op-metricas op-metricas-ia" aria-live="polite">
            {v.metricas.map((m) => (
              <div key={m.titulo} className={`op-mt${m.alerta ? " alerta" : ""}`}>
                <span className="t">{m.titulo}</span>
                <b>{m.valor}</b>
                {m.detalhe && <span className="d">{m.detalhe}</span>}
              </div>
            ))}
          </div>

          {v.vazio && <p className="op-aviso" role="status">{v.vazio}</p>}

          {dados?.disponivel && !v.vazio && (
            <>
              <div className="op-ia-grade">
                <section className="op-bloco-ia" aria-labelledby="t-recurso">
                  <h2 id="t-recurso">Por recurso</h2>
                  <p className="aj">Volume e tempo de resposta p95 de cada uso da IA, {v.janela}.</p>
                  {v.recursos.map((r) => (
                    <div key={r.codigo} className="op-rec">
                      <span className="nm">
                        {r.nome} <code>{r.codigo}</code>
                        <span>{r.detalhe}</span>
                      </span>
                      <span className="p95">{r.p95}</span>
                      <span className="bar" aria-hidden="true"><i style={{ width: `${r.largura}%` }} /></span>
                    </div>
                  ))}
                </section>
                <section className="op-bloco-ia" aria-labelledby="t-forn">
                  <h2 id="t-forn">Fornecedores</h2>
                  <p className="aj">Atrás da porta de inferência: o fornecedor é trocável sem mudar as capacidades.</p>
                  {v.fornecedores.map((f) => (
                    <div key={f.nome} className="op-prov">
                      <div className="nm"><b>{f.nome}</b><span>{f.detalhe}</span></div>
                      <div className="lat">
                        {f.falhas
                          ? <span className="pst pst-deg"><span className="dot" aria-hidden="true" />{f.falhas}</span>
                          : <span className="pst pst-ok"><span className="dot" aria-hidden="true" />Todas rodaram</span>}
                        <br />p95 {f.p95}
                      </div>
                    </div>
                  ))}
                  {v.motivos.length > 0 && (
                    <>
                      <h3>Por que não rodaram</h3>
                      <ul className="op-motivos">
                        {v.motivos.map((m) => <li key={m.rotulo}><span>{m.rotulo}</span><b>{m.execucoes}</b></li>)}
                      </ul>
                    </>
                  )}
                </section>
              </div>

              <section className="op-bloco-ia op-vol-bloco" aria-labelledby="t-vol">
                <h2 id="t-vol">Volume nas últimas {v.janela}</h2>
                <p className="aj">Execuções por hora, todas as câmaras (horário de Fortaleza).</p>
                <ul className={`op-vol${v.barras.length > 48 ? " denso" : ""}`} aria-label={`Execuções por hora nas últimas ${v.janela}`}>
                  {v.barras.map((b, i) => (
                    <li key={i} className={b.indisponiveis ? "falha" : undefined} style={{ height: `${Math.max(b.altura, 3)}%` }}
                      title={b.rotulo}>
                      <span className="sr-only">{b.rotulo}</span>
                    </li>
                  ))}
                </ul>
                <div className="op-vol-leg" aria-hidden="true">{v.legenda.map((l, i) => <span key={i}>{l}</span>)}</div>
              </section>
            </>
          )}
        </>
      )}

      <div className="op-gov">
        <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" aria-hidden="true"><path d="M12 3l8 4v5c0 5-3.5 8-8 9-4.5-1-8-4-8-9V7z" /></svg>
        <span>
          <b>A IA é monitorada, não autônoma.</b> Quando o fornecedor falha, a execução não roda e a câmara segue pela
          tela — não há troca silenciosa de fornecedor. Nenhuma saída de IA vira ato oficial sem revisão de um servidor.
          Aqui entram as execuções do modelo de linguagem (ata, resumo, assistente, conferência, copiloto); a transcrição
          e a busca ficam fora desta medida. O registro não guarda texto: só contagens, tempos e custo.
        </span>
      </div>
    </>
  );
}
