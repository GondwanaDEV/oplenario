"use client";

// Painel de VOTAÇÃO do Comando da Mesa (§22.6 eixo C) — subcomponente da tela /conduzir. As duas escritas
// que só existiam via API: abrir uma votação sobre um objeto da pauta e encerrá-la. IO/CAS em
// `use-votacao-mesa.ts`; opções/derivação em `votacao-mesa-vista.ts`. Aqui só orquestração + apresentação.
//
// Gate visual = estado da sessão: só aparece com a sessão ABERTA (o backend recusa fora disso; a UI não
// oferece). Com uma votação em curso, mostra o resumo + Encerrar (simbólica pede o resultado declarado);
// sem nenhuma, mostra o formulário de abrir (objeto da pauta + modalidade + quórum).
//
// A matéria que a Mesa ANUNCIOU (em apreciação, docs/23 Fatia 4b) vem pré-escolhida no seletor: o rito é
// anunciar → discutir → votar a mesma matéria. A Mesa pode trocar; depois de abrir, o seletor volta vazio.

import { useState } from "react";
import { formatarNumeroProposicao } from "@/lib/proposicoes-vista";
import { useVotacaoMesa, type ModalidadeVotacao, type QuorumTipo, type ResultadoVotacao } from "@/lib/use-votacao-mesa";
import {
  candidatosObjeto,
  derivarPainelVotacao,
  rotuloModalidade,
  rotuloObjetoTipo,
  rotuloQuorum,
  MODALIDADES,
  QUORUNS,
} from "@/lib/votacao-mesa-vista";
import { nomeFase } from "@/lib/rotulos-sessao";

