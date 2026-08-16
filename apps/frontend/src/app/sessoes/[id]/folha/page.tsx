"use client";

// A folha da sessão — §22.6 eixo C, Etapa 5 fatia 6 (a ÚLTIMA fatia da Etapa 5). Irmã solta de
// `/sessoes/[id]/chamada` e `/sessoes/[id]/plenario` em `app/sessoes/[id]/` (nenhum layout de grupo dá
// chrome — o header é desenhado aqui, mesma disciplina das duas irmãs).
//
// D11 do brief: esta tela NÃO abriu rodada de design própria. O peso visual é o DOCUMENTO (já desenhado e
// passado pelos dois consultores na Fatia 2); esta página é casca — lista de versões + ação de congelar +
// visualizador + download. Arquétipo herdado: Balcão de trabalho (painel de entrada + ilha-papel + comando,
// `PADROES-DE-COMPOSICAO §2`, mesmo par de `expediente`).
//
// D10 do brief — POR QUE O VISUALIZADOR É <iframe sandbox="" srcDoc={…}>, NUNCA <iframe src="/api/…">:
// três razões convergem na mesma escolha (documentadas também em `use-folha.ts`/`adapters/out/folha.clj`):
//   1) auth em DEV: o token só viaja dentro de `apiFetch` (header `Authorization`); navegação direta do
//      `<iframe src>` não o carrega — o visualizador ficaria quebrado no ambiente onde se desenvolve.
//   2) isolamento de XSS: o HTML congelado embute texto livre humano (`motivo` de justificativa, LGPD/dado
//      de saúde) — `sandbox=""` (sem `allow-scripts`/`allow-same-origin`) é defesa em profundidade real,
//      custo zero, porque o documento é estático (sem JS, sem CSS externo — Fatia 2).
//   3) o servidor já responde com `X-Frame-Options: DENY` (interceptor global) — um `<iframe src>` seria
//      RECUSADO pelo navegador, não por bug daqui.
// `sandbox=""` (string vazia, NUNCA `"allow-scripts allow-same-origin"`) é testado explicitamente em
// page.test.tsx — a combinação errada é pior que não ter sandbox.
//
// Download do PDF: `baixarPdf` (use-folha.ts) busca os bytes via `apiFetch` (autentica igual em dev e
// produção) e dispara `URL.createObjectURL` + `<a download>` sintético — funciona nos dois ambientes, ao
// contrário de um `<a href="/api/…">` cru (que só funcionaria em produção, pelo cookie de sessão).
//
// Núcleo lógico fora do componente: ordenação (`ordenarVersoes`) e formatação de hash/erro/Retry-After
// (`formatarHash`/`mensagemDeErroFolha`/`retryAfterSegundos`) vivem em `lib/folha-vista.ts`, puro e testado
// à parte — este arquivo é só orquestração + apresentação.

import { useEffect, useState } from "react";
import { useParams, useSearchParams } from "next/navigation";
import { AuthProvider, useAuth } from "@/lib/auth";
import { useTema } from "@/lib/tema";
import { useFolha } from "@/lib/use-folha";
import { formatarHash, ordenarVersoes } from "@/lib/folha-vista";
import { formatarData, formatarHora } from "@/lib/formatar-data";
import type { FolhaMetadadosOut } from "@/lib/contrato-sessoes.gen";
import "./folha.css";

export default function PaginaFolha() {
  const params = useParams<{ id: string }>();
  const search = useSearchParams();
  return (
    <AuthProvider tokenQuery={search.get("token")}>
      <ConteudoFolha id={params.id} />
    </AuthProvider>
  );
}

