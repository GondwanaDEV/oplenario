"use client";

// A chamada — folha de presença da sessão (§22.6 eixo C, Etapa 3B fatia 3) — porta
// produto/design-system/o-plenario/telas/chamada.html ligada às rotas reais via `useChamada` (fatia 2) e ao
// view-model puro de `chamada-vista.ts` (fatia 1). Este arquivo é só orquestração + apresentação: nenhuma
// regra de ordenação/agrupamento/diff/otimista é reimplementada aqui — tudo isso vem importado.
//
// A TIRA DE DEMONSTRAÇÃO do HTML (`.demo-strip`) não tem equivalente aqui — era andaime de design para
// simular os 4 estados da folha. Os 4 estados agora são DERIVADOS de dado real (ver `Chamada` abaixo):
//   - "em curso" ⇄ "registrada": `dados.chamadasConduzidas.length` (0 = em curso; ≥1 = registrada/ao vivo).
//   - "somente leitura": `!podeEditar(dados.sessaoEstado)` (módulo puro).
//   - "sem composição": `dados.linhas.length === 0`.
//   - canal degradado: `canal === "reconectando"` do hook — nunca um botão de simulação.
//
// A REGRA MAIS IMPORTANTE (herdada de `chamada-vista.ts`, `contarLocal`): o quórum exibido é SEMPRE
// `dados.quorum` (vindo do servidor). O contador local (`aplicarOtimista`/marcações pendentes) só existe
// para feedback do operador enquanto ele marca em modo "em curso" — nunca para o número do rail.
//
// CARRIES desta fatia (documentados, não escondidos):
//   - Sem uma segunda busca de `SessaoOut`: o cabeçalho usa só o que `ChamadaOut` já publica (data da
//     composição, estado da sessão). "Sessão Ordinária nº 14" do design viraria uma 2ª rota de IO fora do
//     escopo desta fatia — a mensagem de 409 de sessão encerrada sai sem "às HHhMM" por isso (ver
//     `use-chamada.ts`, `UseChamadaOpcoes.sessaoEncerradaEm`), nunca uma hora inventada.
//   - "Lançar justificativa" (POST /sessoes/:id/justificativas) — Etapa 3 fatia 4: `use-chamada.ts` agora
//     expõe `abrirJustificativa` (a 4ª escrita). O gatilho aparece só na linha `ausente` SEM justificativa
//     registrada (`justificativaServidor` nulo) — abre um formulário INLINE na própria linha (não modal: a
//     tela é operada ao vivo, um modal rouba o contexto da folha). Quem LANÇA não DECIDE (vício de
//     competência, ver `wire/in.clj`): a UI nunca deixa reabrir uma linha que já tem justificativa no
//     servidor, pendente ou decidida — o gate é `!justificativaServidor`, não o `estado` da linha sozinho
//     (uma justificativa `indeferida` derivaria a linha de volta a `ausente`, e o servidor recusaria uma
//     segunda com `:ja-existe`; a UI não tenta adivinhar isso, só não convida a reabrir enquanto o servidor
//     já tem um registro para aquele vereador).
//   - Os números de "quórum de instalação/deliberação" do desenho (`11`) são FABRICADOS pelo protótipo —
//     `ChamadaQuorumOut` não os publica. Portados como nota textual [Regimento], sem número inventado.
//   - "Ver."/"Ver.ª" (prefixo de tratamento por gênero) não existe no contrato — usa-se o nome como vem.

import { useState } from "react";
import { useParams, useSearchParams } from "next/navigation";
import { AuthProvider, useAuth } from "@/lib/auth";
import { useTema } from "@/lib/tema";
import { useChamada, type EstadoCanal } from "@/lib/use-chamada";
import { assentosHemiciclo } from "@/lib/hemiciclo";
import { formatarData } from "@/lib/formatar-data";
import {
  agruparLinhas,
  aplicarOtimista,
  ordenarLinhas,
  podeEditar,
  reverterOtimista,
  type CriterioOrdenacao,
  type EstadoAlvo,
  type EstadoLinhaChamada,
  type MarcacoesPendentes,
  type SnapshotOtimista,
} from "@/lib/chamada-vista";
import type { ChamadaOut, LinhaChamadaOut, LinhaJustificativaOut } from "@/lib/contrato-sessoes.gen";
import "./chamada.css";

const ROTULO_ESTADO: Record<EstadoLinhaChamada, string> = {
  "presente-plenario": "Presente no plenário",
  "presente-remoto": "Presente em remoto",
  ausente: "Ausente",
  "ausente-justificado": "Falta justificada",
  "ausente-justificativa-pendente": "Justificativa pendente de decisão",
  licenciado: "Licenciado",
};

const ESTADOS_ALVO: { estado: EstadoAlvo; rotulo: string }[] = [
  { estado: "presente-plenario", rotulo: "Presente" },
  { estado: "presente-remoto", rotulo: "Remoto" },
  { estado: "ausente", rotulo: "Ausente" },
];

