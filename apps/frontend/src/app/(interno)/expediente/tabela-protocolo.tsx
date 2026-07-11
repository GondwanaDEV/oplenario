// TabelaProtocolo — o "Livro do Protocolo Geral" (feature 3.23, expediente.html:489-544). Lista o ANO
// CORRENTE inteiro (o backend resolve o ano via kernel/tempo, GET /legislativo/protocolo-geral) — o mockup
// chama a seção "hoje", mas o contrato real não filtra por dia; o subtítulo aqui é honesto sobre o que é
// (o livro do ano corrente), não "hoje" (evitar prometer um filtro que não existe).

import { corObjetoTipo, formatarNumeroProtocolo, rotularObjetoTipo, rotularSentido } from "@/lib/expediente-vista";
import { formatarHora } from "@/lib/formatar-data";
import type { ProtocoloGeralOut } from "@/lib/contrato-legislativo.gen";

export function TabelaProtocolo({ itens }: { itens: ProtocoloGeralOut[] }) {
  return (
    <section className="bloco livro" aria-labelledby="livro-titulo">
      <div className="bloco-cabeca">
        <h2 id="livro-titulo">Livro do Protocolo Geral — ano corrente</h2>
        <span className="sub">numerador único · proposições e documentos administrativos · entradas e saídas</span>
      </div>
      {itens.length === 0 ? (
        <div className="bloco-corpo">
          <p>Nenhum registro no Protocolo Geral deste ano ainda.</p>
        </div>
      ) : (
        <div className="tabela-rolagem">
          <table className="protocolo">
            <thead>
              <tr>
                <th scope="col">Nº protocolo</th>
                <th scope="col">Tipo</th>
                <th scope="col">Sentido</th>
                <th scope="col">Descrição</th>
                <th scope="col">Hora</th>
              </tr>
            </thead>
            <tbody>
              {itens.map((item) => {
                const sentido = rotularSentido(item.sentido);
                return (
                  <tr key={item.id}>
                    <td className="np">{formatarNumeroProtocolo(item.numero, item.ano)}</td>
                    <td>
                      <span className={`tipo-doc tipo-doc-${corObjetoTipo(item.objetoTipo)}`}>
                        <span className="pt" aria-hidden="true" />
                        {rotularObjetoTipo(item.objetoTipo)}
                      </span>
                    </td>
                    <td>
                      <span className={`sentido sentido-${sentido.direcao}`}>{sentido.rotulo}</span>
                    </td>
                    <td className="desc">{item.assunto}</td>
                    <td className="hora">{formatarHora(item.protocoladoEm)}</td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        </div>
      )}
    </section>
  );
}
