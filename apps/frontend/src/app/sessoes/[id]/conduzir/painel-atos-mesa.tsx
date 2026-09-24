"use client";

// ATOS DA MESA no cockpit (docs/23 Fatia 2): registrar a decisão sobre QUESTÃO DE ORDEM e os INCIDENTES
// processuais (pedido de vista, verificação de votação, urgência, votação em bloco), e ver o que já foi
// registrado nesta sessão. O backend já tinha as duas escritas; faltavam o botão e uma leitura.
//
// Autoria: quem DECIDE a questão de ordem é quem preside — o formulário pré-seleciona o Presidente da Mesa e o
// operador troca se for o vice em exercício. O registro fica em nome de quem clicou (o servidor injeta).
// As duas escritas são append-only: corrigir é registrar de novo, e a tela diz isso.

import { useEffect, useState } from "react";
import { useAtosMesa } from "@/lib/use-atos-mesa";
import {
  RESULTADOS_INCIDENTE,
  TIPOS_INCIDENTE,
  linhaDoTempo,
  opcoesDePresidencia,
  presidentePadrao,
  type ResultadoIncidente,
  type TipoIncidente,
} from "@/lib/atos-mesa-vista";
import { nomeDoMembro } from "@/lib/tribuna-mesa-vista";
import { formatarHora } from "@/lib/formatar-data";

export interface MateriaDaPauta {
  proposicaoId: string;
  rotulo: string;
}

interface Props {
  sessaoId: string;
  token: string | null;
  /** Sessão aberta/suspensa aceita registro; fechada só mostra a lista (é história da ata). */
  podeRegistrar: boolean;
  materias: MateriaDaPauta[];
}

type Aberto = "questao" | "incidente" | null;

const CHIP_RESULTADO: Record<ResultadoIncidente, string> = {
  deferido: "chip-ok",
  indeferido: "chip-risco",
  prejudicado: "chip-neutro",
  retirado: "chip-neutro",
};

export function PainelAtosMesa({ sessaoId, token, podeRegistrar, materias }: Props) {
  const io = useAtosMesa(sessaoId, token);
  const [aberto, setAberto] = useState<Aberto>(null);
  const [aviso, setAviso] = useState<string | null>(null);
  const atos = linhaDoTempo(io.atos, io.membros);

  function abrir(qual: Aberto) {
    setAviso(null);
    setAberto(qual);
  }

  function concluir(msg: string) {
    setAberto(null);
    setAviso(msg);
  }

  return (
    <section className="bloco atos-mesa" aria-labelledby="atos-titulo">
      <div className="bloco-cabeca">
        <h2 id="atos-titulo">Atos da Mesa</h2>
        {atos.length > 0 && (
          <span className="eyebrow">
            {atos.length} {atos.length === 1 ? "registrado" : "registrados"}
          </span>
        )}
      </div>
      <div className="bloco-corpo">
        {aviso && (
          <p role="status" className="aviso-ok">
            {aviso}
          </p>
        )}

        {podeRegistrar && aberto === null && (
          <div className="am-botoes">
            <button type="button" className="btn btn-contorno btn-mini" onClick={() => abrir("questao")}>
              Registrar questão de ordem
            </button>
            <button type="button" className="btn btn-contorno btn-mini" onClick={() => abrir("incidente")}>
              Registrar incidente
            </button>
          </div>
        )}

        {podeRegistrar && aberto === "questao" && (
          <FormQuestaoDeOrdem io={io} onCancelar={() => setAberto(null)} onConcluir={() => concluir("Decisão da Mesa registrada.")} />
        )}
        {podeRegistrar && aberto === "incidente" && (
          <FormIncidente
            io={io}
            materias={materias}
            onCancelar={() => setAberto(null)}
            onConcluir={() => concluir("Incidente registrado.")}
          />
        )}

        {io.estado === "erro" && (
          <p className="nota-mesa">
            <span>Não foi possível carregar os atos desta sessão agora.</span>
          </p>
        )}
        {io.estado === "pronto" && atos.length === 0 && (
          <p className="nota-mesa">
            <span>Nenhum ato registrado nesta sessão.</span>
          </p>
        )}
        {atos.length > 0 && (
          <ol className="am-lista" aria-label="Atos registrados, do mais recente ao mais antigo">
            {atos.map((a) => (
              <li key={`${a.natureza}-${a.id}`} className="am-item">
                <div className="am-topo">
                  <span className="am-hora">{formatarHora(a.quando)}</span>
                  <b className="am-titulo">{a.titulo}</b>
                  {a.resultado && <span className={`chip ${CHIP_RESULTADO[a.resultado]}`}>{a.desfecho}</span>}
                </div>
                <p className="am-texto">{a.texto}</p>
                {a.natureza === "decisao" && (
                  <p className="am-desfecho">
                    <span>Decisão:</span> {a.desfecho}
                  </p>
                )}
                {a.nota && <p className="am-nota">{a.nota}</p>}
                {a.pessoa && <p className="am-pessoa">{a.pessoa}</p>}
              </li>
            ))}
          </ol>
        )}
        {podeRegistrar && (
          <p className="nota-mesa">
            <span>Os atos vão para a ata e não se apagam: para corrigir, registre um novo ato.</span>
          </p>
        )}
      </div>
    </section>
  );
}

