"use client";

// Bloco "Exportar os dados da Câmara" da área do administrador (ADR-0018 fatia 2, 9.6 — portabilidade). Só o
// `admin_ente` gera e baixa; o arquivo é a Câmara inteira em formato aberto. No encerramento, é daqui que a Câmara
// confirma o recebimento — vendo o código (SHA-256) do arquivo que guardou —, e essa confirmação abre a contagem dos
// 90 dias depois dos quais os dados podem ser apagados da plataforma. A confirmação não se desfaz.

import { useState } from "react";
import type { Exportacao } from "@/lib/contrato-exportacao";
import { codigoEmGrupos, tamanho } from "@/lib/encerramento-vista";
import { formatarData, formatarHora } from "@/lib/formatar-data";
import {
  baixarComToken,
  caminhoDoArquivo,
  confirmarRecebimento,
  gerarExportacao,
  nomeDoArquivo,
  useExportacoes,
} from "@/lib/use-exportacao";

type Aviso = { tom: "ok" | "erro"; texto: string } | null;

function quando(iso: string | null): string {
  return iso ? `${formatarData(iso)} às ${formatarHora(iso)}` : "—";
}

export function ExportarDados({ token }: { token: string | null }) {
  const { carga, recarregar } = useExportacoes(token);
  const [aviso, setAviso] = useState<Aviso>(null);
  const [gerandoAgora, setGerandoAgora] = useState(false);

  const dado = carga.fase === "pronto" ? carga.dado : null;
  const emAndamento = !!dado?.exportacoes.some((e) => e.estado === "gerando");

  async function gerar() {
    if (gerandoAgora) return;
    setGerandoAgora(true);
    setAviso(null);
    const r = await gerarExportacao(token);
    setGerandoAgora(false);
    if (!r.ok) return setAviso({ tom: "erro", texto: r.mensagem });
    setAviso({
      tom: "ok",
      texto: r.dado.estado === "pronta"
        ? "Arquivo pronto para baixar."
        : "Exportação pedida. O arquivo está sendo gerado — pode levar alguns minutos; esta página se atualiza sozinha.",
    });
    recarregar();
  }

  return (
    <section className="adm-auditoria adm-exportar" aria-labelledby="adm-exportar-titulo">
      <h2 id="adm-exportar-titulo">Exportar os dados da Câmara</h2>
      <p className="adm-texto">
        Gera o arquivo completo da Câmara em formato aberto: proposições, normas, sessões, atas, votos, pedidos do cidadão,
        os documentos em PDF e a trilha de auditoria com a cadeia que se confere — em CSV e JSON, com o dicionário dos
        dados. Use para guardar uma cópia ou levar os dados para outro sistema. Só o administrador da Câmara baixa este
        arquivo.
      </p>

      {carga.fase === "carregando" && <p role="status" className="adm-texto">Carregando…</p>}
      {carga.fase === "erro" && <p role="alert" className="adm-texto">{carga.mensagem}</p>}

      {dado && !dado.disponivel && (
        <p className="adm-texto">A exportação completa ainda não está disponível nesta instalação. Fale com o suporte da plataforma.</p>
      )}

      {dado?.disponivel && (
        <>
          {dado.emEncerramento && (
            <p className="adm-aviso adm-encerramento" role="note">
              <b>A Câmara está encerrando o uso d’O Plenário.</b> Baixe o arquivo, guarde-o e confira o código. Depois
              confirme o recebimento aqui: a confirmação abre a contagem de 90 dias, e só depois deles os dados podem ser
              apagados da plataforma. Até lá, você pode exportar de novo quando quiser.
            </p>
          )}
          <button type="button" className="btn btn-contorno btn-mini" onClick={() => void gerar()}
            disabled={gerandoAgora || emAndamento} aria-busy={gerandoAgora}>
            {gerandoAgora ? "Pedindo…" : emAndamento ? "Gerando o arquivo…" : "Gerar exportação completa"}
          </button>
          <p role={aviso?.tom === "erro" ? "alert" : "status"} className="adm-texto">{aviso?.texto ?? ""}</p>
          {dado.exportacoes.length === 0 ? (
            <p className="adm-texto">Nenhuma exportação gerada ainda.</p>
          ) : (
            <ul className="adm-lista adm-exportacoes" aria-label="Exportações">
              {dado.exportacoes.map((e) => (
                <ItemExportacao key={e.id} e={e} token={token} emEncerramento={dado.emEncerramento}
                  aoConfirmar={(texto) => {
                    setAviso({ tom: "ok", texto });
                    recarregar();
                  }} />
              ))}
            </ul>
          )}
        </>
      )}
    </section>
  );
}