function ConteudoFolha({ id }: { id: string }) {
  const { token } = useAuth();
  const { versoes, estado, erro, gerar, buscarHtml, baixarPdf } = useFolha(id, token);

  if (estado === "erro") {
    return (
      <main className="tela-estado">
        <h1>Não foi possível abrir a folha</h1>
        <p>{erro ?? "Erro desconhecido."}</p>
      </main>
    );
  }
  if (!versoes) {
    return (
      <main className="tela-estado">
        <h1>Carregando a folha…</h1>
        <p>Buscando as versões congeladas desta sessão.</p>
      </main>
    );
  }
  return <Folha sessaoId={id} versoes={versoes} gerar={gerar} buscarHtml={buscarHtml} baixarPdf={baixarPdf} />;
}

interface FolhaProps {
  sessaoId: string;
  versoes: FolhaMetadadosOut[];
  gerar: ReturnType<typeof useFolha>["gerar"];
  buscarHtml: ReturnType<typeof useFolha>["buscarHtml"];
  baixarPdf: ReturnType<typeof useFolha>["baixarPdf"];
}

function Folha({ sessaoId, versoes, gerar, buscarHtml, baixarPdf }: FolhaProps) {
  const { tema, alternar } = useTema();
  const ordenadas = ordenarVersoes(versoes);
  const vazia = ordenadas.length === 0;

  // Seleção EXPLÍCITA do operador (null = "segue o padrão"). A versão efetivamente exibida é DERIVADA no
  // render (nunca via useEffect+setState — isso é o antipadrão que `react-hooks/set-state-in-effect`
  // reprova: um efeito cujo corpo só espelha estado a partir de outro estado): sem escolha manual, a mais
  // recente abre selecionada — é o documento que quem entra na tela quer ver.
  const [selecionadaManual, setSelecionadaManual] = useState<number | null>(null);
  const selecionada = selecionadaManual ?? ordenadas[0]?.versao ?? null;

  const [gerando, setGerando] = useState(false);
  const [erroGerar, setErroGerar] = useState<string | null>(null);
  const [notaGerar, setNotaGerar] = useState<string | null>(null);
  const [baixando, setBaixando] = useState<number | null>(null);
  const [erroBaixar, setErroBaixar] = useState<string | null>(null);

  async function onGerar() {
    setGerando(true);
    setErroGerar(null);
    setNotaGerar(null);
    const r = await gerar();
    setGerando(false);
    if (!r.ok) {
      setErroGerar(r.erro);
      return;
    }
    setSelecionadaManual(r.folha.versao);
    if (r.folha.jaCongelada) {
      setNotaGerar(
        `Já havia uma versão gerada nos últimos instantes por este mesmo ator — mostrando a versão ${r.folha.versao} existente, sem duplicar o acervo.`,
      );
    } else {
      setNotaGerar(`Versão ${r.folha.versao} congelada.`);
    }
  }

  async function onBaixar(versao: number) {
    setBaixando(versao);
    setErroBaixar(null);
    const r = await baixarPdf(versao);
    setBaixando(null);
    if (!r.ok) setErroBaixar(r.erro);
  }

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
          <div className="sessao-meta">
            <span className="tipo">Folha de presença</span>
          </div>
          <div className="topo-dir">
            <button className="tema-btn" type="button" aria-pressed={tema === "escuro"} onClick={alternar} title="Alternar tema claro / escuro">
              {tema === "escuro" ? "☾" : "☀"}
              <span className="tema-rotulo">{tema === "escuro" ? "Escuro" : "Claro"}</span>
            </button>
          </div>
        </div>
      </header>

      <a className="pular" href="#versoes">Pular para as versões congeladas</a>

      <main className="envelope">
        <div className="folha-grade">
          <section className="bloco" id="versoes" aria-labelledby="versoes-titulo">
            <div className="bloco-cabeca">
              <h2 id="versoes-titulo">Versões congeladas</h2>
              <span className="chip chip-neutro">{ordenadas.length}</span>
            </div>
            <div className="bloco-corpo">
              {vazia ? (
                <div className="versoes-vazio">
                  <p>
                    Esta sessão ainda não tem folha congelada. A folha só pode ser gerada depois de a sessão ser
                    <b> encerrada</b> — congele quando a presença estiver definitiva.
                  </p>
                </div>
              ) : (
                <ul className="versoes-lista" aria-label="Versões congeladas da folha">
                  {ordenadas.map((v) => (
                    <VersaoItem
                      key={v.id}
                      versao={v}
                      selecionada={selecionada === v.versao}
                      onSelecionar={() => setSelecionadaManual(v.versao)}
                      onBaixar={() => onBaixar(v.versao)}
                      baixando={baixando === v.versao}
                    />
                  ))}
                </ul>
              )}

              {erroBaixar && (
                <p role="alert" className="gerar-erro" style={{ marginTop: "0.8rem" }}>
                  {erroBaixar}
                </p>
              )}

              <div className="gerar-bloco">
                <button className="btn btn-primaria" type="button" disabled={gerando} onClick={onGerar}>
                  {gerando ? "Gerando…" : vazia ? "Gerar a folha desta sessão" : "Gerar nova versão"}
                </button>
                {erroGerar && <p role="alert" className="gerar-erro">{erroGerar}</p>}
                {!erroGerar && notaGerar && <p role="status" className="gerar-nota">{notaGerar}</p>}
                <p className="gerar-nota">
                  Cada congelamento cria uma versão nova, numerada e imutável — a correção de um registro errado
                  se faz gerando outra versão, nunca editando uma já congelada.
                </p>
              </div>
            </div>
          </section>

          <section className="visualizador" aria-labelledby="visualizador-titulo">
            <Visualizador
              sessaoId={sessaoId}
              versoes={ordenadas}
              selecionada={selecionada}
              buscarHtml={buscarHtml}
              onBaixar={onBaixar}
              baixando={baixando}
            />
          </section>
        </div>
      </main>
    </>
  );
}

