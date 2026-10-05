"use client";

// A lista dos ANEXOS de um protocolo (da Casa na resposta, do requerente no pedido), com o download. É compartilhada pelo balcão (a secretaria) e por
// /meus-protocolos (o requerente): o que muda é só a ROTA do arquivo. Nome, tamanho legível e o FORMATO em palavras
// (nunca o tipo MIME cru). O nome do arquivo é conteúdo de quem enviou: renderizado como TEXTO (o React escapa).
//
// Download: no modo real a tela usa um link direto (o cookie vai junto) e o servidor responde SEMPRE como arquivo
// (`Content-Disposition: attachment`); no modo DEV o token viaja só no header, então baixa pelos bytes.

// RETIRADO (incidente de conteúdo, pela secretaria): a linha segue na lista como registro — nome, tamanho, "Retirado em <data>"
// — mas sem link e sem botão: o arquivo saiu do armazenamento e o download é 404 para todos. O MOTIVO só chega ao balcão
// (`motivoDaRetirada`); o requerente vê só que foi retirado e quando. No balcão (`aoRetirar`), cada anexo vigente ganha
// "Retirar": motivo obrigatório, confirmação, e a lista se relê.
//
// SUBSTITUÍDO (ADR-0022): a secretaria trocou o arquivo errado pelo certo. O antigo aparece como "Substituído em <data>" (a data da
// troca é a da retirada; o motivo, só no balcão) e o novo vem logo abaixo, com download. No balcão (`aoSubstituir`), cada anexo
// vigente DA CASA ganha "Substituir" ao lado de "Retirar": motivo obrigatório + UM arquivo, aviso de que não se desfaz. O do
// requerente só se retira.

import { useEffect, useId, useRef, useState } from "react";
import { motivoDeRecusa, ACCEPT_DO_SELETOR, ordenarComSubstituicoes, rotuloDoTipo, TIPOS_ACEITOS_EM_TEXTO } from "@/lib/anexos-do-atendimento";
import { tamanhoLegivel } from "@/lib/comunicacao-vista";
import { formatarData } from "@/lib/formatar-data";
import { baixarAnexoComToken } from "@/lib/use-atendimento";
import "./anexos-do-atendimento.css";

export type AnexoDaLista = {
  id: string;
  nome: string;
  tipoMidia: string;
  bytes: number;
  origem?: string;
  retiradoEm?: string | null;
  motivoDaRetirada?: string | null;
  substituidoPor?: string | null;
};

/** O formulário de retirada de UM anexo: motivo obrigatório + confirmação. `aoConfirmar` devolve a frase do erro, ou null se deu certo. */
function RetirarAnexo({
  nome,
  aoConfirmar,
  aoCancelar,
}: {
  nome: string;
  aoConfirmar: (motivo: string) => Promise<string | null>;
  aoCancelar: () => void;
}) {
  const idCampo = useId();
  const [motivo, setMotivo] = useState("");
  const [enviando, setEnviando] = useState(false);
  const [erro, setErro] = useState<string | null>(null);
  return (
    <div className="anx-retirar" role="group" aria-label={`Retirar o anexo ${nome}`}>
      <p className="anx-dica">
        A retirada não pode ser desfeita: o arquivo sai do armazenamento e ninguém mais o baixa. O motivo fica registrado e só a
        secretaria o lê.
      </p>
      <div className="anx-campo">
        <label htmlFor={idCampo}>Motivo da retirada</label>
        <textarea id={idCampo} value={motivo} rows={3} maxLength={1000} onChange={(e) => setMotivo(e.target.value)} />
      </div>
      {erro && <p className="anx-erro" role="alert">{erro}</p>}
      <div className="anx-retirar-acoes">
        <button
          type="button"
          className="btn btn-primaria btn-mini"
          disabled={enviando || motivo.trim() === ""}
          onClick={async () => {
            setEnviando(true);
            setErro(null);
            const e = await aoConfirmar(motivo.trim());
            setEnviando(false);
            if (e) setErro(e);
          }}
        >
          {enviando ? "Retirando…" : "Confirmar a retirada"}
        </button>
        <button type="button" className="btn btn-fantasma btn-mini" disabled={enviando} onClick={aoCancelar}>
          Cancelar a retirada
        </button>
      </div>
    </div>
  );
}