/** "2026-05-21T14:03:00Z" -> "14:03". Formatação de leitura humana; nunca usada para comparar. */
function horaCurta(iso: string | null | undefined): string | null {
  if (!iso) return null;
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return null;
  return `${String(d.getHours()).padStart(2, "0")}:${String(d.getMinutes()).padStart(2, "0")}`;
}

function nomeDaLinha(l: LinhaChamadaOut): string {
  return l.nomeParlamentar ?? l.nome ?? "Vereador sem nome cadastrado";
}

function iniciais(nome: string): string {
  const partes = nome.trim().split(/\s+/).filter(Boolean);
  if (partes.length === 0) return "?";
  if (partes.length === 1) return partes[0].slice(0, 2).toUpperCase();
  return (partes[0][0] + partes[partes.length - 1][0]).toUpperCase();
}

export default function PaginaChamada() {
  const params = useParams<{ id: string }>();
  const search = useSearchParams();
  return (
    <AuthProvider tokenQuery={search.get("token")}>
      <ConteudoChamada id={params.id} />
    </AuthProvider>
  );
}

function ConteudoChamada({ id }: { id: string }) {
  const { token } = useAuth();
  const {
    dados, justificativas, estado, canal, erro,
    marcarLinha, registrarChamada, decidirJustificativa, abrirJustificativa,
  } = useChamada(id, token);

  if (estado === "erro") {
    return (
      <main className="tela-estado">
        <h1>Não foi possível abrir a chamada</h1>
        <p>{erro ?? "Erro desconhecido."}</p>
      </main>
    );
  }
  if (!dados) {
    return (
      <main className="tela-estado">
        <h1>Carregando a chamada…</h1>
        <p>Buscando a composição da Casa nesta sessão.</p>
      </main>
    );
  }
  return (
    <Chamada
      dados={dados}
      justificativas={justificativas}
      canal={canal}
      marcarLinha={marcarLinha}
      registrarChamada={registrarChamada}
      decidirJustificativa={decidirJustificativa}
      abrirJustificativa={abrirJustificativa}
    />
  );
}

interface ChamadaProps {
  dados: ChamadaOut;
  justificativas: LinhaJustificativaOut[] | null;
  canal: EstadoCanal;
  marcarLinha: (vereadorId: string, estadoAlvo: EstadoAlvo) => Promise<void>;
  registrarChamada: (marcacoes: MarcacoesPendentes) => Promise<{ ok: true } | { ok: false; erro: string }>;
  decidirJustificativa: (
    jid: string,
    decisao: "aprovada" | "indeferida",
    lockVersion: number,
  ) => Promise<{ ok: true } | { ok: false; erro: string; conflito: boolean }>;
  abrirJustificativa: (vereadorId: string, motivo: string) => Promise<{ ok: true } | { ok: false; erro: string }>;
}

