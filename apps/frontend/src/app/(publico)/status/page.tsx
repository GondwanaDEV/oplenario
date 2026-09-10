// Rota pública de STATUS — GET /status. Porte de
// produto/design-system/o-plenario/telas/status.html (arquétipo "leitura pública simples").
// Server Component: a tela é 100% texto fixo, sem binding — não há sonda, nem série, nem hook.
//
// O QUE MUDA EM RELAÇÃO AO HTML DE ORIGEM, e por quê (é a decisão central desta fatia):
// o HTML mostra, ao lado de cada componente, um badge verde "Operacional" com um pontinho — e, na mesma
// linha, "sem medição publicada" na coluna de uptime. As duas coisas se contradizem: a plataforma NÃO
// mede disponibilidade (não há sonda, health-check publicado nem histórico), então o badge seria um
// número/estado fabricado — exatamente o que a regra "nunca mostrar dado falso" proíbe. O próprio design
// já admite isso no banner ("Monitoramento de disponibilidade em configuração — sem histórico de medição
// publicado") e no bloco de incidentes. Esta versão mantém a lista de componentes e o texto honesto, e
// descarta os badges, os pontinhos coloridos e a moldura verde de "tudo certo" (verde é, ele próprio,
// uma afirmação de saúde). Quando existir medição real, a coluna volta a ter conteúdo — não antes.
//
// As três regiões sem backend usam <EmBreve> (padrão sancionado, 21 arquivos): o estado global, a coluna
// de disponibilidade por componente (via o rótulo explícito de ausência em cada linha) e o histórico de
// incidentes.

import { EmBreve } from "@/lib/em-breve";
import "./status.css";

// A ausência de MEDIÇÃO e a ausência do COMPONENTE são fatos diferentes, e a coluna não pode confundir
// os dois: "Sem medição publicada" nega a medição e, ao negá-la, AFIRMA que o componente existe e está
// servido. Quem lê tira daí que o canal está no ar e só não publicam uptime dele.
//
// A API de dados abertos NÃO existe: não há rota `/api` nem endpoint de dados abertos no backend (o que
// `transparencia` expõe é `/portal/casa/...`, o read-model do portal, que já é outra linha desta mesma
// tabela) e não há rota Next. A própria plataforma publica essa ausência ao mesmo cidadão, na mesma
// superfície pública, em navegacao-civica.tsx ("Baixar os dados da Câmara em formato aberto chega numa
// fatia futura de dados abertos"); listá-la aqui como "sem medição" faria as duas telas do grupo
// (publico) se contradizerem para o mesmo leitor. `api/v1/proposicoes` aparece em telas/dados-abertos.html,
// que é DESIGN não implementado — nunca foi um componente em produção.
//
// Por isso cada linha carrega a declaração exata do que falta, e o estado que a classifica. Componente
// que passar a existir (ou a ser medido) troca a sua linha — não se toca no resto.
export const SEM_MEDICAO = "Sem medição publicada";

export const COMPONENTES = [
  { nome: "Portal público e e-SIC", estado: "sem-medicao", declaracao: SEM_MEDICAO },
  { nome: "Sessão ao vivo e votação", estado: "sem-medicao", declaracao: SEM_MEDICAO },
  {
    nome: "API de dados abertos",
    estado: "inexistente",
    declaracao: "Ainda não existe — chega numa fatia futura de dados abertos",
  },
  { nome: "Painel interno e tramitação", estado: "sem-medicao", declaracao: SEM_MEDICAO },
  { nome: "Geração de documentos", estado: "sem-medicao", declaracao: SEM_MEDICAO },
] as const;

export default function PaginaStatus() {
  return (
    <>
      <a className="pular" href="#conteudo">
        Pular para o conteúdo
      </a>
      <main id="conteudo" className="envelope st-pagina">
        <header className="st-topo">
          {/* paleta-marca do azulejo (tokens.css §"paleta-marca FIXA") — é identidade, não tematiza;
              mesmos tokens, nunca hex literal (status.css: "Nenhuma cor literal: só tokens"). */}
          <svg className="st-marca" viewBox="0 0 34 34" role="img" aria-label="O Plenário">
            <rect width="34" height="34" rx="8" fill="var(--jade)" />
            <rect x="7" y="7" width="9" height="9" rx="2" fill="var(--telha)" />
            <rect x="18" y="7" width="9" height="9" rx="2" fill="var(--cobalto)" />
            <rect x="7" y="18" width="9" height="9" rx="2" fill="var(--amarelo)" />
            <rect x="18" y="18" width="9" height="9" rx="2" fill="var(--jade-claro)" />
          </svg>
          <h1>
            Status do sistema <span>· O Plenário</span>
          </h1>
        </header>

        <EmBreve
          titulo="Disponibilidade da plataforma"
          motivo="A plataforma está em implantação e o monitoramento de disponibilidade ainda está em configuração: nenhuma medição de disponibilidade é apurada ou publicada hoje. Enquanto isso, esta página não afirma nem nega que os componentes estejam no ar — só diz o que se sabe."
        />

        <section className="st-secao" aria-labelledby="componentes-titulo">
          <h2 id="componentes-titulo">Componentes</h2>
          <div className="st-tabela-rolagem">
            <table className="st-tabela">
              <caption className="sr-only">
                Componentes da plataforma e o estado da medição de disponibilidade de cada um
              </caption>
              <thead>
                <tr>
                  <th scope="col">Componente</th>
                  <th scope="col">Disponibilidade</th>
                </tr>
              </thead>
              <tbody>
                {COMPONENTES.map((componente) => (
                  <tr key={componente.nome}>
                    <th scope="row">{componente.nome}</th>
                    {/* Ausência declarada em TEXTO, nunca um badge de saúde: ver o cabeçalho deste
                        arquivo. A célula é só texto — nenhum filho, nenhum atributo de estado. */}
                    <td
                      className={
                        componente.estado === "inexistente" ? "st-inexistente" : "st-sem-medicao"
                      }
                    >
                      {componente.declaracao}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </section>

        <section className="st-secao" aria-labelledby="incidentes-titulo">
          <h2 id="incidentes-titulo">Incidentes recentes</h2>
          <EmBreve
            titulo="Histórico de incidentes"
            motivo="Nada é publicado aqui porque o monitoramento de produção ainda não está em operação — a ausência de incidentes nesta página não significa que não houve nenhum. O histórico passa a ser publicado quando a medição entrar no ar."
          />
        </section>

        <footer className="st-rodape">
          Página operada pela plataforma O Plenário. Para o andamento de um processo ou de um pedido de
          informação, use o portal da sua Câmara.
        </footer>
      </main>
    </>
  );
}