/** O formulário de SUBSTITUIÇÃO de UM anexo da Casa: motivo obrigatório + UM arquivo novo + confirmação. Mesmo padrão da retirada
 *  (grupo nomeado, aviso de que não se desfaz, erro em `role=alert` que não apaga o que foi digitado). O foco entra no motivo ao abrir.
 *  `aoConfirmar` devolve a frase do erro, ou null se deu certo. */
function SubstituirAnexo({
  nome,
  aoConfirmar,
  aoCancelar,
}: {
  nome: string;
  aoConfirmar: (arquivo: File, motivo: string) => Promise<string | null>;
  aoCancelar: () => void;
}) {
  const idMotivo = useId();
  const idArquivo = useId();
  const refMotivo = useRef<HTMLTextAreaElement>(null);
  const [motivo, setMotivo] = useState("");
  const [arquivo, setArquivo] = useState<File | null>(null);
  const [enviando, setEnviando] = useState(false);
  const [erro, setErro] = useState<string | null>(null);
  useEffect(() => {
    refMotivo.current?.focus();
  }, []);
  return (
    <div className="anx-retirar" role="group" aria-label={`Substituir o anexo ${nome}`}>
      <p className="anx-dica">
        A substituição não pode ser desfeita: o arquivo atual sai do armazenamento e ninguém mais o baixa, e o arquivo novo toma o
        lugar dele. Vale a qualquer tempo. O motivo fica registrado e só a secretaria o lê.
      </p>
      <div className="anx-campo">
        <label htmlFor={idMotivo}>Motivo da substituição</label>
        <textarea ref={refMotivo} id={idMotivo} value={motivo} rows={3} maxLength={1000} onChange={(e) => setMotivo(e.target.value)} />
      </div>
      <div className="anx-campo">
        <label htmlFor={idArquivo}>Arquivo novo</label>
        <input
          id={idArquivo}
          type="file"
          accept={ACCEPT_DO_SELETOR}
          aria-describedby={`${idArquivo}-dica`}
          onChange={(e) => {
            const a = e.target.files?.[0] ?? null;
            setErro(a ? motivoDeRecusa(a) : null);
            setArquivo(a && !motivoDeRecusa(a) ? a : null);
          }}
        />
        <p id={`${idArquivo}-dica`} className="anx-dica">
          Um arquivo de até 10 MB: {TIPOS_ACEITOS_EM_TEXTO}.
        </p>
      </div>
      {erro && <p className="anx-erro" role="alert">{erro}</p>}
      <div className="anx-retirar-acoes">
        <button
          type="button"
          className="btn btn-primaria btn-mini"
          disabled={enviando || motivo.trim() === "" || !arquivo}
          onClick={async () => {
            if (!arquivo) return;
            setEnviando(true);
            setErro(null);
            const e = await aoConfirmar(arquivo, motivo.trim());
            setEnviando(false);
            if (e) setErro(e);
          }}
        >
          {enviando ? "Substituindo…" : "Confirmar a substituição"}
        </button>
        <button type="button" className="btn btn-fantasma btn-mini" disabled={enviando} onClick={aoCancelar}>
          Cancelar a substituição
        </button>
      </div>
    </div>
  );
}

