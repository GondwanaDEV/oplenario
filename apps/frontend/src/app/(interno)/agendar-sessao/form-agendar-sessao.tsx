"use client";

// FormAgendarSessao — o formulário de agendar sessão (recebe `token` por prop, testável). Lê as sessões
// existentes (use-sessoes) só para resolver o `sessao-legislativa-id` — não há endpoint de listagem de
// sessões legislativas, mas as sessões do período corrente compartilham a sua. Deriva opções/validação de
// `agendar-sessao-vista.ts` e escreve por `use-agendar-sessao.ts`. No sucesso, mostra a sessão criada com um
// link para conduzi-la.

import { useMemo, useState } from "react";
import Link from "next/link";
import { useSessoes } from "@/lib/use-sessoes";
import { useAgendarSessao } from "@/lib/use-agendar-sessao";
import { comToken } from "@/lib/nav";
import { nomeTipoSessao } from "@/lib/rotulos-sessao";
import type { SessaoOut } from "@/lib/contrato-sessoes.gen";
import {
  agendadaParaIso,
  sessoesLegislativasDisponiveis,
  validarAgendar,
  TIPOS_SESSAO,
  MODALIDADES_SESSAO,
} from "@/lib/agendar-sessao-vista";

export function FormAgendarSessao({ token }: { token: string | null }) {
  const { sessoes, estado: estadoSessoes } = useSessoes(token);
  const { agendar, estado: estadoAgendar } = useAgendarSessao(token);

  const legislativas = useMemo(() => sessoesLegislativasDisponiveis(sessoes ?? []), [sessoes]);

  const [sessaoLegislativaId, setSessaoLegislativaId] = useState("");
  const [tipoSessao, setTipoSessao] = useState("ordinaria");
  const [modalidade, setModalidade] = useState("");
  const [agendadaPara, setAgendadaPara] = useState("");
  const [erroAcao, setErroAcao] = useState<string | null>(null);
  const [criada, setCriada] = useState<SessaoOut | null>(null);

  // auto-seleciona a única sessão legislativa (o caso comum: um período corrente)
  const legislativaEfetiva = sessaoLegislativaId || (legislativas.length === 1 ? legislativas[0].id : "");

  const enviando = estadoAgendar === "enviando";

  async function onSubmit(e: React.FormEvent) {
    e.preventDefault();
    setErroAcao(null);
    const v = validarAgendar({ sessaoLegislativaId: legislativaEfetiva, tipoSessao });
    if (!v.ok) {
      setErroAcao(v.erro);
      return;
    }
    const r = await agendar({
      sessaoLegislativaId: legislativaEfetiva,
      tipoSessao,
      modalidade: modalidade || null,
      agendadaPara: agendadaParaIso(agendadaPara),
    });
    if (r.ok) {
      setCriada(r.sessao);
      setAgendadaPara("");
    } else {
      setErroAcao(r.erro);
    }
  }

  return (
    <main className="envelope agendar-sessao">
      <div className="cabecalho">
        <p className="eyebrow">Sessões</p>
        <h1>Agendar sessão</h1>
        <p className="subtitulo">
          Cria uma sessão no estado <b>agendada</b>. A abertura, a chamada e as votações são conduzidas depois,
          no comando da sessão.
        </p>
      </div>

      {criada && (
        <div className="criada" role="status">
          <p>
            <b>Sessão {nomeTipoSessao(criada.tipoSessao)} nº {criada.numeroSequencial}</b> agendada.
          </p>
          <div className="criada-acoes">
            <Link className="btn btn-primaria" href={comToken(`/sessoes/${criada.id}/conduzir`, token)}>
              Conduzir a sessão
            </Link>
            <button type="button" className="btn btn-fantasma" onClick={() => setCriada(null)}>
              Agendar outra
            </button>
          </div>
        </div>
      )}

      {!criada && (
        <form className="bloco form-agendar" onSubmit={onSubmit}>
          {estadoSessoes !== "erro" && legislativas.length === 0 && estadoSessoes === "pronto" && (
            <p className="nota-terminal">
              <span>
                Nenhuma sessão legislativa encontrada para esta Casa. A primeira sessão de um período depende
                do cadastro da sessão legislativa (fora deste formulário).
              </span>
            </p>
          )}

          {legislativas.length > 1 && (
            <div className="campo">
              <label htmlFor="legislativa">Sessão legislativa (período)</label>
              <select
                id="legislativa"
                value={legislativaEfetiva}
                onChange={(e) => setSessaoLegislativaId(e.target.value)}
              >
                <option value="">— escolha o período —</option>
                {legislativas.map((l) => (
                  <option key={l.id} value={l.id}>
                    {l.id.slice(0, 8)} · {l.sessoesCount} sessão(ões)
                  </option>
                ))}
              </select>
            </div>
          )}

          <div className="campo">
            <label htmlFor="tipo">Tipo de sessão</label>
            <select id="tipo" value={tipoSessao} onChange={(e) => setTipoSessao(e.target.value)}>
              {TIPOS_SESSAO.map((t) => (
                <option key={t.valor} value={t.valor}>
                  {t.rotulo}
                </option>
              ))}
            </select>
          </div>

          <div className="campo">
            <label htmlFor="modalidade">Modalidade (opcional)</label>
            <select id="modalidade" value={modalidade} onChange={(e) => setModalidade(e.target.value)}>
              <option value="">Usar o padrão da Casa</option>
              {MODALIDADES_SESSAO.map((m) => (
                <option key={m.valor} value={m.valor}>
                  {m.rotulo}
                </option>
              ))}
            </select>
          </div>

          <div className="campo">
            <label htmlFor="quando">Data e hora (opcional)</label>
            <input
              id="quando"
              type="datetime-local"
              value={agendadaPara}
              onChange={(e) => setAgendadaPara(e.target.value)}
            />
          </div>

          {erroAcao && (
            <p role="alert" className="erro-inline">
              {erroAcao}
            </p>
          )}

          <div className="form-acoes">
            <button
              type="submit"
              className="btn btn-primaria"
              disabled={enviando || (legislativas.length === 0 && estadoSessoes === "pronto")}
            >
              {enviando ? "Agendando…" : "Agendar sessão"}
            </button>
          </div>
        </form>
      )}
    </main>
  );
}
