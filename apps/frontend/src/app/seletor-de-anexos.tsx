"use client";

// O seletor de arquivos que acompanham um ATO: a resposta da Casa (responder, indeferir, decidir o recurso, ou anexar
// depois) e o pedido do cidadão (formulários do portal e "Meus protocolos"). Até 5 arquivos de até 10 MB, nove formatos,
// ditos em texto. Barra ANTES de enviar o que não pode ir (tipo fora da lista, vazio, grande demais, o sexto), com o
// motivo; o servidor confere tudo de novo. Os arquivos só SOBEM depois que o ato dá certo (a rota de anexo pede o
// protocolo já gravado): aqui só se escolhe. O rótulo e a dica mudam de tela; a regra não.

import { useState } from "react";
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
  id = "anx-arquivos",
  rotulo = "Anexar arquivos à resposta (opcional)",
  dica = "Eles sobem depois que você enviar, e quem pediu os baixa no protocolo dele.",
}: {
  arquivos: File[];
  aoMudar: (arquivos: File[]) => void;
  desabilitado?: boolean;
  /** O `id` do campo (uma tela pode ter mais de um seletor). */
  id?: string;
  rotulo?: string;
  /** Depois do limite e dos formatos: quando e para quem os arquivos aparecem. */
  dica?: string;
}) {
  const [recusados, setRecusados] = useState<string[]>([]);

  return (
    <div className="anx-campo">
      <label htmlFor={id}>{rotulo}</label>
      <input
        id={id}
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
      <p className="anx-dica">
        Até {LIMITE_DE_ANEXOS} arquivos de até 10 MB: {TIPOS_ACEITOS_EM_TEXTO}. {dica}
      </p>
      {recusados.length > 0 && (
        <div className="anx-erro" role="alert">
          {recusados.map((m) => (
            <p key={m}>{m}</p>
          ))}
        </div>
      )}
      {arquivos.length > 0 && (
        <ul className="anx-arquivos" aria-label="Arquivos escolhidos">
          {arquivos.map((a) => (
            <li key={`${a.name}:${a.size}`}>
              <span className="anx-arquivo-nome">{a.name}</span>
              <span className="anx-arquivo-info">{tamanhoLegivel(a.size)}</span>
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
