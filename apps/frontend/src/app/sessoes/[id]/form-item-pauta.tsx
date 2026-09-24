"use client";

// Formulário de INCLUIR ITEM NA PAUTA (docs/23, Fatia 1) — o mesmo na montagem (/pauta-convocacao) e no item
// extrapauta do cockpit (/sessoes/:id/conduzir). Dois caminhos, espelhando a FK-por-tipo do backend
// (`AdicionarItemPauta`): MATÉRIA (proposição do acervo, achada por busca) ou item de TEXTO (leitura,
// comunicado, homenagem). Quem chama injeta `onIncluir` (a escrita de useEditarPauta) e recarrega a pauta.
//
// A busca só monta o hook de proposições DEPOIS que o operador busca algo (`ResultadosBusca` é filho montado
// sob demanda) — sem isso, abrir o formulário já dispararia a listagem inteira do acervo.

import { useState } from "react";
import { useProposicoes } from "@/lib/use-proposicoes";
import { formatarNumeroProposicao } from "@/lib/proposicoes-vista";
import { FASES_DO_RITO, TIPOS_ITEM_TEXTO } from "@/lib/pauta-convocacao-vista";
import type { FasePauta, NovoItemPauta, ResultadoPauta, TipoItemTexto } from "@/lib/use-editar-pauta";
import type { ProposicaoResumoOut } from "@/lib/contrato-legislativo.gen";
import "./form-item-pauta.css";

type Tipo = "proposicao" | TipoItemTexto;

/** Teto de `texto-descricao` na borda do backend (`max-texto` de sessoes/adapters/in/pauta.clj). */
const MAX_TEXTO = 2000;

interface Props {
  token: string | null;
  faseInicial: FasePauta;
  /** Matérias já na pauta — aparecem na busca, mas não podem ser escolhidas de novo. */
  idsNaPauta: Set<string>;
  enviando: boolean;
  onIncluir: (novo: NovoItemPauta) => Promise<ResultadoPauta>;
  onCancelar: () => void;
  /** Rótulo do botão de envio ("Incluir na pauta", "Incluir extrapauta"). */
  rotuloEnviar?: string;
}