function Chamada({
  dados, justificativas, canal, marcarLinha, registrarChamada, decidirJustificativa, abrirJustificativa,
}: ChamadaProps) {
  const { tema, alternar } = useTema();
  const [busca, setBusca] = useState("");
  const [ordem, setOrdem] = useState<CriterioOrdenacao>("servidor");
  const [pendentes, setPendentes] = useState<MarcacoesPendentes>({});
  const [snapshot, setSnapshot] = useState<SnapshotOtimista | null>(null);
  const [destaque, setDestaque] = useState<string | null>(null);
  const [registrando, setRegistrando] = useState(false);
  const [erroAcao, setErroAcao] = useState<string | null>(null);

  const podeEditarSessao = podeEditar(dados.sessaoEstado);
  const vazia = dados.linhas.length === 0;
  const registrada = dados.chamadasConduzidas.length > 0;
  const emCurso = podeEditarSessao && !vazia && !registrada;
  const registradaAoVivo = podeEditarSessao && !vazia && registrada;

  const ordenadas = ordenarLinhas(dados.linhas, ordem);
  const grupos = agruparLinhas(ordenadas);
  const filtro = (l: LinhaChamadaOut) => {
    if (!busca.trim()) return true;
    const q = busca.trim().toLowerCase();
    return nomeDaLinha(l).toLowerCase().includes(q) || (l.partido ?? "").toLowerCase().includes(q);
  };
  const linhasComAssento = [...grupos.mesa, ...grupos.casa, ...grupos.licenciados];

  function estadoExibido(l: LinhaChamadaOut): EstadoLinhaChamada {
    if (emCurso) return pendentes[l.vereadorId]?.estadoAlvo ?? l.estado;
    return l.estado;
  }

  async function onMarcar(vereadorId: string, alvo: EstadoAlvo) {
    if (!podeEditarSessao) return;
    if (emCurso) {
      setPendentes((p) => ({ ...p, [vereadorId]: { estadoAlvo: alvo, desde: new Date().toISOString() } }));
      return;
    }
    if (registradaAoVivo) {
      try {
        await marcarLinha(vereadorId, alvo);
      } catch (e) {
        setErroAcao(e instanceof Error ? e.message : "Não foi possível gravar a marcação.");
      }
    }
  }

  function onTodosPresentes() {
    const r = aplicarOtimista(dados.linhas, pendentes, new Date().toISOString());
    setPendentes(r.marcacoes);
    setSnapshot(r.snapshot);
  }

  function onDesfazer() {
    if (!snapshot) return;
    setPendentes((p) => reverterOtimista(p, snapshot));
    setSnapshot(null);
  }

  async function onRegistrar() {
    setRegistrando(true);
    setErroAcao(null);
    const r = await registrarChamada(pendentes);
    setRegistrando(false);
    if (r.ok) {
      setPendentes({});
      setSnapshot(null);
    } else {
      setErroAcao(r.erro);
    }
  }

  async function onNovaChamada() {
    setRegistrando(true);
    setErroAcao(null);
    const r = await registrarChamada({});
    setRegistrando(false);
    if (!r.ok) setErroAcao(r.erro);
  }

  async function onDecidir(jid: string, decisao: "aprovada" | "indeferida", lockVersion: number) {
    const r = await decidirJustificativa(jid, decisao, lockVersion);
    if (!r.ok) setErroAcao(r.erro);
  }

  const marcadosCount = linhasComAssento.filter((l) => l.estado !== "licenciado").length;
  const pendentesCount = Object.keys(pendentes).filter((v) =>
    linhasComAssento.some((l) => l.vereadorId === v && l.estado !== "licenciado"),
  ).length;

  return (
    <>
      <header className="topo">
        <div className="envelope topo-grade">
          <div className="marca">
            <Brasao />
            <div>
              <p className="marca-nome">O&nbsp;Plenário</p>
              <p className="marca-orgao">Câmara Municipal</p>
            </div>
          </div>
          <div className="topo-sep" aria-hidden="true" />
          <CanalBadge canal={canal} />
          <div className="sessao-meta">
            <span className="tipo">Chamada de presença</span>
            {/* `data-de-composicao` chega ISO (`2026-08-15`) — formato de transporte, não de tela. O util
                compartilhado `formatarData` é a convenção da casa (pt-BR, instância única de Intl). */}
            <span className="quando">composição de {formatarData(dados.dataDeComposicao)}</span>
          </div>
          <div className="topo-dir">
            <button className="tema-btn" type="button" aria-pressed={tema === "escuro"} onClick={alternar} title="Alternar tema claro / escuro">
              {tema === "escuro" ? "☾" : "☀"}
              <span className="tema-rotulo">{tema === "escuro" ? "Escuro" : "Claro"}</span>
            </button>
          </div>
        </div>
      </header>

      <a className="pular" href="#folha">Pular para a folha de chamada</a>

      <main className="envelope">
        <div className="cabine">
          <section className="bloco folha" id="folha" aria-labelledby="folha-titulo">
            <div className="folha-cabeca">
              <p className="eyebrow">Presença desta sessão</p>
              <h1 id="folha-titulo">A chamada</h1>
              {emCurso && (
                <p className="folha-sub">
                  Marque cada vereador e registre a chamada num único ato. Enquanto não registrar, nada é gravado.
                </p>
              )}
              {registradaAoVivo && (
                <p className="folha-sub">
                  <b>Chamada já registrada.</b> A partir daqui, cada marcação é gravada na hora — entradas e saídas
                  durante a sessão.
                </p>
              )}

              {!vazia && (
                <div className="ferramentas">
                  <div className="campo">
                    <label htmlFor="busca">Localizar vereador</label>
                    <input
                      id="busca"
                      type="search"
                      placeholder="Nome ou partido"
                      autoComplete="off"
                      value={busca}
                      onChange={(e) => setBusca(e.target.value)}
                    />
                  </div>
                  <div className="campo" style={{ flex: "0 1 190px" }}>
                    <label htmlFor="ordem">Ordem da chamada</label>
                    <select id="ordem" value={ordem} onChange={(e) => setOrdem(e.target.value as CriterioOrdenacao)}>
                      <option value="servidor">Como sua Casa chama</option>
                      <option value="alfabetica">Alfabética</option>
                      <option value="partido">Por partido</option>
                      <option value="estado">Por estado de presença</option>
                    </select>
                  </div>
                  {emCurso && (
                    <button className="btn btn-contorno btn-mini" type="button" onClick={onTodosPresentes}>
                      Todos presentes
                    </button>
                  )}
                </div>
              )}

              {!vazia && (
                <p className="nota-ordem">
                  <span>
                    A ordem em que se chama os nomes <b>varia por regimento</b> — a folha abre na ordem cadastrada na
                    sua Casa. <b>[Regimento]</b>
                  </span>
                </p>
              )}
            </div>

            {!podeEditarSessao && (
              <p className="aviso-fechada" role="status">
                <span>
                  <b>A sessão não aceita mais alteração de presença.</b> A presença desta sessão está fechada e não
                  pode mais ser alterada — a correção de um registro errado se faz pela ata. Esta folha segue
                  disponível para leitura.
                </span>
              </p>
            )}

            {vazia ? (
              <div className="vazio-folha">
                <h2>Nenhum vereador com mandato vigente na data da composição</h2>
                <p>
                  A chamada usa a composição da Casa na data da sessão. Sem vereadores cadastrados nessa data não há
                  a quem chamar — e o quórum não teria denominador.
                </p>
              </div>
            ) : (
              <div className="folha-corpo">
                <Grupo
                  titulo="Mesa Diretora"
                  id="g-mesa"
                  linhas={grupos.mesa.filter(filtro)}
                  editavel={podeEditarSessao}
                  estadoExibido={estadoExibido}
                  destaque={destaque}
                  setDestaque={setDestaque}
                  onMarcar={onMarcar}
                  justificativas={justificativas}
                  onDecidir={onDecidir}
                  abrirJustificativa={abrirJustificativa}
                />
                <Grupo
                  titulo="Demais vereadores"
                  id="g-casa"
                  linhas={grupos.casa.filter(filtro)}
                  editavel={podeEditarSessao}
                  estadoExibido={estadoExibido}
                  destaque={destaque}
                  setDestaque={setDestaque}
                  onMarcar={onMarcar}
                  justificativas={justificativas}
                  onDecidir={onDecidir}
                  abrirJustificativa={abrirJustificativa}
                />
                <Grupo
                  titulo="Licenciados"
                  id="g-lic"
                  linhas={grupos.licenciados.filter(filtro)}
                  editavel={false}
                  estadoExibido={estadoExibido}
                  destaque={destaque}
                  setDestaque={setDestaque}
                  onMarcar={onMarcar}
                  justificativas={justificativas}
                  onDecidir={onDecidir}
                  abrirJustificativa={abrirJustificativa}
                />
                {grupos.foraDaComposicao.length > 0 && (
                  <>
                    <p className="fora-nota">
                      <span>
                        <b>
                          {grupos.foraDaComposicao.length} presença(s) fora da composição.
                        </b>{" "}
                        Há registro de presença para alguém que não consta como vereador com mandato vigente nesta
                        data. Conta no total de presentes, <b>mas não no total de membros da Casa</b> — por isso os
                        números podem não fechar. Verifique o cadastro da legislatura antes de registrar a chamada.
                      </span>
                    </p>
                    <Grupo
                      titulo="Fora da composição"
                      id="g-fora"
                      linhas={grupos.foraDaComposicao.filter(filtro)}
                      editavel={false}
                      estadoExibido={estadoExibido}
                      destaque={destaque}
                      setDestaque={setDestaque}
                      onMarcar={onMarcar}
                      justificativas={justificativas}
                      onDecidir={onDecidir}
                      abrirJustificativa={abrirJustificativa}
                    />
                  </>
                )}
              </div>
            )}
          </section>

          <aside className="rail" aria-label="Quórum, justificativas e atos de chamada">
            <Quorum
              dados={dados}
              linhasComAssento={linhasComAssento}
              destaque={destaque}
              setDestaque={setDestaque}
              estadoDe={estadoExibido}
              aRegistrar={pendentesCount}
            />
            <JustificativasPainel justificativas={justificativas} dados={dados} onDecidir={onDecidir} />
            <Atos dados={dados} />
          </aside>
        </div>
      </main>

      {!vazia && (
        <div className="comando" role="region" aria-label="Comandos da chamada">
          <div className="envelope">
            {emCurso && (
              <p className="dica">
                Registrar grava a folha inteira num único ato, com a hora e o seu nome. Enquanto você marca, nada sai
                daqui — se a rede cair no meio, não fica meia chamada gravada.
              </p>
            )}
            {registradaAoVivo && (
              <p className="dica">
                A chamada já foi registrada. Cada marcação agora é gravada na hora, como entrada ou saída durante a
                sessão — e entra no quórum das votações seguintes.
              </p>
            )}
            {erroAcao && (
              <p role="status" className="erro-inline">
                {erroAcao}
              </p>
            )}
            <div className="comando-grade">
              <div className="comando-ctx">
                {emCurso && (
                  <b>
                    {marcadosCount === 0 ? "0" : pendentesCount} marcação(ões) pendente(s) de {marcadosCount}
                  </b>
                )}
                {registradaAoVivo && <b>Chamada registrada — ajustes ao vivo</b>}
                <span>
                  {dados.quorum.presentesTotal} presentes · {dados.quorum.membrosDaCasa} membros da Casa
                </span>
              </div>
              {podeEditarSessao && (
                <div className="comando-acoes">
                  {snapshot && emCurso && (
                    <span className="desfazer" role="status">
                      Todos marcados como presentes.
                      <button className="btn btn-fantasma btn-mini" type="button" onClick={onDesfazer}>
                        Desfazer
                      </button>
                    </span>
                  )}
                  {emCurso && (
                    <button className="btn btn-primaria" type="button" disabled={registrando} onClick={onRegistrar}>
                      Registrar a chamada
                    </button>
                  )}
                  {registradaAoVivo && (
                    <button className="btn btn-primaria" type="button" disabled={registrando} onClick={onNovaChamada}>
                      Nova chamada
                    </button>
                  )}
                </div>
              )}
            </div>
          </div>
        </div>
      )}
    </>
  );
}