// ---------------------------------------------------------------- questão de ordem

type IO = ReturnType<typeof useAtosMesa>;

function FormQuestaoDeOrdem({ io, onCancelar, onConcluir }: { io: IO; onCancelar: () => void; onConcluir: () => void }) {
  const opcoes = opcoesDePresidencia(io.membros);
  const [presidenteId, setPresidenteId] = useState<string>(() => presidentePadrao(io.membros) ?? "");
  const [questao, setQuestao] = useState("");
  const [decisao, setDecisao] = useState("");
  const [fundamentacao, setFundamentacao] = useState("");
  const [fala, setFala] = useState<{ falaId: string; oradorId: string } | null>(null);
  const [vincular, setVincular] = useState(true);
  const [erro, setErro] = useState<string | null>(null);

  // A composição pode chegar depois do formulário abrir: pré-seleciona o Presidente assim que ela chegar,
  // sem sobrescrever uma escolha já feita (ajuste durante o render — mesmo padrão dos hooks do projeto).
  const padrao = presidentePadrao(io.membros);
  if (!presidenteId && padrao) setPresidenteId(padrao);

  // A fala em curso é lida uma vez, na abertura do formulário (para oferecer o vínculo).
  const { buscarFalaAtual } = io;
  useEffect(() => {
    let vivo = true;
    void buscarFalaAtual().then((f) => {
      if (vivo) setFala(f);
    });
    return () => {
      vivo = false;
    };
  }, [buscarFalaAtual]);

  async function enviar() {
    setErro(null);
    if (!presidenteId) return setErro("Escolha quem presidiu e decidiu.");
    if (!questao.trim()) return setErro("Descreva a questão de ordem levantada.");
    if (!decisao.trim()) return setErro("Registre a decisão da Mesa.");
    const r = await io.registrarDecisao({
      questao,
      decisao,
      presidenteId,
      fundamentacao,
      falaId: fala && vincular ? fala.falaId : null,
    });
    if (r.ok) onConcluir();
    else setErro(r.erro);
  }

  return (
    <div className="am-form" role="group" aria-labelledby="form-questao-titulo">
      <h3 id="form-questao-titulo" className="tribuna-sub">
        Questão de ordem
      </h3>
      <p className="am-ajuda">Quem decide é quem preside a sessão; o registro fica em seu nome.</p>
      <div className="campo">
        <label htmlFor="ato-presidente">Quem presidiu e decidiu</label>
        <select id="ato-presidente" value={presidenteId} onChange={(e) => setPresidenteId(e.target.value)}>
          <option value="">— escolha —</option>
          {opcoes.map((o) => (
            <option key={o.vereadorId} value={o.vereadorId}>
              {o.rotulo}
            </option>
          ))}
        </select>
      </div>
      <div className="campo">
        <label htmlFor="ato-questao">Questão levantada</label>
        <textarea id="ato-questao" rows={2} maxLength={4000} value={questao} onChange={(e) => setQuestao(e.target.value)} />
      </div>
      <div className="campo">
        <label htmlFor="ato-decisao">Decisão da Mesa</label>
        <textarea id="ato-decisao" rows={2} maxLength={4000} value={decisao} onChange={(e) => setDecisao(e.target.value)} />
      </div>
      <div className="campo">
        <label htmlFor="ato-fundamentacao">Fundamentação (opcional)</label>
        <input id="ato-fundamentacao" type="text" maxLength={2000} value={fundamentacao} placeholder="Ex.: art. 90 do Regimento Interno" onChange={(e) => setFundamentacao(e.target.value)} />
      </div>
      {fala && (
        <label className="am-check">
          <input type="checkbox" checked={vincular} onChange={(e) => setVincular(e.target.checked)} />
          Vincular à fala em curso ({nomeDoMembro(fala.oradorId, io.membros)})
        </label>
      )}
      {erro && (
        <p role="alert" className="erro-inline">
          {erro}
        </p>
      )}
      <div className="am-acoes">
        <button type="button" className="btn btn-primaria btn-mini" disabled={io.enviando} onClick={() => void enviar()}>
          {io.enviando ? "Registrando…" : "Registrar decisão"}
        </button>
        <button type="button" className="btn btn-fantasma btn-mini" disabled={io.enviando} onClick={onCancelar}>
          Cancelar
        </button>
      </div>
    </div>
  );
}

