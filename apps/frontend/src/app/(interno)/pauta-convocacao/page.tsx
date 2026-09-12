"use client";

// Pauta/convocação (Onda C Slice C2, servidor/secretário) — leitura da pauta (Expediente + Ordem do Dia)
// de uma sessão agendada + o artefato de convocação DERIVADO (não persistido). Escopo READ PURO: o backend
// não expõe `lock-version` em GET /sessoes/:id/pauta, então PATCH/DELETE de item (que exigem
// `lock-version` para o CAS) não podem ser montados corretamente pelo cliente nesta fatia — ver
// docs/superpowers/specs/2026-07-11-onda-c-slice2-pauta-convocacao-design.md, "Correção pós-investigação
// técnica". Nenhuma mutação de pauta aqui.
//
// GUARD DE PAPEL: as rotas consumidas (GET /paineis/sli/sessoes, GET /sessoes/:id[/pauta]) já são gated
// `exige-papel "secretario"` no backend (403 é a authz real). Este componente replica o guard client-side
// só por UX (mesmo padrão de GuardVereador em (vereador)/layout.tsx) — nenhuma outra rota (interno) hoje
// tem esse guard (proposições/tramitação/parecer são abertas a qualquer servidor autenticado); esta é a
// primeira, porque é a primeira rota (interno) restrita a um papel específico.

import { useState } from "react";
import { useAuth, usePapeis } from "@/lib/auth";
import { useSliSessoes } from "@/lib/use-sli-sessoes";
import { useSessaoPauta } from "@/lib/use-sessao-pauta";
import { useProposicoes } from "@/lib/use-proposicoes";
import {
  agruparPautaPorFase,
  avisoCorteSessoes,
  derivarConvocacao,
  derivarProntasForaDaPauta,
  formatarTipoSessao,
  formatarTituloSessao,
  indexarProposicoesPorId,
  resolverTituloItem,
  selecionarSessaoAlvo,
  sessoesAgendadas,
} from "@/lib/pauta-convocacao-vista";
import { formatarData, formatarHora } from "@/lib/formatar-data";
import { formatarNumeroProposicao } from "@/lib/proposicoes-vista";
import { TopoInterno } from "../topo";
import "./pauta-convocacao.css";

export default function PaginaPautaConvocacao() {
  const { token } = useAuth();
  const { papeis, estado } = usePapeis();
  // Modo real: segura enquanto /eu não respondeu (evita piscar "Acesso restrito" antes da resposta — mesmo
  // racional de GuardVereador em (vereador)/layout.tsx).
  if (estado === "carregando") return null;
  if (!papeis.includes("secretario")) {
    return (
      <main className="acesso-restrito">
        <h1>Acesso restrito</h1>
        <p>Esta área é exclusiva da secretaria legislativa.</p>
      </main>
    );
  }
  return <ConteudoPautaConvocacao token={token} />;
}