function CanalBadge({ canal }: { canal: EstadoCanal }) {
  if (canal === "reconectando") return <span className="ao-vivo off">Reconectando</span>;
  if (canal === "carregando") return <span className="ao-vivo off">Carregando</span>;
  return (
    <span className="ao-vivo">
      <span className="pulso" aria-hidden="true" />
      Ao vivo
    </span>
  );
}

interface GrupoProps {
  titulo: string;
  id: string;
  linhas: LinhaChamadaOut[];
  editavel: boolean;
  estadoExibido: (l: LinhaChamadaOut) => EstadoLinhaChamada;
  destaque: string | null;
  setDestaque: (v: string | null) => void;
  onMarcar: (vereadorId: string, alvo: EstadoAlvo) => void;
  justificativas: LinhaJustificativaOut[] | null;
  onDecidir: (jid: string, decisao: "aprovada" | "indeferida", lockVersion: number) => void;
  abrirJustificativa: (vereadorId: string, motivo: string) => Promise<{ ok: true } | { ok: false; erro: string }>;
}

function Grupo({
  titulo, id, linhas, editavel, estadoExibido, destaque, setDestaque, onMarcar, justificativas, onDecidir,
  abrirJustificativa,
}: GrupoProps) {
  if (linhas.length === 0) return null;
  return (
    <>
      <h2 className="grupo-tit" id={id}>
        {titulo} <span className="qtd">{linhas.length}</span>
      </h2>
      <ul className="linhas" aria-labelledby={id}>
        {linhas.map((l) => (
          <Linha
            key={l.vereadorId}
            linha={l}
            editavel={editavel}
            estado={estadoExibido(l)}
            destacada={destaque === l.vereadorId}
            onHover={(on) => setDestaque(on ? l.vereadorId : null)}
            onMarcar={onMarcar}
            justificativaServidor={justificativas?.find((j) => j.vereadorId === l.vereadorId) ?? null}
            onDecidir={onDecidir}
            abrirJustificativa={abrirJustificativa}
          />
        ))}
      </ul>
    </>
  );
}