export function ListaDeAnexos({
  anexos,
  rotaDe,
  token,
  Titulo = "h2",
  titulo = "Anexos da resposta",
  aoRetirar,
  aoSubstituir,
}: {
  anexos: AnexoDaLista[] | null | undefined;
  /** O endereço do download de um anexo (a rota da secretaria ou a do requerente). */
  rotaDe: (anexoId: string) => string;
  token: string | null;
  Titulo?: "h2" | "h3";
  /** "Anexos da resposta" (a Casa) | "Anexos do pedido" (o requerente, no balcão) | "Seus anexos" (o requerente, no portal). */
  titulo?: string;
  /** Só no balcão: retirar o anexo `anexoId` com o `motivo`. Devolve a frase do erro, ou null se deu certo. */
  aoRetirar?: (anexoId: string, motivo: string) => Promise<string | null>;
  /** Só no balcão, só para anexo da Casa: trocar o anexo `anexoId` por `arquivo`, com o `motivo`. Devolve a frase do erro, ou null. */
  aoSubstituir?: (anexoId: string, arquivo: File, motivo: string) => Promise<string | null>;
}) {
  const [erro, setErro] = useState<string | null>(null);
  // um formulário aberto por vez, em toda a lista: retirar OU substituir, de UM anexo
  const [aberto, setAberto] = useState<{ id: string; qual: "retirar" | "substituir" } | null>(null);
  const idTitulo = useId(); // varios protocolos na mesma pagina: um id por lista
  if (!anexos || anexos.length === 0) return null;
  return (
    <section className="anx" aria-labelledby={idTitulo}>
      <Titulo id={idTitulo} className="anx-titulo">
        {titulo}
      </Titulo>
      <ul aria-label={titulo}>
        {ordenarComSubstituicoes(anexos).map((a) => (
          <li key={a.id}>
            <span className="anx-nome">{a.nome}</span>
            <span className="anx-info">{[tamanhoLegivel(a.bytes), rotuloDoTipo(a.tipoMidia)].filter(Boolean).join(" · ")}</span>
            {a.retiradoEm ? (
              <>
                <span className="anx-retirado">
                  {a.substituidoPor ? "Substituído" : "Retirado"} em {formatarData(a.retiradoEm)}
                </span>
                {a.motivoDaRetirada && <span className="anx-motivo">Motivo: {a.motivoDaRetirada}</span>}
              </>
            ) : (
              <>
                {token ? (
                  <button
                    type="button"
                    className="btn btn-contorno btn-mini"
                    aria-label={`Baixar ${a.nome}`}
                    onClick={async () => {
                      setErro(null);
                      const r = await baixarAnexoComToken(token, rotaDe(a.id), a.nome);
                      if (!r.ok) setErro(r.mensagem);
                    }}
                  >
                    Baixar
                  </button>
                ) : (
                  <a className="btn btn-contorno btn-mini" href={rotaDe(a.id)} download={a.nome} aria-label={`Baixar ${a.nome}`}>
                    Baixar
                  </a>
                )}
                {aoSubstituir && a.origem === "casa" && aberto?.id !== a.id && (
                  <button
                    type="button"
                    className="btn btn-fantasma btn-mini"
                    aria-label={`Substituir ${a.nome}`}
                    onClick={() => setAberto({ id: a.id, qual: "substituir" })}
                  >
                    Substituir
                  </button>
                )}
                {aoRetirar && aberto?.id !== a.id && (
                  <button
                    type="button"
                    className="btn btn-fantasma btn-mini"
                    aria-label={`Retirar ${a.nome}`}
                    onClick={() => setAberto({ id: a.id, qual: "retirar" })}
                  >
                    Retirar
                  </button>
                )}
              </>
            )}
            {aoRetirar && aberto?.id === a.id && aberto.qual === "retirar" && !a.retiradoEm && (
              <RetirarAnexo
                nome={a.nome}
                aoConfirmar={async (motivo) => {
                  const e = await aoRetirar(a.id, motivo);
                  if (!e) setAberto(null);
                  return e;
                }}
                aoCancelar={() => setAberto(null)}
              />
            )}
            {aoSubstituir && aberto?.id === a.id && aberto.qual === "substituir" && !a.retiradoEm && (
              <SubstituirAnexo
                nome={a.nome}
                aoConfirmar={async (arquivo, motivo) => {
                  const e = await aoSubstituir(a.id, arquivo, motivo);
                  if (!e) setAberto(null);
                  return e;
                }}
                aoCancelar={() => setAberto(null)}
              />
            )}
          </li>
        ))}
      </ul>
      {erro && (
        <p className="anx-erro" role="alert">
          {erro}
        </p>
      )}
    </section>
  );
}
