"use client";

// FormTemposTribuna — a tabela de tempos da tribuna da Casa, editável pela secretaria (recebe `token` por prop,
// testável sem <AuthProvider>, mesmo split de form-agendar-sessao.tsx). Um bloco por TIPO DE FALA: o tempo
// padrão, de onde ele vem no Regimento e, recolhido, um tempo diferente por fase. Salva a tabela INTEIRA
// (PUT); a barra de salvar/descartar só aparece quando há diferença do que está salvo (arquétipo config,
// telas/config-ente.html). Lógica pura em lib/tempos-tribuna-vista.ts; IO em lib/use-tempos-tribuna.ts.

import { useState } from "react";
import { useTemposTribuna } from "@/lib/use-tempos-tribuna";
import { nomeFase, nomeTipoFala } from "@/lib/rotulos-sessao";
import {
  FASES_TEMPO,
  TIPOS_FALA_TEMPO,
  alterado,
  itensDe,
  rascunhoDe,
  temTempoPorFase,
  type ErrosTempos,
  type FaseTempo,
  type ItemTempo,
  type RascunhoTempos,
  type TipoFalaTempo,
} from "@/lib/tempos-tribuna-vista";

export function FormTemposTribuna({ token }: { token: string | null }) {
  const { itens, estado, salvar } = useTemposTribuna(token);

  // A edição vale para a tabela de onde saiu: quando o servidor devolve outra (carga, ou depois de salvar),
  // a tela volta a mostrar o que está salvo — sem efeito sincronizando estado.
  const [edicao, setEdicao] = useState<{ de: ItemTempo[]; r: RascunhoTempos } | null>(null);
  const [abertos, setAbertos] = useState<Set<TipoFalaTempo>>(new Set());
  const [erros, setErros] = useState<ErrosTempos>({});
  const [salvando, setSalvando] = useState(false);
  const [aviso, setAviso] = useState<{ tipo: "ok" | "erro"; texto: string } | null>(null);

  const original = rascunhoDe(itens);
  const rascunho = edicao && edicao.de === itens ? edicao.r : original;
  const sujo = alterado(rascunho, original);

  function editar(mudar: (r: RascunhoTempos) => void, campo: string) {
    const copia = structuredClone(rascunho);
    mudar(copia);
    setEdicao({ de: itens, r: copia });
    setAviso(null);
    if (erros[campo]) setErros(Object.fromEntries(Object.entries(erros).filter(([k]) => k !== campo)));
  }

  function descartar() {
    setEdicao(null);
    setErros({});
    setAviso(null);
  }

  async function onSalvar() {
    const v = itensDe(rascunho);
    if (!v.ok) {
      setErros(v.erros);
      setAviso({ tipo: "erro", texto: "Corrija os tempos marcados antes de salvar." });
      // leva a pessoa ao primeiro campo errado (na ordem da tela), inclusive dentro de um "por fase" recolhido
      const primeiro = [...TIPOS_FALA_TEMPO]
        .flatMap((t) => [`${t}:padrao`, ...FASES_TEMPO.map((f) => `${t}:${f}`)])
        .find((k) => v.erros[k]);
      if (primeiro) {
        const [tipo, onde] = primeiro.split(":") as [TipoFalaTempo, string];
        if (onde !== "padrao") setAbertos((s) => new Set(s).add(tipo));
        requestAnimationFrame(() => document.getElementById(`tempo-${tipo}-${onde}`)?.focus());
      }
      return;
    }
    setSalvando(true);
    const r = await salvar(v.itens);
    setSalvando(false);
    if (r.ok) {
      setEdicao(null);
      setErros({});
      setAviso({ tipo: "ok", texto: "Tempos salvos. Valem a partir da próxima fala chamada à tribuna." });
    } else {
      setAviso({ tipo: "erro", texto: r.erro });
    }
  }

  if (estado === "carregando") {
    return (
      <main className="envelope tempos-tribuna">
        <p className="nota-terminal">Carregando os tempos da Casa…</p>
      </main>
    );
  }
  if (estado === "erro") {
    return (
      <main className="envelope tempos-tribuna">
        <p className="nota-terminal">
          Não foi possível carregar os tempos da Casa. Confira se o seu login é da secretaria e tente de novo.
        </p>
      </main>
    );
  }

  return (
    <main className="envelope tempos-tribuna">
      <div className="cabecalho">
        <p className="eyebrow">Sessões</p>
        <h1>Tempos da tribuna</h1>
        <p className="subtitulo">
          Quanto tempo cada fala tem. A TV do plenário mostra a contagem regressiva e toca a campainha quando o
          tempo acaba; a Mesa concede +1 min ou encerra a fala.
        </p>
      </div>

      <ul className="regras" aria-label="Como os tempos funcionam">
        <li>
          Digite em minutos (<b>3</b>) ou minutos e segundos (<b>1:30</b>). Vazio = <b>sem limite</b>: o
          cronômetro só conta, sem campainha.
        </li>
        <li>O tempo de uma fase, quando preenchido, vale no lugar do padrão naquela fase.</li>
        <li>Quem já está na tribuna mantém o tempo que recebeu; a mudança vale a partir da próxima fala.</li>
      </ul>

      <div className="tipos">
        {TIPOS_FALA_TEMPO.map((tipo) => {
          const linha = rascunho[tipo];
          const nome = nomeTipoFala(tipo);
          const idBase = `tempo-${tipo}`;
          const aberto = abertos.has(tipo) || temTempoPorFase(original[tipo]);
          const erroPadrao = erros[`${tipo}:padrao`];
          return (
            <fieldset key={tipo} className="tipo" aria-labelledby={`${idBase}-nome`}>
              <legend id={`${idBase}-nome`} className="tipo-nome">
                {nome}
              </legend>
              <div className="tipo-linha">
                <div className="campo campo-tempo">
                  <label htmlFor={`${idBase}-padrao`}>Tempo</label>
                  <input
                    id={`${idBase}-padrao`}
                    inputMode="numeric"
                    autoComplete="off"
                    placeholder="sem limite"
                    value={linha.padrao}
                    aria-invalid={erroPadrao ? true : undefined}
                    aria-describedby={erroPadrao ? `${idBase}-padrao-erro` : undefined}
                    onChange={(e) => editar((r) => (r[tipo].padrao = e.target.value), `${tipo}:padrao`)}
                  />
                  {erroPadrao && (
                    <p id={`${idBase}-padrao-erro`} className="erro-campo">
                      {erroPadrao}
                    </p>
                  )}
                </div>
                <div className="campo campo-ref">
                  <label htmlFor={`${idBase}-ref`}>Referência no Regimento (opcional)</label>
                  <input
                    id={`${idBase}-ref`}
                    autoComplete="off"
                    placeholder="ex.: RI, art. 98"
                    maxLength={200}
                    value={linha.referencia}
                    onChange={(e) => editar((r) => (r[tipo].referencia = e.target.value), `${tipo}:ref`)}
                  />
                </div>
              </div>

              <button
                type="button"
                className="btn btn-fantasma btn-mini por-fase-toggle"
                aria-expanded={aberto}
                aria-controls={`${idBase}-fases`}
                onClick={() =>
                  setAbertos((s) => {
                    const n = new Set(s);
                    if (n.has(tipo)) n.delete(tipo);
                    else n.add(tipo);
                    return n;
                  })
                }
              >
                Tempo diferente por fase
              </button>

              {aberto && (
                <div id={`${idBase}-fases`} className="fases">
                  {FASES_TEMPO.map((fase: FaseTempo) => {
                    const chave = `${tipo}:${fase}`;
                    const erroFase = erros[chave];
                    return (
                      <div key={fase} className="campo campo-fase">
                        <label htmlFor={`${idBase}-${fase}`}>{nomeFase(fase)}</label>
                        <input
                          id={`${idBase}-${fase}`}
                          inputMode="numeric"
                          autoComplete="off"
                          placeholder="usa o padrão"
                          value={linha.porFase[fase]}
                          aria-invalid={erroFase ? true : undefined}
                          aria-describedby={erroFase ? `${idBase}-${fase}-erro` : undefined}
                          onChange={(e) => editar((r) => (r[tipo].porFase[fase] = e.target.value), chave)}
                        />
                        {erroFase && (
                          <p id={`${idBase}-${fase}-erro`} className="erro-campo">
                            {erroFase}
                          </p>
                        )}
                      </div>
                    );
                  })}
                </div>
              )}
            </fieldset>
          );
        })}
      </div>

      {/* a confirmação mora na MESMA barra fixa: quem salvou no meio da lista não rola até o fim para vê-la */}
      {!sujo && aviso?.tipo === "ok" && (
        <div className="comando" role="region" aria-label="Situação dos tempos">
          <div className="envelope">
            <div className="comando-grade">
              <p className="comando-ctx salvo" role="status">
                {aviso.texto}
              </p>
            </div>
          </div>
        </div>
      )}

      {sujo && (
        <div className="comando" role="region" aria-label="Salvar alterações">
          <div className="envelope">
            <div className="comando-grade">
              <div className="comando-ctx">
                <p className="comando-titulo">Alterações não salvas</p>
                {aviso?.tipo === "erro" && (
                  <p className="erro-inline" role="alert">
                    {aviso.texto}
                  </p>
                )}
              </div>
              <div className="comando-acoes">
                <button type="button" className="btn btn-fantasma" onClick={descartar} disabled={salvando}>
                  Descartar
                </button>
                <button type="button" className="btn btn-primaria" onClick={onSalvar} disabled={salvando}>
                  {salvando ? "Salvando…" : "Salvar tempos"}
                </button>
              </div>
            </div>
          </div>
        </div>
      )}
    </main>
  );
}