function ItemExportacao({ e, token, emEncerramento, aoConfirmar }: {
  e: Exportacao; token: string | null; emEncerramento: boolean; aoConfirmar: (texto: string) => void;
}) {
  return (
    <li className="adm-item">
      <div className="adm-linha">
        <span className="adm-quem">
          <b>Pedida em {quando(e.solicitadaEm)}</b>
          <span>
            {e.estado === "gerando" && "Gerando o arquivo…"}
            {e.estado === "falhou" && `A geração falhou${e.erro ? `: ${e.erro}` : ""}. Gere de novo.`}
            {e.estado === "pronta" && `Pronta · ${tamanho(e.bytes)}${e.solicitadaPor === "operador" ? " · pedida pela plataforma" : ""}`}
          </span>
        </span>
        {e.estado === "pronta" && <Baixar e={e} token={token} />}
        {e.confirmadaEm && <span className="adm-chip">Recebimento confirmado</span>}
      </div>
      {e.estado === "pronta" && (
        <div className="adm-codigo">
          <span>Código do arquivo (SHA-256)</span>
          <code>{e.sha256 ? codigoEmGrupos(e.sha256) : "—"}</code>
        </div>
      )}
      {e.estado === "pronta" && !e.confirmadaEm && (
        <Confirmar e={e} token={token} emEncerramento={emEncerramento} aoConfirmar={aoConfirmar} />
      )}
      {e.confirmadaEm && (
        <p className="adm-texto">
          {e.confirmadaPor === "oficio" ? "Recebimento registrado por ofício" : "Recebimento confirmado"} em{" "}
          {formatarData(e.confirmadaEm)}.
        </p>
      )}
    </li>
  );
}

function Baixar({ e, token }: { e: Exportacao; token: string | null }) {
  const [erro, setErro] = useState<string | null>(null);
  const [baixando, setBaixando] = useState(false);
  if (!token) {
    // modo real: o cookie da sessão vai no link, e o arquivo vai direto para o disco
    return (
      <a className="btn btn-contorno btn-mini" href={caminhoDoArquivo(e)} download={nomeDoArquivo(e)}>
        Baixar arquivo
      </a>
    );
  }
  return (
    <>
      <button type="button" className="btn btn-contorno btn-mini" disabled={baixando} aria-busy={baixando}
        onClick={async () => {
          setBaixando(true);
          const r = await baixarComToken(token, e);
          setBaixando(false);
          setErro(r.ok ? null : r.mensagem);
        }}>
        {baixando ? "Baixando…" : "Baixar arquivo"}
      </button>
      {erro && <span role="alert" className="adm-erro">{erro}</span>}
    </>
  );
}

function Confirmar({ e, token, emEncerramento, aoConfirmar }: {
  e: Exportacao; token: string | null; emEncerramento: boolean; aoConfirmar: (texto: string) => void;
}) {
  const [conferido, setConferido] = useState(false);
  const [enviando, setEnviando] = useState(false);
  const [erro, setErro] = useState<string | null>(null);
  const id = `confirmar-${e.id}`;

  async function confirmar(ev: React.FormEvent) {
    ev.preventDefault();
    if (!conferido) return setErro("Marque que a Câmara recebeu o arquivo com este código.");
    setEnviando(true);
    const r = await confirmarRecebimento(token, e);
    setEnviando(false);
    if (!r.ok) return setErro(r.mensagem);
    setErro(null);
    aoConfirmar(emEncerramento
      ? "Recebimento confirmado. A contagem de 90 dias começou hoje."
      : "Recebimento confirmado.");
  }

  return (
    <form className="adm-confirmar" onSubmit={(ev) => void confirmar(ev)} noValidate>
      <label htmlFor={id}>
        <input id={id} type="checkbox" checked={conferido} onChange={(ev) => setConferido(ev.target.checked)} />
        <span>
          Recebemos o arquivo com o código <code>{e.sha256 ? `${e.sha256.slice(0, 8)}…${e.sha256.slice(-8)}` : "—"}</code> e
          o guardamos.
        </span>
      </label>
      {emEncerramento && (
        <p className="adm-texto">A confirmação não se desfaz e abre a contagem de 90 dias para o apagamento.</p>
      )}
      <button type="submit" className="btn btn-contorno btn-mini" disabled={enviando} aria-busy={enviando}>
        {enviando ? "Confirmando…" : "Confirmar recebimento"}
      </button>
      {erro && <p role="alert" className="adm-erro">{erro}</p>}
    </form>
  );
}
