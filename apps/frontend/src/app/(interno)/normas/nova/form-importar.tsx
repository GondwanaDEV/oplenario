"use client";

// O formulário de importação: espécie, título, de onde veio o texto e o próprio texto (colado ou de um arquivo .txt).
// PDF e imagem ainda não: dependem da leitura de PDF/OCR, que vem depois.

import Link from "next/link";
import { useRouter } from "next/navigation";
import { useState } from "react";
import { comToken } from "@/lib/nav";
import { ESPECIES_DA_CASA, especieUnica } from "@/lib/normas-vista";
import { importarNorma } from "@/lib/use-normas";

export function FormImportar({ token = null }: { token?: string | null }) {
  const router = useRouter();
  const [especie, setEspecie] = useState("lei_organica");
  const [titulo, setTitulo] = useState("Lei Orgânica do Município");
  const [numero, setNumero] = useState("");
  const [data, setData] = useState("");
  const [consolidada, setConsolidada] = useState("");
  const [fonte, setFonte] = useState("");
  const [texto, setTexto] = useState("");
  const [erro, setErro] = useState<string | null>(null);
  const [enviando, setEnviando] = useState(false);

  function trocarEspecie(e: string) {
    const anterior = ESPECIES_DA_CASA.find((x) => x.valor === especie);
    const nova = ESPECIES_DA_CASA.find((x) => x.valor === e);
    if (!titulo || titulo === anterior?.rotulo) setTitulo(nova?.unica ? (nova.rotulo ?? "") : "");
    setEspecie(e);
  }

  async function lerArquivo(f: File | undefined) {
    if (!f) return;
    if (!/\.(txt|md)$/i.test(f.name) && f.type !== "text/plain") {
      setErro("Por enquanto só arquivos de texto (.txt). Para PDF, copie o texto do PDF e cole no campo.");
      return;
    }
    setErro(null);
    setTexto(await f.text());
  }

  async function enviar() {
    setErro(null);
    setEnviando(true);
    const r = await importarNorma(token, {
      especie,
      titulo: titulo.trim(),
      fonte: fonte.trim(),
      texto,
      ...(especieUnica(especie) || !numero.trim() ? {} : { numero: numero.trim() }),
      ...(data ? { data } : {}),
      ...(consolidada ? { "consolidada-ate": consolidada } : {}),
    });
    setEnviando(false);
    if (r.ok) router.push(comToken(`/normas/versoes/${r.dado.versao.id}`, token));
    else setErro(r.mensagem);
  }

  const falta = !titulo.trim() || !fonte.trim() || !texto.trim();
  return (
    <main className="envelope normas">
      <header className="normas-cabeca">
        <div>
          <h1>Importar texto de norma</h1>
          <p className="normas-sub">
            Cole o texto como está publicado. O sistema separa artigos, parágrafos, incisos e alíneas e mostra o que
            merece atenção. Nada vale até alguém conferir e publicar.
          </p>
        </div>
      </header>
      <form
        className="normas-form"
        onSubmit={(e) => {
          e.preventDefault();
          if (!falta && !enviando) void enviar();
        }}
      >
        <div className="normas-campo">
          <label htmlFor="n-especie">Espécie</label>
          <select id="n-especie" value={especie} onChange={(e) => trocarEspecie(e.target.value)}>
            {ESPECIES_DA_CASA.map((e) => <option key={e.valor} value={e.valor}>{e.rotulo}</option>)}
          </select>
        </div>
        <div className="normas-campo">
          <label htmlFor="n-titulo">Título</label>
          <input id="n-titulo" value={titulo} maxLength={300} onChange={(e) => setTitulo(e.target.value)} />
        </div>
        <div className="normas-linha">
          {!especieUnica(especie) && (
            <div className="normas-campo">
              <label htmlFor="n-numero">Número</label>
              <input id="n-numero" value={numero} maxLength={40} placeholder="Ex.: 1.234" onChange={(e) => setNumero(e.target.value)} />
            </div>
          )}
          <div className="normas-campo">
            <label htmlFor="n-data">Data</label>
            <input id="n-data" type="date" value={data} onChange={(e) => setData(e.target.value)} />
          </div>
          <div className="normas-campo">
            <label htmlFor="n-consolidada">Consolidada até</label>
            <input id="n-consolidada" type="date" value={consolidada} onChange={(e) => setConsolidada(e.target.value)} />
          </div>
        </div>
        <div className="normas-campo">
          <label htmlFor="n-fonte">De onde veio o texto</label>
          <input id="n-fonte" value={fonte} maxLength={500} placeholder="Endereço do site oficial, ou “enviado pela Casa”"
            onChange={(e) => setFonte(e.target.value)} />
        </div>
        <div className="normas-campo">
          <label htmlFor="n-texto">Texto</label>
          <textarea id="n-texto" value={texto} rows={14} onChange={(e) => setTexto(e.target.value)}
            placeholder={"Art. 1º …\n§ 1º …\nI – …"} />
          <label className="normas-arquivo">
            Ou carregar arquivo de texto (.txt)
            <input type="file" accept=".txt,.md,text/plain" onChange={(e) => void lerArquivo(e.target.files?.[0])} />
          </label>
        </div>
        {erro && <p className="normas-erro" role="alert">{erro}</p>}
        <div className="normas-acoes">
          <button type="submit" className="btn btn-primaria" disabled={falta || enviando}>
            {enviando ? "Enviando…" : "Enviar para conferência"}
          </button>
          <Link className="btn btn-fantasma" href={comToken("/normas", token)}>Cancelar</Link>
        </div>
      </form>
    </main>
  );
}