interface LinhaProps {
  linha: LinhaChamadaOut;
  editavel: boolean;
  estado: EstadoLinhaChamada;
  destacada: boolean;
  onHover: (on: boolean) => void;
  onMarcar: (vereadorId: string, alvo: EstadoAlvo) => void;
  justificativaServidor: LinhaJustificativaOut | null;
  onDecidir: (jid: string, decisao: "aprovada" | "indeferida", lockVersion: number) => void;
  abrirJustificativa: (vereadorId: string, motivo: string) => Promise<{ ok: true } | { ok: false; erro: string }>;
}

function Linha({
  linha, editavel, estado, destacada, onHover, onMarcar, justificativaServidor, onDecidir, abrirJustificativa,
}: LinhaProps) {
  const nome = nomeDaLinha(linha);
  const horaFato = horaCurta(linha.desde);
  const horaRegistro = horaCurta(linha.registradoEm);
  const retro =
    !!linha.desde && !!linha.registradoEm && Math.abs(Date.parse(linha.registradoEm) - Date.parse(linha.desde)) > 60_000;

  function onKeyDownGrupo(e: React.KeyboardEvent<HTMLSpanElement>) {
    if (e.key !== "ArrowRight" && e.key !== "ArrowLeft") return;
    const botoes = Array.from(e.currentTarget.querySelectorAll<HTMLButtonElement>("button[data-e]"));
    const i = botoes.indexOf(document.activeElement as HTMLButtonElement);
    if (i < 0) return;
    e.preventDefault();
    const proximo = (i + (e.key === "ArrowRight" ? 1 : botoes.length - 1)) % botoes.length;
    botoes[proximo]?.focus();
  }

  return (
    <li
      className={destacada ? "linha destacada" : "linha"}
      data-v={linha.vereadorId}
      data-estado={estado}
      onMouseEnter={() => onHover(true)}
      onMouseLeave={() => onHover(false)}
      onFocus={() => onHover(true)}
      onBlur={() => onHover(false)}
    >
      <span className="trilho" aria-hidden="true" />
      <span className="avatar" aria-hidden="true">{iniciais(nome)}</span>
      <span className="quem">
        <b>
          {nome}
          {linha.inconsistenciaCadastro && (
            <span className="chip chip-risco" style={{ marginLeft: "0.4rem" }}>Cadastro incompleto</span>
          )}
        </b>
        <span className="sub">
          {linha.partido && <span className="partido">{linha.partido}</span>}
          {linha.cargoMesa && <span className="cargo-mesa">{linha.cargoMesa}</span>}
          {linha.semAssento && <span className="chip chip-risco">Sem assento na data</span>}
        </span>
      </span>

      {editavel ? (
        <span className="marcar" role="group" aria-label={`Presença de ${nome}`} onKeyDown={onKeyDownGrupo}>
          {ESTADOS_ALVO.map((e) => (
            <button
              key={e.estado}
              type="button"
              data-e={e.estado}
              aria-pressed={estado === e.estado}
              onClick={() => onMarcar(linha.vereadorId, e.estado)}
            >
              {e.rotulo}
            </button>
          ))}
        </span>
      ) : (
        <span className="estado-fixo">
          {linha.estado === "licenciado" ? "Licenciado" : `${ROTULO_ESTADO[linha.estado]} · fora do denominador`}
        </span>
      )}

      <span className="hora">
        {horaFato ? (
          <>
            <b>{horaFato}</b>
            <span className={retro ? "retro" : undefined}>{horaRegistro ? `registrado ${horaRegistro}` : ""}</span>
          </>
        ) : (
          <>
            <b>—</b>
            <span className="vazio">{linha.estado === "licenciado" ? "não se marca" : "sem registro"}</span>
          </>
        )}
      </span>

      {linha.estado === "ausente-justificado" || linha.estado === "ausente-justificativa-pendente" ? (
        <span className={linha.estado === "ausente-justificativa-pendente" ? "just pendente" : "just"}>
          <span className={linha.estado === "ausente-justificativa-pendente" ? "chip chip-alerta" : "chip chip-ok"}>
            {ROTULO_ESTADO[linha.estado]}
          </span>
          {justificativaServidor && <span className="motivo">{justificativaServidor.motivo}</span>}
          {linha.estado === "ausente-justificativa-pendente" && justificativaServidor && editavel && (
            <span className="decidir">
              <button
                className="btn btn-contorno btn-mini"
                type="button"
                onClick={() => onDecidir(justificativaServidor.id, "aprovada", justificativaServidor.lockVersion)}
              >
                Deferir
              </button>
              <button
                className="btn btn-fantasma btn-mini"
                type="button"
                onClick={() => onDecidir(justificativaServidor.id, "indeferida", justificativaServidor.lockVersion)}
              >
                Indeferir
              </button>
            </span>
          )}
        </span>
      ) : linha.estado === "ausente" && editavel && !justificativaServidor ? (
        <JustificativaAbrir vereadorId={linha.vereadorId} abrirJustificativa={abrirJustificativa} />
      ) : null}
    </li>
  );
}