export function FormItemPauta({ token, faseInicial, idsNaPauta, enviando, onIncluir, onCancelar, rotuloEnviar = "Incluir na pauta" }: Props) {
  const [tipo, setTipo] = useState<Tipo>("proposicao");
  const [fase, setFase] = useState<FasePauta>(faseInicial);
  const [texto, setTexto] = useState("");
  const [buscaBruta, setBuscaBruta] = useState("");
  const [termo, setTermo] = useState("");
  const [escolhida, setEscolhida] = useState<ProposicaoResumoOut | null>(null);
  const [erro, setErro] = useState<string | null>(null);

  async function enviar() {
    setErro(null);
    let novo: NovoItemPauta;
    if (tipo === "proposicao") {
      if (!escolhida) {
        setErro("Busque e escolha a matéria que entra na pauta.");
        return;
      }
      novo = { fase, tipoItem: "proposicao", proposicaoId: escolhida.id };
    } else {
      if (!texto.trim()) {
        setErro("Descreva o item antes de incluir.");
        return;
      }
      novo = { fase, tipoItem: tipo, textoDescricao: texto };
    }
    const r = await onIncluir(novo);
    if (!r.ok) setErro(r.erro);
  }

  return (
    <div className="form-item-pauta">
      <fieldset className="fip-tipo">
        <legend>O que entra</legend>
        <label>
          <input type="radio" name="fip-tipo" value="proposicao" checked={tipo === "proposicao"} onChange={() => setTipo("proposicao")} />
          Matéria
        </label>
        {TIPOS_ITEM_TEXTO.map((t) => (
          <label key={t.valor}>
            <input type="radio" name="fip-tipo" value={t.valor} checked={tipo === t.valor} onChange={() => setTipo(t.valor)} />
            {t.rotulo}
          </label>
        ))}
      </fieldset>

      <div className="fip-campo">
        <label htmlFor="fip-fase">Fase da sessão</label>
        <select id="fip-fase" value={fase} onChange={(e) => setFase(e.target.value as FasePauta)}>
          {FASES_DO_RITO.map((f) => (
            <option key={f.fase} value={f.fase}>
              {f.titulo}
            </option>
          ))}
        </select>
      </div>

      {tipo === "proposicao" ? (
        <div className="fip-materia">
          {escolhida ? (
            <div className="fip-escolhida">
              <div>
                <span className="num">{formatarNumeroProposicao(escolhida.tipo, escolhida.sequencial, escolhida.ano)}</span>
                <p>{escolhida.ementa}</p>
              </div>
              <button type="button" className="btn btn-fantasma btn-mini" onClick={() => setEscolhida(null)}>
                Trocar
              </button>
            </div>
          ) : (
            <>
              <form
                className="fip-busca"
                role="search"
                onSubmit={(e) => {
                  e.preventDefault();
                  setTermo(buscaBruta.trim());
                }}
              >
                <div className="fip-campo">
                  <label htmlFor="fip-busca">Buscar matéria</label>
                  <input
                    id="fip-busca"
                    type="search"
                    value={buscaBruta}
                    placeholder="Palavra da ementa ou número"
                    onChange={(e) => setBuscaBruta(e.target.value)}
                  />
                </div>
                <button type="submit" className="btn btn-contorno btn-mini">
                  Buscar
                </button>
              </form>
              {termo && <ResultadosBusca token={token} termo={termo} idsNaPauta={idsNaPauta} onEscolher={setEscolhida} />}
            </>
          )}
        </div>
      ) : (
        <div className="fip-campo">
          <label htmlFor="fip-texto">Descrição do item</label>
          <textarea
            id="fip-texto"
            rows={3}
            maxLength={MAX_TEXTO}
            value={texto}
            placeholder={tipo === "leitura" ? "Ex.: Leitura e aprovação da ata da sessão anterior" : undefined}
            onChange={(e) => setTexto(e.target.value)}
          />
        </div>
      )}

      {erro && (
        <p className="fip-erro" role="alert">
          {erro}
        </p>
      )}

      <div className="fip-acoes">
        <button type="button" className="btn btn-primaria btn-mini" disabled={enviando} onClick={() => void enviar()}>
          {enviando ? "Incluindo…" : rotuloEnviar}
        </button>
        <button type="button" className="btn btn-fantasma btn-mini" disabled={enviando} onClick={onCancelar}>
          Cancelar
        </button>
      </div>
    </div>
  );
}

function ResultadosBusca({
  token,
  termo,
  idsNaPauta,
  onEscolher,
}: {
  token: string | null;
  termo: string;
  idsNaPauta: Set<string>;
  onEscolher: (p: ProposicaoResumoOut) => void;
}) {
  const { dados, estado } = useProposicoes(token, {
    busca: termo,
    pagina: 1,
    tamanho: 8,
    ordenarPor: "atualizado_em",
    ordenarDir: "desc",
  });
  if (estado === "carregando") return <p role="status" className="fip-nota">Buscando…</p>;
  if (estado === "erro") return <p className="fip-nota">Não foi possível buscar agora. Tente de novo.</p>;
  const itens = dados?.itens ?? [];
  if (itens.length === 0) return <p className="fip-nota">Nenhuma matéria encontrada para “{termo}”.</p>;
  return (
    <ul className="fip-resultados" aria-label="Matérias encontradas">
      {itens.map((p) => {
        const jaNaPauta = idsNaPauta.has(p.id);
        const numero = formatarNumeroProposicao(p.tipo, p.sequencial, p.ano);
        return (
          <li key={p.id}>
            <div>
              <span className="num">{numero}</span>
              <p>{p.ementa}</p>
            </div>
            {jaNaPauta ? (
              <span className="fip-ja">Já na pauta</span>
            ) : (
              <button type="button" className="btn btn-contorno btn-mini" aria-label={`Escolher ${numero}`} onClick={() => onEscolher(p)}>
                Escolher
              </button>
            )}
          </li>
        );
      })}
      {(dados?.total ?? 0) > itens.length && (
        <li className="fip-nota">
          Mostrando {itens.length} de {dados?.total}. Refine a busca se a matéria não estiver aqui.
        </li>
      )}
    </ul>
  );
}
