"use client";

// "Encaminhar às comissões" (ADR-0019, Eixo 6) — o caminho da matéria até o parecer de comissão, que era só semente. A
// secretaria escolhe de 1 a 10 comissões e, se já souber, o relator de cada uma (a determinação do presidente da
// comissão; sem relator, o parecer abre e o relator é designado depois, na aba Pareceres). O servidor abre UM parecer por
// comissão; se a comissão já tem parecer em andamento nesta matéria, mantém o existente e diz isso — nada é duplicado.
// Diálogo modal com foco preso, Esc fecha e o foco volta ao botão que o abriu. A autorização fina (só o presidente da
// comissão designa) fica para quando uma Casa pedir.

import { useEffect, useRef, useState } from "react";
import { useVereadores } from "@/lib/use-vereadores";
import {
  MAX_COMISSOES_POR_ENCAMINHAMENTO,
  encaminharAsComissoes,
  resumoDoEncaminhamento,
  useComissoes,
} from "@/lib/use-comissoes";
import type { ParecerAbertoOut } from "@/lib/contrato-juridico.gen";
import "./juridico-ficha.css";

const FOCAVEIS = 'button:not([disabled]), input:not([disabled]), select:not([disabled]), textarea:not([disabled]), a[href]';

export function EncaminharComissoes({ proposicaoId, token, aoFechar, aoEncaminhar }: {
  proposicaoId: string;
  token: string | null;
  aoFechar: () => void;
  aoEncaminhar: () => void;
}) {
  const comissoes = useComissoes(token, true);
  const vereadores = useVereadores(token);
  const [escolhidas, setEscolhidas] = useState<Record<string, string>>({}); // comissaoId -> relatorId ("" = sem relator)
  const [enviando, setEnviando] = useState(false);
  const [erro, setErro] = useState<string | null>(null);
  const [resultado, setResultado] = useState<ParecerAbertoOut[] | null>(null);
  const caixa = useRef<HTMLDivElement>(null);
  const totalEscolhidas = Object.keys(escolhidas).length;

  // foco preso: entra no diálogo ao abrir; Tab circula; Esc fecha (o foco volta a quem abriu, pelo pai)
  useEffect(() => {
    caixa.current?.querySelector<HTMLElement>(FOCAVEIS)?.focus();
  }, []);

  function aoTeclar(e: React.KeyboardEvent<HTMLDivElement>) {
    if (e.key === "Escape") {
      e.stopPropagation();
      aoFechar();
      return;
    }
    if (e.key !== "Tab" || !caixa.current) return;
    const itens = Array.from(caixa.current.querySelectorAll<HTMLElement>(FOCAVEIS));
    if (itens.length === 0) return;
    const primeiro = itens[0];
    const ultimo = itens[itens.length - 1];
    if (e.shiftKey && document.activeElement === primeiro) {
      e.preventDefault();
      ultimo.focus();
    } else if (!e.shiftKey && document.activeElement === ultimo) {
      e.preventDefault();
      primeiro.focus();
    }
  }

  function alternar(id: string) {
    setEscolhidas((atual) => {
      const { [id]: existente, ...resto } = atual;
      if (existente !== undefined) return resto;
      if (Object.keys(atual).length >= MAX_COMISSOES_POR_ENCAMINHAMENTO) return atual;
      return { ...atual, [id]: "" };
    });
  }

  async function enviar() {
    if (enviando || totalEscolhidas === 0) return;
    setEnviando(true);
    setErro(null);
    const destinos = Object.entries(escolhidas).map(([comissaoId, relatorId]) => ({ comissaoId, relatorId: relatorId || null }));
    const r = await encaminharAsComissoes(token, proposicaoId, destinos);
    setEnviando(false);
    if (r.ok) {
      setResultado(r.dado.pareceres);
      aoEncaminhar();
    } else setErro(r.mensagem);
  }

  const semVereadores = vereadores.estado !== "pronto";

  return (
    <div className="jf-scrim">
      <div ref={caixa} className="jf-dialogo" role="dialog" aria-modal="true" aria-labelledby="jf-enc-titulo" onKeyDown={aoTeclar}>
        <h3 id="jf-enc-titulo">Encaminhar às comissões</h3>

        {resultado ? (
          <>
            <p role="status" className="jf-ok">{resumoDoEncaminhamento(resultado)}</p>
            <p className="jf-dica">Falta designar o relator onde ainda não há: isso se faz na aba Pareceres desta ficha.</p>
            <div className="jf-acoes">
              <button type="button" className="btn btn-primaria" onClick={aoFechar}>Fechar</button>
            </div>
          </>
        ) : (
          <>
            <p className="jf-dica">
              Escolha as comissões que vão dar parecer sobre a matéria. O sistema abre um parecer por comissão; o relator é
              opcional agora e pode ser designado depois.
            </p>

            {comissoes.fase === "carregando" && <p role="status">Carregando as comissões…</p>}
            {comissoes.fase === "erro" && <p role="alert" className="jf-erro">{comissoes.mensagem}</p>}
            {comissoes.fase === "pronto" && comissoes.comissoes.length === 0 && (
              <p className="jf-dica">Esta Casa ainda não tem comissões cadastradas. Cadastre-as antes de encaminhar a matéria.</p>
            )}
            {comissoes.fase === "pronto" && comissoes.comissoes.length > 0 && (
              <fieldset className="jf-lista">
                <legend>Comissões</legend>
                {semVereadores && vereadores.estado === "erro" && (
                  <p className="jf-dica">Não foi possível carregar os vereadores: o relator poderá ser designado depois, na aba Pareceres.</p>
                )}
                {comissoes.comissoes.map((c) => {
                  const marcada = escolhidas[c.id] !== undefined;
                  const cheio = !marcada && totalEscolhidas >= MAX_COMISSOES_POR_ENCAMINHAMENTO;
                  return (
                    <div key={c.id} className="jf-comissao">
                      <label className="jf-check">
                        <input type="checkbox" checked={marcada} disabled={cheio} onChange={() => alternar(c.id)} />
                        <span>{c.nome}</span>
                      </label>
                      {marcada && (
                        <div className="jf-relator">
                          <label htmlFor={`jf-rel-${c.id}`}>Relator de {c.nome} (opcional)</label>
                          <select id={`jf-rel-${c.id}`} value={escolhidas[c.id]} disabled={vereadores.estado !== "pronto"}
                            onChange={(e) => setEscolhidas((atual) => ({ ...atual, [c.id]: e.target.value }))}>
                            <option value="">Designar depois</option>
                            {vereadores.dados.map((v) => (
                              <option key={v.id} value={v.id}>{v.nomeParlamentar ?? v.nome}</option>
                            ))}
                          </select>
                        </div>
                      )}
                    </div>
                  );
                })}
              </fieldset>
            )}
            {totalEscolhidas >= MAX_COMISSOES_POR_ENCAMINHAMENTO && (
              <p className="jf-dica">O limite é de {MAX_COMISSOES_POR_ENCAMINHAMENTO} comissões por encaminhamento.</p>
            )}

            {erro && <p role="alert" className="jf-erro">{erro}</p>}
            <div className="jf-acoes">
              <button type="button" className="btn btn-primaria" disabled={enviando || totalEscolhidas === 0} onClick={enviar}>
                {enviando ? "Encaminhando…" : totalEscolhidas > 1 ? `Encaminhar a ${totalEscolhidas} comissões` : "Encaminhar"}
              </button>
              <button type="button" className="btn btn-fantasma" disabled={enviando} onClick={aoFechar}>Cancelar</button>
            </div>
          </>
        )}
      </div>
    </div>
  );
}