/** O "Lançar justificativa" da linha `ausente` sem registro no servidor (Etapa 3 fatia 4). Formulário INLINE
 * — nunca modal (a folha é operada ao vivo; um modal rouba o contexto de "quem eu estava marcando"). Reusa a
 * receita `.campo` (mesma da barra de ferramentas) e `.just`/`.decidir` (mesmos do bloco de justificativa
 * decidida acima) — nenhum estilo novo. `motivo.trim()` é validado aqui ANTES de chamar `abrirJustificativa`
 * (que valida de novo — defesa em profundidade, não confiança no cliente); o erro do servidor (400 de motivo
 * vazio, 409 de fora-do-roster/duplicata) aparece no mesmo lugar, com `role="alert"`. */
function JustificativaAbrir({
  vereadorId,
  abrirJustificativa,
}: {
  vereadorId: string;
  abrirJustificativa: (vereadorId: string, motivo: string) => Promise<{ ok: true } | { ok: false; erro: string }>;
}) {
  const [aberta, setAberta] = useState(false);
  const [motivo, setMotivo] = useState("");
  const [enviando, setEnviando] = useState(false);
  const [erro, setErro] = useState<string | null>(null);
  const campoId = `motivo-${vereadorId}`;

  if (!aberta) {
    return (
      <span className="just">
        <span className="chip chip-neutro">Sem justificativa</span>
        <span className="motivo">Nenhuma justificativa protocolada para esta sessão.</span>
        <span className="decidir">
          <button className="btn btn-contorno btn-mini" type="button" onClick={() => setAberta(true)}>
            Lançar justificativa
          </button>
        </span>
      </span>
    );
  }

  async function onSalvar() {
    if (!motivo.trim()) {
      setErro("Descreva o motivo da ausência — o campo não pode ficar em branco.");
      return;
    }
    setEnviando(true);
    setErro(null);
    const r = await abrirJustificativa(vereadorId, motivo);
    setEnviando(false);
    if (r.ok) {
      setAberta(false);
      setMotivo("");
    } else {
      setErro(r.erro);
    }
  }

  function onCancelar() {
    setAberta(false);
    setMotivo("");
    setErro(null);
  }

  return (
    <span className="just">
      <span className="campo">
        <label htmlFor={campoId}>Motivo da ausência</label>
        <textarea
          id={campoId}
          value={motivo}
          onChange={(e) => setMotivo(e.target.value)}
          rows={2}
          maxLength={4096}
          aria-invalid={erro ? true : undefined}
          aria-describedby={erro ? `${campoId}-erro` : undefined}
        />
        {erro && (
          <span id={`${campoId}-erro`} role="alert" className="campo-erro">
            {erro}
          </span>
        )}
      </span>
      <span className="decidir">
        <button className="btn btn-primaria btn-mini" type="button" disabled={enviando} onClick={onSalvar}>
          {enviando ? "Salvando…" : "Salvar"}
        </button>
        <button className="btn btn-fantasma btn-mini" type="button" disabled={enviando} onClick={onCancelar}>
          Cancelar
        </button>
      </span>
    </span>
  );
}

