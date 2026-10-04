"use client";

// "Anexar arquivo": o controle avulso do balcão. Os anexos da resposta sobem DEPOIS do ato; se o envio falhar, ou a
// secretaria recarregar a página, ela não pode perder a janela (10 minutos depois do último ato da Casa, até 5 anexos
// da Casa). Por isso, enquanto o servidor diz `pode-anexar`, este controle fica no protocolo, com o MESMO seletor e o
// MESMO envio do formulário da resposta. O servidor decide se ainda cabe: a tela só mostra o controle quando cabe.

import { useId, useState } from "react";
import { SeletorDeAnexos } from "../../seletor-de-anexos";

export function AnexarAvulso({
  aoEnviar,
  enviando,
  anonima = false,
}: {
  aoEnviar: (arquivos: File[]) => void;
  enviando: boolean;
  /** Manifestação anônima: o arquivo fica só no registro da Casa (ninguém o baixa) — a dica diz isso. */
  anonima?: boolean;
}) {
  const [arquivos, setArquivos] = useState<File[]>([]);
  const idTitulo = useId();
  const idCampo = useId();
  return (
    <section className="anx-controle" aria-label="Anexar arquivo">
      <h2 id={idTitulo}>Anexar arquivo</h2>
      <p className="anx-dica">
        Ainda dá para juntar arquivos à resposta: vale até 10 minutos depois da resposta (ou da decisão), e até 5 anexos da Casa.
      </p>
      <SeletorDeAnexos
        id={idCampo}
        rotulo="Escolher arquivos para anexar à resposta"
        dica={anonima
          ? "Eles sobem quando você clicar em anexar. Esta manifestação é anônima: o arquivo fica só no registro da Casa, porque não há quem o baixe."
          : "Eles sobem quando você clicar em anexar, e quem pediu os baixa no protocolo dele."}
        arquivos={arquivos}
        aoMudar={setArquivos}
        desabilitado={enviando}
      />
      <div>
        <button
          type="button"
          className="btn btn-primaria"
          disabled={enviando || arquivos.length === 0}
          onClick={() => {
            const lista = arquivos;
            setArquivos([]); // a selecao vai embora: o resultado de cada arquivo aparece no painel de envio
            aoEnviar(lista);
          }}
        >
          Anexar os arquivos escolhidos
        </button>
      </div>
    </section>
  );
}
