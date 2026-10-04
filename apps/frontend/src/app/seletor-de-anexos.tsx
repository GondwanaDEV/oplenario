"use client";

// O seletor de arquivos que acompanham um ATO: a resposta da Casa (responder, indeferir, decidir o recurso, ou anexar
// depois) e o pedido do cidadão (formulários do portal e "Meus protocolos"). Até 5 arquivos de até 10 MB, nove formatos,
// ditos em texto. Barra ANTES de enviar o que não pode ir (tipo fora da lista, vazio, grande demais, o sexto), com o
// motivo; o servidor confere tudo de novo. Os arquivos só SOBEM depois que o ato dá certo (a rota de anexo pede o
// protocolo já gravado): aqui só se escolhe. O rótulo e a dica mudam de tela; a regra não.

import { useEffect, useId, useRef, useState } from "react";
import {
  ACCEPT_DO_SELETOR,
  LIMITE_DE_ANEXOS,
  TIPOS_ACEITOS_EM_TEXTO,
  adicionarArquivos,
} from "@/lib/anexos-do-atendimento";
import { tamanhoLegivel } from "@/lib/comunicacao-vista";
import "./anexos-do-atendimento.css";

export function SeletorDeAnexos({
  arquivos,
  aoMudar,
  desabilitado,
  id,
  rotulo = "Anexar arquivos à resposta (opcional)",
  dica = "Eles sobem depois que você enviar, e quem pediu os baixa no protocolo dele.",
}: {
  arquivos: File[];
  aoMudar: (arquivos: File[]) => void;
  desabilitado?: boolean;
  /** O `id` do campo (uma tela pode ter mais de um seletor). Sem ele, um id próprio. */
  id?: string;
  rotulo?: string;
  /** Depois do limite e dos formatos: quando e para quem os arquivos aparecem. */
  dica?: string;
}) {
  const idProprio = useId();
  const idDoCampo = id ?? `anx-${idProprio}`;
  const idDaDica = `${idDoCampo}-dica`;
  // O aviso (recusas, repetidos) vale para A LISTA que a escolha produziu: se a lista muda por outro caminho (remover) ou a
  // tela a zera (o envio), o aviso some junto — nunca fica um alerta velho de uma escolha que ja' nao existe.
  const [avisos, setAvisos] = useState<{ para: File[]; recusados: string[]; repetidos: string[] } | null>(null);
  const campo = useRef<HTMLInputElement>(null);
  const botoes = useRef<(HTMLButtonElement | null)[]>([]);
  const foco = useRef<number | "campo" | null>(null);   // para onde o foco vai depois de um "Remover"
  const vigente = avisos && avisos.para === arquivos ? avisos : null;
  const cheio = arquivos.length >= LIMITE_DE_ANEXOS;

  useEffect(() => {
    if (foco.current === null) return;
    const alvo = foco.current === "campo" ? campo.current : botoes.current[foco.current];
    foco.current = null;
    alvo?.focus();
  }, [arquivos]);

  return (
    <div className="anx-campo">
      <label htmlFor={idDoCampo}>{rotulo}</label>
      <input
        ref={campo}
        id={idDoCampo}
        type="file"
        multiple
        accept={ACCEPT_DO_SELETOR}
        aria-describedby={idDaDica}
        disabled={desabilitado || cheio}
        onChange={(e) => {
          const r = adicionarArquivos(arquivos, Array.from(e.target.files ?? []));
          aoMudar(r.arquivos);
          setAvisos({ para: r.arquivos, recusados: r.recusados, repetidos: r.repetidos });
          e.target.value = ""; // o mesmo arquivo pode ser escolhido de novo depois de removido
        }}
      />
      <p id={idDaDica} className="anx-dica">
        Até {LIMITE_DE_ANEXOS} arquivos de até 10 MB: {TIPOS_ACEITOS_EM_TEXTO}. {dica}
      </p>
      {cheio && !desabilitado && (
        <p className="anx-dica">
          <b>Limite de {LIMITE_DE_ANEXOS} arquivos atingido.</b> Remova um para escolher outro.
        </p>
      )}
      {vigente && vigente.recusados.length > 0 && (
        <div className="anx-erro" role="alert">
          {vigente.recusados.map((m) => (
            <p key={m}>{m}</p>
          ))}
        </div>
      )}
      {vigente && vigente.repetidos.length > 0 && (
        <div className="anx-dica" role="status">
          {vigente.repetidos.map((nome) => (
            <p key={nome}>“{nome}” já estava na lista: não foi incluído de novo.</p>
          ))}
        </div>
      )}
      {arquivos.length > 0 && (
        <ul className="anx-arquivos" aria-label="Arquivos escolhidos">
          {arquivos.map((a, i) => (
            <li key={`${a.name}:${a.size}`}>
              <span className="anx-arquivo-nome">{a.name}</span>
              <span className="anx-arquivo-info">{tamanhoLegivel(a.size)}</span>
              <button
                ref={(el) => {
                  botoes.current[i] = el;
                }}
                type="button"
                className="btn btn-fantasma btn-mini"
                aria-label={`Remover ${a.name}`}
                disabled={desabilitado}
                onClick={() => {
                  // o foco fica na lista: no "Remover" que ocupa o lugar do removido (ou no anterior, se era o ultimo) e,
                  // sem mais arquivos, volta ao campo — nunca cai no <body>
                  const restantes = arquivos.length - 1;
                  foco.current = restantes === 0 ? "campo" : Math.min(i, restantes - 1);
                  aoMudar(arquivos.filter((x) => x !== a));
                }}
              >
                Remover
              </button>
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}