function Quorum({
  dados,
  linhasComAssento,
  destaque,
  setDestaque,
  estadoDe,
  aRegistrar,
}: {
  dados: ChamadaOut;
  linhasComAssento: LinhaChamadaOut[];
  destaque: string | null;
  setDestaque: (v: string | null) => void;
  estadoDe: (l: LinhaChamadaOut) => EstadoLinhaChamada;
  aRegistrar: number;
}) {
  const total = Math.max(1, linhasComAssento.length);
  // Geometria em `lib/hemiciclo` (pura, testada) — a mesma do telão. Ver o docstring de lá: três
  // fileiras fixas degeneravam em Casa pequena (4 membros -> [1,1,2], dois assentos no mesmo eixo).
  const seats = assentosHemiciclo(total)
    .slice(0, linhasComAssento.length)
    .map((p, i) => ({ ...p, l: linhasComAssento[i] }));

  // O MAPA DE ASSENTOS É A FOLHA, NÃO O INSTRUMENTO DE QUÓRUM (decisão de 15/08/2026).
  //
  // Antes, os assentos e a legenda liam `l.estado` CRU do servidor enquanto as linhas da folha já
  // mostravam a marcação pendente. Resultado medido em browser: a linha dizia "Ana presente", a legenda
  // dizia "Ausentes 3" e o número dizia "0 de 3" — a mesma tela afirmando três coisas incompatíveis. E
  // durante toda a fase de marcação o mapa ficava cinza, isto é, peso morto exatamente quando o operador
  // mais precisa de leitura rápida de "quem ainda não fiz".
  //
  // A separação que resolve: o mapa e a legenda seguem `estadoDe` (o que o operador vê na linha); o
  // NÚMERO e o DENOMINADOR seguem estritamente `dados.quorum`, do servidor. A lei da Etapa 4 é sobre o
  // número — nunca aplicar delta local a ele — e fica intacta. O que impede o mapa de ser lido como
  // quórum confirmado é o rótulo "a registrar", visível enquanto houver pendência.
  const legenda = { plenario: 0, remoto: 0, ausentes: 0, licenciados: 0 };
  for (const l of linhasComAssento) {
    const e = estadoDe(l);
    if (e === "presente-plenario") legenda.plenario++;
    else if (e === "presente-remoto") legenda.remoto++;
    else if (e === "licenciado") legenda.licenciados++;
    else legenda.ausentes++;
  }

  const rotulo =
    aRegistrar > 0
      ? `${dados.quorum.presentesTotal} de ${dados.quorum.membrosDaCasa} vereadores presentes; ${aRegistrar} marcação a registrar.`
      : `${dados.quorum.presentesTotal} de ${dados.quorum.membrosDaCasa} vereadores presentes.`;

  return (
    <section className="bloco quorum" aria-labelledby="quorum-titulo">
      <div className="bloco-cabeca">
        <h2 id="quorum-titulo">Quórum</h2>
        <span className="chip chip-ok">Contagem viva</span>
      </div>
      <div className="bloco-corpo">
        <div className="quorum-num" aria-live="polite" aria-atomic="true">
          <b>{dados.quorum.presentesTotal}</b>
          <span>
            de <b>{dados.quorum.membrosDaCasa}</b> membros presentes
          </span>
        </div>

        <svg className="hemi" viewBox="0 0 240 130" role="img" aria-label={rotulo}>
          {seats.map((s) => (
            <circle
              key={s.l.vereadorId}
              cx={s.x.toFixed(1)}
              cy={s.y.toFixed(1)}
              r="6"
              className={`s-${estadoDe(s.l)}${destaque === s.l.vereadorId ? " destaque" : ""}`}
              onMouseEnter={() => setDestaque(s.l.vereadorId)}
              onMouseLeave={() => setDestaque(null)}
              onClick={() => {
                const alvo = document.querySelector<HTMLElement>(
                  `.linha[data-v="${s.l.vereadorId}"] .marcar button[aria-pressed="true"], .linha[data-v="${s.l.vereadorId}"] .marcar button`,
                );
                alvo?.focus();
              }}
            >
              <title>{`${nomeDaLinha(s.l)} — ${ROTULO_ESTADO[estadoDe(s.l)]}`}</title>
            </circle>
          ))}
        </svg>

        {/* O marcador que separa a FOLHA do NÚMERO. Sem ele o mapa colorido dentro de um bloco chamado
            "Quórum" se lê como presença confirmada — que é exatamente o erro que a Etapa 4 reprovou. */}
        {aRegistrar > 0 && (
          <p className="a-registrar">
            <span className="chip chip-alerta">a registrar</span>
            <span>
              O mapa acima já mostra {aRegistrar === 1 ? "a sua marcação" : "as suas marcações"}. O número
              acima é o do servidor e só muda quando você registrar a chamada.
            </span>
          </p>
        )}

        <div className="quorum-legenda">
          <span><i style={{ background: "var(--marca)" }} />Plenário {legenda.plenario}</span>
          <span><i style={{ background: "var(--foco)" }} />Remoto {legenda.remoto}</span>
          <span><i style={{ background: "var(--texto-2)" }} />Ausentes {legenda.ausentes}</span>
          <span><i className="vazado" />Licenciado {legenda.licenciados}</span>
        </div>

        <p className="limiar">
          <span className="gap">[Regimento] quórum de instalação e de deliberação em homologação</span> — se a sua
          Casa separa os dois, confirme no regimento antes de usar este número fora daqui.
        </p>

        {dados.quorum.presencasForaDoRoster > 0 && (
          <p className="fora-roster">
            <span>
              <b>+{dados.quorum.presencasForaDoRoster} presença(s) fora da composição</b> — total de presentes{" "}
              {dados.quorum.presentesTotal}, membros da Casa {dados.quorum.membrosDaCasa}. Ver o grupo no fim da
              folha.
            </span>
          </p>
        )}
      </div>
    </section>
  );
}