export function PainelVotacao({
  sessaoId,
  token,
  sessaoEstado,
  emApreciacaoItemId = null,
}: {
  sessaoId: string;
  token: string | null;
  sessaoEstado: string;
  /** O item da pauta anunciado pela Mesa (`em-apreciacao` da pauta) — pré-escolhe a matéria. */
  emApreciacaoItemId?: string | null;
}) {
  const { votacaoAberta, itens, estado, abrir, encerrar } = useVotacaoMesa(sessaoId, token);
  const painel = derivarPainelVotacao({ sessaoEstado, votacaoAberta });

  // A escolha da Mesa vale sob o anúncio em que foi feita; um anúncio novo volta a pré-escolher a matéria.
  const [escolha, setEscolha] = useState<{ valor: string; sob: string | null } | null>(null);
  const setObjetoId = (valor: string) => setEscolha({ valor, sob: emApreciacaoItemId });
  const [modalidade, setModalidade] = useState<ModalidadeVotacao>("nominal");
  const [quorumTipo, setQuorumTipo] = useState<QuorumTipo>("maioria_simples");
  const [resultado, setResultado] = useState<ResultadoVotacao | "">("");
  const [enviando, setEnviando] = useState(false);
  const [erroAcao, setErroAcao] = useState<string | null>(null);
  const [aviso, setAviso] = useState<string | null>(null);

  const candidatos = candidatosObjeto(itens);
  const sugerido = itens.find((i) => i.id === emApreciacaoItemId)?.proposicaoId ?? null;
  const objetoId =
    escolha && escolha.sob === emApreciacaoItemId
      ? escolha.valor
      : candidatos.some((c) => c.objetoId === sugerido)
        ? sugerido!
        : "";

  async function onAbrir() {
    if (!objetoId) {
      setErroAcao("Escolha o objeto da votação na pauta.");
      return;
    }
    const alvo = candidatos.find((c) => c.objetoId === objetoId);
    setEnviando(true);
    setErroAcao(null);
    setAviso(null);
    const r = await abrir({
      objetoTipo: "proposicao",
      objetoId,
      modalidade,
      quorumTipo,
      pautaItemId: alvo ? itens.find((i) => i.proposicaoId === objetoId)?.id ?? null : null,
    });
    setEnviando(false);
    if (r.ok) {
      setObjetoId("");
      setAviso("Votação aberta.");
    } else {
      setErroAcao(r.erro);
    }
  }

  async function onEncerrar(exigeResultado: boolean) {
    if (exigeResultado && !resultado) {
      setErroAcao("Na votação simbólica, declare o resultado (aprovada ou rejeitada) para encerrar.");
      return;
    }
    setEnviando(true);
    setErroAcao(null);
    setAviso(null);
    const r = await encerrar(exigeResultado ? (resultado as ResultadoVotacao) : undefined);
    setEnviando(false);
    if (r.ok) {
      setResultado("");
      setAviso("Votação encerrada.");
    } else {
      setErroAcao(r.erro);
    }
  }

  return (
    <section className="bloco votacao" aria-labelledby="votacao-titulo">
      <div className="bloco-cabeca">
        <h2 id="votacao-titulo">Votação</h2>
        {painel.tipo === "em-curso" && <span className="chip chip-ok">Em curso</span>}
      </div>
      <div className="bloco-corpo">
        {aviso && (
          <p role="status" className="aviso-ok">
            {aviso}
          </p>
        )}
        {erroAcao && (
          <p role="alert" className="erro-inline">
            {erroAcao}
          </p>
        )}

        {painel.tipo === "indisponivel" && (
          <p className="nota-terminal">
            <span>{painel.nota}</span>
          </p>
        )}

        {painel.tipo === "em-curso" && (
          <div className="votacao-em-curso">
            <dl className="votacao-resumo">
              <div>
                <dt>Objeto</dt>
                <dd>
                  {painel.votacao.proposicao
                    ? formatarNumeroProposicao(
                        painel.votacao.proposicao.tipo,
                        painel.votacao.proposicao.sequencial,
                        painel.votacao.proposicao.ano,
                      )
                    : rotuloObjetoTipo(painel.votacao.objetoTipo)}
                </dd>
              </div>
              <div>
                <dt>Modalidade</dt>
                <dd>{rotuloModalidade(painel.votacao.modalidade)}</dd>
              </div>
            </dl>
            {painel.votacao.proposicao?.ementa && (
              <p className="votacao-ementa">{painel.votacao.proposicao.ementa}</p>
            )}
            {painel.exigeResultado && (
              <fieldset className="campo-radio">
                <legend>Resultado declarado (aclamação)</legend>
                {(["aprovada", "rejeitada"] as ResultadoVotacao[]).map((v) => (
                  <label key={v} htmlFor={`res-${v}`}>
                    <input
                      id={`res-${v}`}
                      type="radio"
                      name="resultado"
                      checked={resultado === v}
                      onChange={() => setResultado(v)}
                    />
                    {v === "aprovada" ? "Aprovada" : "Rejeitada"}
                  </label>
                ))}
              </fieldset>
            )}
            <p className="nota-mesa">
              <span>
                O placar ao vivo aparece no telão (<code>/plenario</code>). Aqui a Mesa só abre e encerra; os
                votos entram pelo cockpit dos vereadores.
              </span>
            </p>
            <button
              type="button"
              className="btn btn-encerrar"
              disabled={enviando}
              onClick={() => onEncerrar(painel.exigeResultado)}
            >
              {enviando ? "Encerrando…" : "Encerrar votação"}
            </button>
          </div>
        )}

        {painel.tipo === "abrir" && (
          <div className="votacao-abrir">
            {candidatos.length === 0 ? (
              <p className="nota-terminal">
                <span>
                  Nenhuma proposição na pauta para pôr em votação. Inclua a matéria na pauta desta sessão antes
                  de abrir a votação.
                </span>
              </p>
            ) : (
              <>
                <div className="campo">
                  <label htmlFor="objeto">Objeto da votação (matéria da pauta)</label>
                  <select id="objeto" value={objetoId} onChange={(e) => setObjetoId(e.target.value)}>
                    <option value="">— escolha a matéria —</option>
                    {candidatos.map((c) => (
                      <option key={c.objetoId} value={c.objetoId}>
                        {c.sigla ? `${c.sigla} · ${nomeFase(c.fase)}` : `${nomeFase(c.fase)} · item ${c.ordem}`}
                      </option>
                    ))}
                  </select>
                </div>

                <fieldset className="campo-radio">
                  <legend>Modalidade</legend>
                  {MODALIDADES.map((m) => (
                    <label key={m.valor} htmlFor={`mod-${m.valor}`} title={m.descricao}>
                      <input
                        id={`mod-${m.valor}`}
                        type="radio"
                        name="modalidade"
                        checked={modalidade === m.valor}
                        onChange={() => setModalidade(m.valor)}
                      />
                      {m.rotulo}
                    </label>
                  ))}
                </fieldset>

                <div className="campo">
                  <label htmlFor="quorum">Quórum exigido</label>
                  <select id="quorum" value={quorumTipo} onChange={(e) => setQuorumTipo(e.target.value as QuorumTipo)}>
                    {QUORUNS.map((q) => (
                      <option key={q.valor} value={q.valor}>
                        {rotuloQuorum(q.valor)}
                      </option>
                    ))}
                  </select>
                </div>

                <button type="button" className="btn btn-primaria" disabled={enviando || estado !== "pronto"} onClick={onAbrir}>
                  {enviando ? "Abrindo…" : "Abrir votação"}
                </button>
              </>
            )}
          </div>
        )}
      </div>
    </section>
  );
}