function VersaoItem({
  versao,
  selecionada,
  onSelecionar,
  onBaixar,
  baixando,
}: {
  versao: FolhaMetadadosOut;
  selecionada: boolean;
  onSelecionar: () => void;
  onBaixar: () => void;
  baixando: boolean;
}) {
  const html = formatarHash(versao.htmlHash);
  const pdf = formatarHash(versao.pdfHash);
  return (
    <li className={selecionada ? "versao-item selecionada" : "versao-item"}>
      <div className="versao-cabeca">
        <span className="versao-num">Versão {versao.versao}</span>
        <span className="versao-quando">
          {formatarData(versao.geradaEm)} às {formatarHora(versao.geradaEm)}
        </span>
      </div>
      <span className="versao-por">Congelada por {versao.geradaPor.slice(0, 8)}</span>
      {selecionada && <span className="chip chip-ok" style={{ width: "fit-content" }}>Selecionada</span>}

      <div className="versao-hashes">
        <HashLinha rotulo="HTML" hash={html} />
        <HashLinha rotulo="PDF" hash={pdf} />
      </div>

      <div className="versao-acoes">
        <button className="btn btn-contorno btn-mini" type="button" onClick={onSelecionar} disabled={selecionada}>
          {selecionada ? "Em exibição" : "Ver"}
        </button>
        <button className="btn btn-fantasma btn-mini" type="button" onClick={onBaixar} disabled={baixando}>
          {baixando ? "Baixando…" : "Baixar PDF"}
        </button>
      </div>
    </li>
  );
}

/** O hash é a prova de integridade que o jurídico compara — sempre exibido por inteiro (nunca truncado no
 * DOM, só visualmente elidido por `text-overflow`) e selecionável com um clique (`user-select: all`), mais
 * um botão explícito de copiar. Copiar é best-effort: `navigator.clipboard` pode faltar (contexto não
 * seguro em dev, ver carry de passkey) — falha silenciosa, nunca trava a tela por um botão secundário. */
