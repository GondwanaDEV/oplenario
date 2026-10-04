"use client";

// A lista dos ANEXOS da resposta de um protocolo, com o download. É compartilhada pelo balcão (a secretaria) e por
// /meus-protocolos (o requerente): o que muda é só a ROTA do arquivo. Nome, tamanho legível e o FORMATO em palavras
// (nunca o tipo MIME cru). O nome do arquivo é conteúdo de quem enviou: renderizado como TEXTO (o React escapa).
//
// Download: no modo real a tela usa um link direto (o cookie vai junto) e o servidor responde SEMPRE como arquivo
// (`Content-Disposition: attachment`); no modo DEV o token viaja só no header, então baixa pelos bytes.

import { useId, useState } from "react";
import { rotuloDoTipo } from "@/lib/anexos-do-atendimento";
import { tamanhoLegivel } from "@/lib/comunicacao-vista";
import { baixarAnexoComToken } from "@/lib/use-atendimento";
import "./anexos-do-atendimento.css";

export type AnexoDaLista = { id: string; nome: string; tipoMidia: string; bytes: number };

export function ListaDeAnexos({
  anexos,
  rotaDe,
  token,
  Titulo = "h2",
}: {
  anexos: AnexoDaLista[] | null | undefined;
  /** O endereço do download de um anexo (a rota da secretaria ou a do requerente). */
  rotaDe: (anexoId: string) => string;
  token: string | null;
  Titulo?: "h2" | "h3";
}) {
  const [erro, setErro] = useState<string | null>(null);
  const idTitulo = useId(); // varios protocolos na mesma pagina: um id por lista
  if (!anexos || anexos.length === 0) return null;
  return (
    <section className="anx" aria-labelledby={idTitulo}>
      <Titulo id={idTitulo} className="anx-titulo">
        Anexos da resposta
      </Titulo>
      <ul aria-label="Anexos da resposta">
        {anexos.map((a) => (
          <li key={a.id}>
            <span className="anx-nome">{a.nome}</span>
            <span className="anx-info">{[tamanhoLegivel(a.bytes), rotuloDoTipo(a.tipoMidia)].filter(Boolean).join(" · ")}</span>
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