function JustificativasPainel({
  justificativas,
  dados,
  onDecidir,
}: {
  justificativas: LinhaJustificativaOut[] | null;
  dados: ChamadaOut;
  onDecidir: (jid: string, decisao: "aprovada" | "indeferida", lockVersion: number) => void;
}) {
  const pendentes = (justificativas ?? []).filter((j) => j.estado === "pendente");
  const podeDecidir = podeEditar(dados.sessaoEstado);
  return (
    <section className="bloco larga" aria-labelledby="just-titulo">
      <div className="bloco-cabeca">
        <h2 id="just-titulo">Justificativas</h2>
        {pendentes.length > 0 && <span className="chip chip-alerta">{pendentes.length} pendente(s)</span>}
      </div>
      <div className="bloco-corpo">
        {pendentes.length === 0 ? (
          <p className="nota-mesa">
            <span>Nenhuma justificativa pendente de decisão.</span>
          </p>
        ) : (
          <ul className="pend-lista">
            {pendentes.map((j) => {
              const linha = dados.linhas.find((l) => l.vereadorId === j.vereadorId);
              return (
                <li key={j.id}>
                  <span className="nome">{linha ? nomeDaLinha(linha) : j.vereadorId.slice(0, 8)}</span>
                  <span className="txt">{j.motivo}</span>
                  {podeDecidir && (
                    <span className="acoes">
                      <button className="btn btn-contorno btn-mini" type="button" onClick={() => onDecidir(j.id, "aprovada", j.lockVersion)}>
                        Deferir
                      </button>
                      <button className="btn btn-fantasma btn-mini" type="button" onClick={() => onDecidir(j.id, "indeferida", j.lockVersion)}>
                        Indeferir
                      </button>
                    </span>
                  )}
                </li>
              );
            })}
          </ul>
        )}
        <p className="nota-mesa">
          <span>
            Quem <b>lança</b> a justificativa é a secretaria; quem <b>decide</b> é a Mesa. A decisão fica registrada
            com nome e hora de quem deferiu.
          </span>
        </p>
      </div>
    </section>
  );
}

function Atos({ dados }: { dados: ChamadaOut }) {
  const atos = [...dados.chamadasConduzidas].sort((a, b) => Date.parse(b.ocorridoEm) - Date.parse(a.ocorridoEm));
  return (
    <section className="bloco larga" aria-labelledby="atos-titulo">
      <div className="bloco-cabeca">
        <h2 id="atos-titulo">Chamadas desta sessão</h2>
        <span className="eyebrow" style={{ color: "var(--texto-2)" }}>{atos.length}</span>
      </div>
      <div className="bloco-corpo">
        {atos.length === 0 ? (
          <p className="nota-mesa">
            <span>Nenhuma chamada conduzida nesta sessão ainda.</span>
          </p>
        ) : (
          <ul className="atos">
            {atos.map((a) => (
              <li key={a.id}>
                <span className="qdo">{horaCurta(a.ocorridoEm) ?? "—"}</span>
                <span className="qm">
                  Chamada · <b>{a.conduzidaPor.slice(0, 8)}</b>
                </span>
                <span className="det">Casa com {a.membrosDaCasa} membros</span>
              </li>
            ))}
          </ul>
        )}
        <p className="nota-mesa">
          <span>
            Cada chamada vira um <b>ato datado e append-only</b>, com quem conduziu e quantos membros a Casa tinha na
            hora. Não se apaga: uma chamada errada se corrige com outra chamada.
          </span>
        </p>
      </div>
    </section>
  );
}

function Brasao() {
  return (
    <svg className="marca-simbolo" viewBox="0 0 40 40" role="img" aria-label="O Plenário">
      <circle cx="20" cy="20" r="19" fill="#FBF8F0" stroke="#E0D7BF" />
      <path d="M7 27 A13 13 0 0 1 33 27" fill="none" stroke="#0C5340" strokeWidth="2.4" strokeLinecap="round" />
      <path d="M10 27 A10 10 0 0 1 30 27" fill="none" stroke="#1E5FA8" strokeWidth="2.4" strokeLinecap="round" />
      <path d="M13.5 27 A6.5 6.5 0 0 1 26.5 27" fill="none" stroke="#D9542B" strokeWidth="2.4" strokeLinecap="round" />
      <rect x="18.4" y="9.5" width="3.2" height="6" rx="1.2" fill="#E8B23A" />
    </svg>
  );
}