function HashLinha({ rotulo, hash }: { rotulo: string; hash: ReturnType<typeof formatarHash> }) {
  const [copiado, setCopiado] = useState(false);
  const valor = hash.algoritmo ? `${hash.algoritmo}:${hash.digest}` : hash.digest;

  async function onCopiar() {
    try {
      await navigator.clipboard?.writeText(valor);
      setCopiado(true);
      setTimeout(() => setCopiado(false), 2000);
    } catch {
      // best-effort — ver docstring
    }
  }

  return (
    <span className="hash-linha">
      <b>{rotulo}</b>
      <span className="hash-valor" title={valor}>{valor}</span>
      <button className="hash-copiar" type="button" onClick={onCopiar}>
        {copiado ? "Copiado" : "Copiar"}
      </button>
    </span>
  );
}

function Visualizador({
  sessaoId,
  versoes,
  selecionada,
  buscarHtml,
  onBaixar,
  baixando,
}: {
  sessaoId: string;
  versoes: FolhaMetadadosOut[];
  selecionada: number | null;
  buscarHtml: ReturnType<typeof useFolha>["buscarHtml"];
  onBaixar: (versao: number) => void;
  baixando: number | null;
}) {
  const [html, setHtml] = useState<string | null>(null);
  const [carregando, setCarregando] = useState(false);
  const [erro, setErro] = useState<string | null>(null);

  useEffect(() => {
    // `selecionada === null` só ocorre sem nenhuma versão congelada — o render abaixo já troca para o
    // estado vazio nesse caso (`versoes.length === 0`), então `html`/`erro` (ambos já nascem `null`)
    // nunca chegam a ser lidos; nenhum setState é necessário aqui (evita o antipadrão flagueado por
    // `react-hooks/set-state-in-effect`: um efeito cujo corpo inteiro, num ramo, é só um setState).
    if (selecionada === null) return;
    let vivo = true;
    (async () => {
      setCarregando(true);
      setErro(null);
      setHtml(null);
      const r = await buscarHtml(selecionada);
      if (!vivo) return;
      setCarregando(false);
      if (r.ok) setHtml(r.html);
      else setErro(r.erro);
    })();
    return () => {
      vivo = false;
    };
  }, [selecionada, buscarHtml]);

  if (versoes.length === 0) {
    return (
      <div className="bloco">
        <div className="visualizador-vazio">
          Nenhuma versão congelada ainda — gere a folha desta sessão para visualizar o documento aqui.
        </div>
      </div>
    );
  }

  return (
    <div className="bloco">
      <div className="bloco-cabeca">
        <div className="visualizador-titulo">
          <h2 id="visualizador-titulo">Documento</h2>
          {selecionada !== null && <span>{`folha-${sessaoId}-v${selecionada}.pdf`}</span>}
        </div>
        {selecionada !== null && (
          <button className="btn btn-contorno btn-mini" type="button" onClick={() => onBaixar(selecionada)} disabled={baixando === selecionada}>
            {baixando === selecionada ? "Baixando…" : "Baixar PDF desta versão"}
          </button>
        )}
      </div>
      <div className="bloco-corpo">
        <div className="ilha-papel" style={{ padding: 0, overflow: "hidden" }}>
          {carregando && <div className="visualizador-carregando">Carregando o documento congelado…</div>}
          {erro && <div className="visualizador-erro" role="alert">{erro}</div>}
          {!carregando && !erro && html !== null && (
            // sandbox="" — string VAZIA, de propósito (D10): nenhuma exceção concedida. Nunca
            // "allow-scripts allow-same-origin" — essa combinação reabriria same-origin + script, que é
            // pior que não sandboxar. Ver docstring do topo do arquivo e page.test.tsx.
            <iframe
              title={`Folha de presença — versão ${selecionada}`}
              sandbox=""
              srcDoc={html}
              className="folha-frame"
            />
          )}
        </div>
      </div>
    </div>
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
