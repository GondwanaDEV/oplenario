// Rótulo de comissão — o único lugar do frontend que decide o que aparece onde o mockup diz "Comissão".
//
// POR QUE ESTE MÓDULO EXISTE. Não há, em NENHUMA rota do backend, resolução `comissao-id` -> nome
// (verificado em 07/09/2026: `cadastros/diplomat/http/in.clj` expõe apenas `/cadastros/vereadores*` e
// `/cadastros/legislatura-vigente`; a ficha do vereador devolve suas comissões por `nome`/`tipo`/`cargo`
// e DELIBERADAMENTE sem o `id` — `cadastros/wire/out/vereador.clj` `ComissaoDoVereadorOut`). O frontend,
// portanto, não tem de onde tirar o nome: não é preguiça de view-model, é ausência de fonte.
//
// E `legislativo.parecer.comissao_id` é coluna `uuid NOT NULL` (migration 20260620000019), guard ref sem
// FK cross-schema. Ou seja: imprimir o campo é imprimir um UUID na cara de quem assiste — foi o defeito
// #11 do ledger de prontidão (`docs/16-ledger-prontidao.md`), classificado `MATA`, e sobreviveu ao
// conserto de semente porque semear comissões REAIS não cria a resolução que nunca existiu.
//
// A degradação honesta é a mesma que o relator já usava ("Relator designado", parecer-vista.ts): afirmar
// que HÁ uma comissão designada, sem inventar qual. A função aceita um nome porque é assim que ela some
// no dia em que o backend servir um: o parâmetro passa a ter valor e o rótulo genérico deixa de aparecer,
// sem tocar em nenhum componente.

export const COMISSAO_SEM_NOME = "Comissão designada";

// Um id nunca é rótulo. Hoje o único formato que chega é UUID, mas a guarda é por FORMA e não por
// procedência: qualquer valor com cara de identificador degrada, em vez de vazar para a tela.
const FORMA_DE_UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

/** O nome exibível da comissão, ou `null` quando não há nome — id NUNCA conta como nome. */
export function nomeDeComissao(nome: string | null | undefined): string | null {
  const limpo = nome?.trim() ?? "";
  if (limpo.length === 0 || FORMA_DE_UUID.test(limpo)) return null;
  return limpo;
}

/**
 * O rótulo pra onde a comissão aparece SOZINHA (subtítulo da tela, linha da lista de pareceres) e
 * omitir deixaria a frase sem sujeito. Onde o rótulo do campo já diz "Comissão" — o rail do editor —
 * use `nomeDeComissao` e omita a linha inteira quando vier `null`: "Comissão: Comissão designada" é
 * ruído, e a mesma disciplina de omitir já vale pro relator.
 */
export function rotularComissao(nome: string | null | undefined): string {
  return nomeDeComissao(nome) ?? COMISSAO_SEM_NOME;
}
