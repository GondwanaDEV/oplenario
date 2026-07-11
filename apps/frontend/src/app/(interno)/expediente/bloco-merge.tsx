// BlocoMerge — "Preenchido pelo cadastro" no mockup (expediente.html:415-429), READ-ONLY. DESVIO
// deliberado: o mockup mostra campos resolvidos AUTOMATICAMENTE do cadastro (Remetente/Signatária/Local e
// data/Numeração) — essa resolução automática não existe nesta fatia (o backend não expõe uma rota de
// "fatos do cadastro"; `dados`, o mapa que POST /legislativo/documentos recebe, é digitado à mão no
// formulário de preenchimento, ver formulario-preenchimento.tsx). Em vez de fingir uma origem que não
// existe, este bloco ecoa HONESTAMENTE os pares chave/valor que o próprio usuário acabou de digitar como
// merge desta geração — real, read-only (a edição acontece lá em cima, antes de gerar; depois de gerado o
// merge já foi aplicado ao `corpo`, não há mais o que editar aqui), nunca inventado. `carimbo` (o texto do
// Protocolo Geral — "a reservar" ou o número real) é sempre real (expediente-vista.ts/textoCarimbo).

export function BlocoMerge({ dados, carimbo }: { dados: Record<string, string>; carimbo: string }) {
  const pares = Object.entries(dados);
  return (
    <section className="bloco dominio" aria-labelledby="dominio-titulo">
      <div className="bloco-cabeca">
        <h2 id="dominio-titulo">Campos mesclados nesta geração</h2>
        <span className="passo">
          <span className="tag-cadastro">
            <span className="pin" aria-hidden="true" />
            merge
          </span>
        </span>
      </div>
      <div className="bloco-corpo">
        <ul className="merge-lista">
          {pares.length === 0 ? (
            <li>
              <span className="rot">Dados</span>
              <span className="val aberto">
                Nenhum campo mesclado nesta geração — o modelo escolhido não tem placeholder ou o formulário
                de preenchimento ainda está vazio.
              </span>
            </li>
          ) : (
            pares.map(([chave, valor]) => (
              <li key={chave}>
                <span className="rot">{chave}</span>
                <span className="val">{valor}</span>
              </li>
            ))
          )}
          <li>
            <span className="rot">Protocolo Geral</span>
            <span className={carimbo === "a reservar" ? "val aberto" : "val"}>{carimbo}</span>
          </li>
        </ul>
      </div>
    </section>
  );
}
