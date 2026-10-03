"use client";

// O bloco da AUDIÊNCIA PÚBLICA no "Agendar sessão" (ADR-0021 A1): aparece só quando o tipo é `audiencia_publica`, e
// é obrigatório nesse caso. Comissão que promove (as comissões da Casa — o servidor recusa a que não for vigente),
// tema, local, matéria relacionada (opcional, achada por busca), finalidade, o quadrimestre quando é a audiência de
// metas fiscais da LRF, e o tempo único de fala. Controlado: o estado mora no formulário, a validação em
// `agendar-sessao-vista.ts`.
//
// A busca da matéria NÃO é um <form> (este bloco já mora dentro do formulário de agendar; form dentro de form é HTML
// inválido): Enter no campo e o botão "Buscar" fazem a mesma coisa.

import { useState } from "react";
import { useComissoes } from "@/lib/use-comissoes";
import { useProposicoes } from "@/lib/use-proposicoes";
import { formatarNumeroProposicao } from "@/lib/proposicoes-vista";
import { FINALIDADES, QUADRIMESTRES } from "@/lib/contrato-audiencia";
import { LIMITE_TEMA, TEMPO_FALA_MAX, TEMPO_FALA_MIN } from "@/lib/audiencia-vista";
import type { FormAudiencia } from "@/lib/agendar-sessao-vista";
import type { ProposicaoResumoOut } from "@/lib/contrato-legislativo.gen";

type Props = {
  token: string | null;
  valor: FormAudiencia;
  onChange: (v: FormAudiencia) => void;
};

export function BlocoAudiencia({ token, valor, onChange }: Props) {
  const comissoes = useComissoes(token, true);
  const [escolhida, setEscolhida] = useState<ProposicaoResumoOut | null>(null);
  const mudar = (parcial: Partial<FormAudiencia>) => onChange({ ...valor, ...parcial });

  return (
    <fieldset className="bloco-audiencia">
      <legend>Audiência pública</legend>
      <p className="ba-ajuda">
        A audiência não delibera e não exige quórum. O cidadão se inscreve para falar pelo portal, com o gov.br; a Mesa
        também inscreve no dia quem estiver presente.
      </p>

      <div className="campo">
        <label htmlFor="aud-comissao">Comissão que promove</label>
        {comissoes.fase === "erro" ? (
          <p role="alert" className="erro-inline">
            {comissoes.mensagem}
          </p>
        ) : (
          <select
            id="aud-comissao"
            value={valor.comissaoId}
            disabled={comissoes.fase === "carregando"}
            onChange={(e) => mudar({ comissaoId: e.target.value })}
          >
            <option value="">{comissoes.fase === "carregando" ? "Carregando as comissões…" : "— escolha a comissão —"}</option>
            {comissoes.fase === "pronto" &&
              comissoes.comissoes.map((c) => (
                <option key={c.id} value={c.id}>
                  {c.nome}
                </option>
              ))}
          </select>
        )}
      </div>

      <div className="campo">
        <label htmlFor="aud-tema">Tema</label>
        <input
          id="aud-tema"
          type="text"
          value={valor.tema}
          maxLength={LIMITE_TEMA}
          placeholder="Ex.: Plano municipal de mobilidade urbana"
          onChange={(e) => mudar({ tema: e.target.value })}
        />
      </div>

      <div className="campo">
        <label htmlFor="aud-finalidade">Finalidade</label>
        <select
          id="aud-finalidade"
          value={valor.finalidade}
          onChange={(e) => mudar({ finalidade: e.target.value as FormAudiencia["finalidade"] })}
        >
          {FINALIDADES.map((f) => (
            <option key={f.valor} value={f.valor}>
              {f.rotulo}
            </option>
          ))}
        </select>
      </div>

      {valor.finalidade === "metas_fiscais" && (
        <fieldset className="ba-referencia">
          <legend>Quadrimestre de referência</legend>
          <p className="ba-ajuda">
            A LRF (art. 9º, § 4º) pede que o Executivo demonstre as metas fiscais do quadrimestre até o fim de maio,
            setembro e fevereiro. 1º quadrimestre: jan–abr · 2º: mai–ago · 3º: set–dez.
          </p>
          <div className="ba-linha">
            <div className="campo">
              <label htmlFor="aud-ref-ano">Ano</label>
              <input
                id="aud-ref-ano"
                type="text"
                inputMode="numeric"
                maxLength={4}
                placeholder="AAAA"
                value={valor.referenciaAno}
                onChange={(e) => mudar({ referenciaAno: e.target.value })}
              />
            </div>
            <div className="campo">
              <label htmlFor="aud-ref-quad">Quadrimestre</label>
              <select
                id="aud-ref-quad"
                value={valor.referenciaQuadrimestre}
                onChange={(e) => mudar({ referenciaQuadrimestre: e.target.value })}
              >
                <option value="">— escolha —</option>
                {QUADRIMESTRES.map((q) => (
                  <option key={q.valor} value={q.valor}>
                    {q.rotulo} ({q.meses})
                  </option>
                ))}
              </select>
            </div>
          </div>
        </fieldset>
      )}

      <div className="ba-linha">
        <div className="campo">
          <label htmlFor="aud-local">Local (opcional)</label>
          <input
            id="aud-local"
            type="text"
            value={valor.local}
            placeholder="Ex.: Plenário principal"
            onChange={(e) => mudar({ local: e.target.value })}
          />
        </div>
        <div className="campo">
          <label htmlFor="aud-tempo">Tempo de fala (minutos)</label>
          <input
            id="aud-tempo"
            type="number"
            min={TEMPO_FALA_MIN}
            max={TEMPO_FALA_MAX}
            step={1}
            value={valor.tempoFalaMinutos}
            aria-describedby="aud-tempo-dica"
            onChange={(e) => mudar({ tempoFalaMinutos: e.target.value })}
          />
          <span id="aud-tempo-dica" className="ba-dica">
            O mesmo para cada pessoa. A Mesa pode ajustar depois.
          </span>
        </div>
      </div>

      <MateriaRelacionada
        token={token}
        escolhida={escolhida}
        onEscolher={(p) => {
          setEscolhida(p);
          mudar({ proposicaoId: p?.id ?? "" });
        }}
      />
    </fieldset>
  );
}

