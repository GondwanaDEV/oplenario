"use client";

// "Anexar ao pedido": a cidadã junta arquivos ao PRÓPRIO protocolo (e-SIC, LGPD, ouvidoria identificada) em
// /meus-protocolos. A regra vem do servidor e esta tela só a diz em palavras: até 10 minutos depois de enviar o
// pedido, até 5 arquivos, os mesmos formatos e tamanho do balcão. O servidor manda `podeAnexar`; o controle só aparece
// enquanto cabe. Os arquivos sobem um a um (a rota recebe um por chamada) e cada resultado fica à vista, com o motivo
// da falha e o "tentar de novo". O resultado continua na tela mesmo que o protocolo deixe de aceitar anexos no meio
// do envio (o 5º arquivo fecha a conta), para a cidadã não perder a explicação do que não foi.

import { useId, useState } from "react";
import { anexarAoMeuProtocolo, type EspecieDoPortal } from "@/lib/use-meus-protocolos";
import { useEnvioDeAnexos } from "@/lib/use-envio-de-anexos";
import { PainelDeEnvio } from "../../envio-de-anexos";
import { SeletorDeAnexos } from "../../seletor-de-anexos";

export function AnexarAoProtocolo({
  especie,
  id,
  podeAnexar,
  token,
  aoMudar,
}: {
  especie: EspecieDoPortal;
  id: string;
  /** Do servidor: ainda cabe anexar a este protocolo (janela de 10 minutos e o limite de 5 do requerente). */
  podeAnexar: boolean;
  token: string | null;
  /** Depois do envio: relê a lista (os anexos novos aparecem, e o `podeAnexar` volta atualizado). */
  aoMudar: () => void;
}) {
  const [arquivos, setArquivos] = useState<File[]>([]);
  const idCampo = useId();
  const { itens, enviar, tentarDeNovo, enviando } = useEnvioDeAnexos(async (arquivo) => {
    const r = await anexarAoMeuProtocolo(token, especie, id, arquivo);
    return r.ok ? { ok: true } : { ok: false, mensagem: r.mensagem };
  }, aoMudar);

  if (!podeAnexar && itens.length === 0) return null;
  return (
    <>
      {podeAnexar && (
        <fieldset className="anx-controle">
          <legend>Anexar ao pedido</legend>
          <p className="anx-dica">
            Você pode juntar arquivos ao pedido até 10 minutos depois de enviar o pedido. Passado esse tempo, o protocolo
            não aceita mais anexos.
          </p>
          <SeletorDeAnexos
            id={idCampo}
            rotulo="Anexar arquivos ao pedido"
            dica="Eles ficam junto com o pedido, e a Câmara os lê no protocolo."
            arquivos={arquivos}
            aoMudar={setArquivos}
            desabilitado={enviando}
          />
          <div>
            <button
              type="button"
              className="btn btn-primaria btn-mini"
              disabled={enviando || arquivos.length === 0}
              onClick={() => {
                const lista = arquivos;
                setArquivos([]); // a seleção vai embora: o resultado de cada arquivo aparece logo abaixo
                void enviar(lista);
              }}
            >
              Enviar os arquivos
            </button>
          </div>
        </fieldset>
      )}
      <PainelDeEnvio
        itens={itens}
        podeTentarDeNovo={podeAnexar && !enviando}
        aoTentarDeNovo={(i) => void tentarDeNovo(i)}
        semJanela="Já não dá para anexar: passaram os 10 minutos depois de enviar o pedido, ou o pedido já tem 5 anexos seus."
      />
    </>
  );
}
