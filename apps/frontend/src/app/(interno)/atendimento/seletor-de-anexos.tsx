"use client";

// O seletor de arquivos que acompanham a RESPOSTA (responder, indeferir, decidir o recurso): até 5 arquivos de até 10 MB,
// nove formatos, ditos em texto. Barra ANTES de enviar o que não pode ir (tipo fora da lista, vazio, grande demais, o
// sexto), com o motivo; o servidor confere tudo de novo. Os arquivos só SOBEM depois que o ato dá certo (a rota de anexo
// pede a resposta já gravada): aqui só se escolhe.

import { useState } from "react";
import {
  ACCEPT_DO_SELETOR,
  LIMITE_DE_ANEXOS,
  TIPOS_ACEITOS_EM_TEXTO,
  adicionarArquivos,
} from "@/lib/anexos-do-atendimento";
import { tamanhoLegivel } from "@/lib/comunicacao-vista";

export function SeletorDeAnexos({
  arquivos,
  aoMudar,
  desabilitado,
}: {
  arquivos: File[];
  aoMudar: (arquivos: File[]) => void;
  desabilitado?: boolean;
}) {
  const [recusados, setRecusados] = useState<string[]>([]);

  return (
    <div className="atd-campo atd-anexos">
      <label htmlFor="atd-anexos">Anexar arquivos à resposta (opcional)</label>
      <input
        id="atd-anexos"
        type="file"
        multiple
        accept={ACCEPT_DO_SELETOR}
        disabled={desabilitado || arquivos.length >= LIMITE_DE_ANEXOS}
        onChange={(e) => {
          const r = adicionarArquivos(arquivos, Array.from(e.target.files ?? []));
          aoMudar(r.arquivos);
          setRecusados(r.recusados);
          e.target.value = ""; // o mesmo arquivo pode ser escolhido de novo depois de removido
        }}
      />
      <p className="atd-dica">
        Até {LIMITE_DE_ANEXOS} arquivos de até 10 MB: {TIPOS_ACEITOS_EM_TEXTO}. Eles sobem depois que você enviar, e quem pediu
        os baixa no protocolo dele.
      </p>
      {recusados.length > 0 && (
        <div className="atd-erro" role="alert">
          {recusados.map((m) => (
            <p key={m}>{m}</p>
          ))}
        </div>
      )}
      {arquivos.length > 0 && (
        <ul className="atd-arquivos" aria-label="Arquivos escolhidos">
          {arquivos.map((a) => (
            <li key={`${a.name}:${a.size}`}>
              <span className="atd-arquivo-nome">{a.name}</span>
              <span className="atd-arquivo-info">{tamanhoLegivel(a.size)}</span>
              <button
                type="button"
                className="btn btn-fantasma btn-mini"
                aria-label={`Remover ${a.name}`}
                disabled={desabilitado}
                onClick={() => aoMudar(arquivos.filter((x) => x !== a))}
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