// ---------------------------------------------------------------- incidente

function FormIncidente({
  io,
  materias,
  onCancelar,
  onConcluir,
}: {
  io: IO;
  materias: MateriaDaPauta[];
  onCancelar: () => void;
  onConcluir: () => void;
}) {
  const [tipo, setTipo] = useState<TipoIncidente>("pedido_vista");
  const [resultado, setResultado] = useState<ResultadoIncidente>("deferido");
  const [descricao, setDescricao] = useState("");
  const [proposicaoId, setProposicaoId] = useState("");
  const [requerenteId, setRequerenteId] = useState("");
  const [deliberacao, setDeliberacao] = useState("");
  const [erro, setErro] = useState<string | null>(null);
  const requerentes = opcoesDePresidencia(io.membros);

  async function enviar() {
    setErro(null);
    if (!descricao.trim()) return setErro("Descreva o incidente.");
    const r = await io.registrarIncidente({
      tipo,
      resultado,
      descricao,
      proposicaoId: proposicaoId || null,
      requerenteId: requerenteId || null,
      deliberacao,
    });
    if (r.ok) onConcluir();
    else setErro(r.erro);
  }

  return (
    <div className="am-form" role="group" aria-labelledby="form-incidente-titulo">
      <h3 id="form-incidente-titulo" className="tribuna-sub">
        Incidente
      </h3>
      <div className="campo">
        <label htmlFor="inc-tipo">Tipo</label>
        <select id="inc-tipo" value={tipo} onChange={(e) => setTipo(e.target.value as TipoIncidente)}>
          {TIPOS_INCIDENTE.map((t) => (
            <option key={t.valor} value={t.valor}>
              {t.rotulo}
            </option>
          ))}
        </select>
      </div>
      <fieldset className="campo-radio">
        <legend>Resultado</legend>
        {RESULTADOS_INCIDENTE.map((r) => (
          <label key={r.valor}>
            <input type="radio" name="inc-resultado" checked={resultado === r.valor} onChange={() => setResultado(r.valor)} />
            {r.rotulo}
          </label>
        ))}
      </fieldset>
      <div className="campo">
        <label htmlFor="inc-descricao">Descrição</label>
        <textarea
          id="inc-descricao"
          rows={2}
          maxLength={4096}
          value={descricao}
          placeholder="Ex.: Pedido de vista do PL 22/2026 pelo vereador autor da emenda"
          onChange={(e) => setDescricao(e.target.value)}
        />
      </div>
      {materias.length > 0 && (
        <div className="campo">
          <label htmlFor="inc-materia">Matéria atingida (opcional)</label>
          <select id="inc-materia" value={proposicaoId} onChange={(e) => setProposicaoId(e.target.value)}>
            <option value="">— nenhuma —</option>
            {materias.map((m) => (
              <option key={m.proposicaoId} value={m.proposicaoId}>
                {m.rotulo}
              </option>
            ))}
          </select>
        </div>
      )}
      {requerentes.length > 0 && (
        <div className="campo">
          <label htmlFor="inc-requerente">Quem requereu (opcional)</label>
          <select id="inc-requerente" value={requerenteId} onChange={(e) => setRequerenteId(e.target.value)}>
            <option value="">— não informado —</option>
            {requerentes.map((o) => (
              <option key={o.vereadorId} value={o.vereadorId}>
                {o.rotulo}
              </option>
            ))}
          </select>
        </div>
      )}
      <div className="campo">
        <label htmlFor="inc-deliberacao">Deliberação (opcional)</label>
        <input id="inc-deliberacao" type="text" maxLength={4096} value={deliberacao} placeholder="Ex.: vista por uma sessão" onChange={(e) => setDeliberacao(e.target.value)} />
      </div>
      {erro && (
        <p role="alert" className="erro-inline">
          {erro}
        </p>
      )}
      <div className="am-acoes">
        <button type="button" className="btn btn-primaria btn-mini" disabled={io.enviando} onClick={() => void enviar()}>
          {io.enviando ? "Registrando…" : "Registrar incidente"}
        </button>
        <button type="button" className="btn btn-fantasma btn-mini" disabled={io.enviando} onClick={onCancelar}>
          Cancelar
        </button>
      </div>
    </div>
  );
}