function ConteudoPautaConvocacao({ token }: { token: string | null }) {
  const { sessoes, sessoesTotal, estado: estadoSessoes } = useSliSessoes(token);
  const [escolhidaId, setEscolhidaId] = useState<string | null>(null);
  const alvo = selecionarSessaoAlvo(sessoes ?? [], escolhidaId);
  const { sessao, pauta, estado: estadoDetalhe } = useSessaoPauta(token, alvo?.sessaoId ?? null);
  const { dados: proposicoesDados, estado: estadoProposicoes } = useProposicoes(token, {
    pagina: 1,
    tamanho: 100,
    ordenarPor: "atualizado_em",
    ordenarDir: "desc",
  });

  if (estadoSessoes === "erro" || estadoDetalhe === "erro") {
    return (
      <main className="tela-estado">
        <h1>Não foi possível carregar a pauta</h1>
        <p>Tente novamente em instantes.</p>
      </main>
    );
  }

  const agendadas = sessoesAgendadas(sessoes ?? []);
  // Fatia "truncamento-familia" sitio (a): sessoesTotal é o campo autoritativo do servidor — a comparação
  // nunca é uma dedução client-side (regra 4), e null (chamada ainda em voo) não deve acusar corte.
  const avisoCorte = sessoes && sessoesTotal != null ? avisoCorteSessoes(sessoes, sessoesTotal) : null;
  const grupos = agruparPautaPorFase(pauta);
  const proposicoesPorId = indexarProposicoesPorId(proposicoesDados?.itens ?? []);
  const rail = derivarProntasForaDaPauta(proposicoesDados?.itens ?? [], proposicoesDados?.total ?? 0, pauta);
  const convocacao = sessao ? derivarConvocacao(sessao, grupos) : null;

  return (
    <>
      <TopoInterno area="Pauta" />
      <main className="envelope">
        <div className="pg-cab">
          <div>
            <span className="eyebrow">Pauta</span>
            <h1>
              {sessao
                ? formatarTituloSessao(sessao)
                : !alvo && estadoSessoes === "pronto"
                  ? "Nenhuma sessão agendada"
                  : "Carregando…"}
            </h1>
            <p className="leitura">
              Leitura da pauta e da convocação. Montar, reordenar e convocar não fazem parte desta versão.
            </p>
          </div>
          {agendadas.length > 1 && (
            <span className="faceta">
              <label htmlFor="sel-sessao">Sessão</label>
              <select id="sel-sessao" value={alvo?.sessaoId ?? ""} onChange={(e) => setEscolhidaId(e.target.value)}>
                {agendadas.map((s) => (
                  <option key={s.sessaoId} value={s.sessaoId}>
                    {s.agendadaPara ? formatarData(s.agendadaPara) : s.sessaoId}
                  </option>
                ))}
              </select>
            </span>
          )}
        </div>

        {avisoCorte && <p className="aviso-corte">{avisoCorte}</p>}

        {estadoSessoes === "carregando" && <p role="status">Carregando…</p>}

        {estadoSessoes === "pronto" && !alvo && (
          <p className="col-vazia">Nenhuma sessão em estado &quot;agendada&quot; no momento.</p>
        )}

        {alvo && (
          <div className="balcao">
            <section aria-label="Pauta da sessão">
              {sessao && (
                <div className="card">
                  <h2>Dados da sessão</h2>
                  <dl className="dados-sessao">
                    <dt>Tipo</dt>
                    <dd>{formatarTipoSessao(sessao.tipoSessao)}</dd>
                    {sessao.agendadaPara && (
                      <>
                        <dt>Data</dt>
                        <dd>{formatarData(sessao.agendadaPara)}</dd>
                        <dt>Início</dt>
                        <dd>{formatarHora(sessao.agendadaPara)}</dd>
                      </>
                    )}
                  </dl>
                </div>
              )}

              <div className="card">
                <h2>Pauta</h2>
                <p className="ajuda">Leitura da ordem definida. Reordenar, adicionar e remover não fazem parte desta versão.</p>
                {estadoDetalhe === "carregando" && <p role="status">Carregando…</p>}
                {estadoDetalhe === "pronto" &&
                  grupos.map((grupo) => (
                    <div className="grupo" key={grupo.chave}>
                      <div className="grupo-cab">
                        <h3>{grupo.titulo}</h3>
                        <span className="cont">
                          {grupo.itens.length} {grupo.itens.length === 1 ? "item" : "itens"}
                        </span>
                      </div>
                      {grupo.itens.length === 0 && <p className="col-vazia">Nenhum item.</p>}
                      {grupo.itens.map((item) => {
                        const titulo = resolverTituloItem(item, proposicoesPorId);
                        return (
                          <div className="pauta-item" key={item.id}>
                            <span className="ordem-n">{item.ordem}</span>
                            <div className="pi-mid">
                              {titulo.numero && <span className="num">{titulo.numero}</span>}
                              <h4>{titulo.rotulo}</h4>
                            </div>
                          </div>
                        );
                      })}
                    </div>
                  ))}
              </div>
            </section>

            <aside className="rail" aria-label="Disponíveis e convocação">
              <div className="card disp">
                <h3>Prontas, fora da pauta</h3>
                {estadoProposicoes === "carregando" && <p role="status">Carregando…</p>}
                {estadoProposicoes === "erro" && <p className="col-vazia">Não foi possível carregar.</p>}
                {estadoProposicoes === "pronto" && rail.itens.length === 0 && (
                  <p className="col-vazia">Nenhuma matéria pronta fora da pauta.</p>
                )}
                {rail.itens.map((p) => (
                  <div className="disp-item" key={p.id}>
                    <div className="di-mid">
                      <span className="num">{formatarNumeroProposicao(p.tipo, p.sequencial, p.ano)}</span>
                      <p>{p.ementa}</p>
                    </div>
                  </div>
                ))}
                {rail.truncado && (
                  <p className="rail-truncado">
                    Mostrando as {proposicoesDados?.itens.length ?? 0} matérias mais recentes de{" "}
                    {proposicoesDados?.total ?? 0}.
                  </p>
                )}
              </div>

              {convocacao && (
                <div className="conv">
                  <h3>Convocação</h3>
                  <div className="edital-cab">{convocacao.tituloEdital}</div>
                  <dl>
                    <dt>Data</dt>
                    <dd>
                      <span className="mono">{convocacao.data}</span> · {convocacao.diaSemana}
                    </dd>
                    <dt>Início</dt>
                    <dd>
                      <span className="mono">{convocacao.hora}</span>
                    </dd>
                  </dl>
                  <p className="antec">
                    <span className="tag">[Regimento]</span> Deve ser publicada com a antecedência mínima
                    prevista no Regimento Interno.
                  </p>
                  <p className="dest">
                    {convocacao.contagemExpediente} no expediente · {convocacao.contagemOrdemDoDia} na ordem
                    do dia.
                  </p>
                </div>
              )}
            </aside>
          </div>
        )}
      </main>
    </>
  );
}