function MateriaRelacionada({
  token,
  escolhida,
  onEscolher,
}: {
  token: string | null;
  escolhida: ProposicaoResumoOut | null;
  onEscolher: (p: ProposicaoResumoOut | null) => void;
}) {
  const [bruta, setBruta] = useState("");
  const [termo, setTermo] = useState("");
  const buscar = () => setTermo(bruta.trim());

  if (escolhida) {
    return (
      <div className="campo">
        <span className="ba-rotulo">Matéria relacionada</span>
        <div className="ba-escolhida">
          <div>
            <b>{formatarNumeroProposicao(escolhida.tipo, escolhida.sequencial, escolhida.ano)}</b>
            <p>{escolhida.ementa}</p>
          </div>
          <button type="button" className="btn btn-fantasma btn-mini" onClick={() => onEscolher(null)}>
            Tirar
          </button>
        </div>
      </div>
    );
  }
  return (
    <div className="campo">
      <label htmlFor="aud-materia">Matéria relacionada (opcional)</label>
      <div className="ba-busca">
        <input
          id="aud-materia"
          type="search"
          value={bruta}
          placeholder="Palavra da ementa ou número"
          onChange={(e) => setBruta(e.target.value)}
          onKeyDown={(e) => {
            if (e.key === "Enter") {
              e.preventDefault();
              buscar();
            }
          }}
        />
        <button type="button" className="btn btn-contorno btn-mini" onClick={buscar}>
          Buscar
        </button>
      </div>
      {termo && <Resultados token={token} termo={termo} onEscolher={onEscolher} />}
    </div>
  );
}

function Resultados({ token, termo, onEscolher }: { token: string | null; termo: string; onEscolher: (p: ProposicaoResumoOut) => void }) {
  const { dados, estado } = useProposicoes(token, { busca: termo, pagina: 1, tamanho: 6, ordenarPor: "atualizado_em", ordenarDir: "desc" });
  if (estado === "carregando") return <p role="status" className="ba-dica">Buscando…</p>;
  if (estado === "erro") return <p className="ba-dica">Não foi possível buscar agora. Tente de novo.</p>;
  const itens = dados?.itens ?? [];
  if (itens.length === 0) return <p className="ba-dica">Nenhuma matéria encontrada para “{termo}”.</p>;
  return (
    <ul className="ba-resultados" aria-label="Matérias encontradas">
      {itens.map((p) => {
        const numero = formatarNumeroProposicao(p.tipo, p.sequencial, p.ano);
        return (
          <li key={p.id}>
            <div>
              <b>{numero}</b>
              <p>{p.ementa}</p>
            </div>
            <button type="button" className="btn btn-contorno btn-mini" aria-label={`Escolher ${numero}`} onClick={() => onEscolher(p)}>
              Escolher
            </button>
          </li>
        );
      })}
    </ul>
  );
}
