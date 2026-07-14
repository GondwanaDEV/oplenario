(ns oplenario.cadastros.adapters.out.vereador
  "Gate de SAIDA `models -> wire/out` da leitura de vereadores (§22.10 adapters/out, ADR-0001, Onda D Slice
  3). Projeta as linhas do Repo (kebab, uuid/LocalDate) para VereadorLinhaOut/VereadorFichaOut — nunca vaza
  ente-id/identidade-id/legislatura-id/mandato-id. `cargo-mesa` na ficha tem UMA fonte: derivado aqui de
  `comissoes` (a entrada tipo=\"mesa\"), nunca lido do mandato. Validado contra o contrato (drift de campo =
  bug de servidor -> 500, nunca resposta malformada que envenena o codegen)."
  (:require [malli.core :as m]
            [malli.error :as me]
            [oplenario.cadastros.wire.out.vereador :as wire]))

(set! *warn-on-reflection* true)

(defn- ->str [x] (some-> x str))

(defn- validado [schema out tipo]
  (when-not (m/validate schema out)
    (throw (ex-info (str "projecao de " tipo " viola o contrato wire/out (bug de servidor)")
                    {:campos (keys (me/humanize (m/explain schema out)))})))
  out)

(defn- linha->wire [linha]
  (validado wire/VereadorLinhaOut
            {:id (->str (:id linha)) :nome (:nome linha) :nome-parlamentar (:nome-parlamentar linha)
             :partido (:partido linha) :estado-mandato (:estado-mandato linha) :cargo-mesa (:cargo-mesa linha)}
            "linha de vereador"))

(defn lista->wire
  "Seq de linhas (dominio, `Repo/listar-vereadores`) -> seq de VereadorLinhaOut validado. Uso interno de
  `lista-envelope->wire`; mantida publica por compat de teste unitario."
  [rows]
  (mapv linha->wire rows))

(defn lista-envelope->wire
  "Seq de linhas -> o ENVELOPE {:vereadores [...]} de GET /cadastros/vereadores, validado inteiro contra
  wire/ListaVereadoresOut (nao so' cada linha) — mesmo precedente de `paineis/adapters/out/pendencia`
  `o-que-vence->wire`: drift de campo e' bug de servidor -> 500, nunca resposta malformada. A borda HTTP
  chama ISTO como corpo 200, nunca monta o envelope inline."
  [rows]
  (validado wire/ListaVereadoresOut {:vereadores (lista->wire rows)} "envelope de lista de vereadores"))

(defn- cargo-mesa-de
  "A entrada de `comissoes` cujo tipo e' a Mesa Diretora fornece o cargo-mesa — nunca o mandato."
  [comissoes]
  (some #(when (= "mesa" (:tipo %)) (:cargo %)) comissoes))

(defn- comissao->wire [c]
  (validado wire/ComissaoDoVereadorOut
            {:nome (:nome c) :tipo (:tipo c) :cargo (:cargo c)}
            "comissao do vereador"))

(defn- mandato->wire [mandato legislatura comissoes]
  (when mandato
    (validado wire/MandatoVigenteOut
              {:partido (:partido mandato) :estado (:estado mandato) :natureza (:natureza mandato)
               :posse (->str (:vigencia-inicio mandato))
               :legislatura-numero (:numero legislatura)
               :legislatura-ano-inicio (:ano-inicio legislatura)
               :legislatura-ano-fim (:ano-fim legislatura)
               :cargo-mesa (cargo-mesa-de comissoes)}
              "mandato vigente")))

(defn ficha->wire
  "{:vereador :mandato :legislatura :comissoes} (dominio, `Repo/ficha-vereador`) -> VereadorFichaOut."
  [{:keys [vereador mandato legislatura comissoes]}]
  (validado wire/VereadorFichaOut
            {:id (->str (:id vereador)) :nome (:nome vereador) :nome-parlamentar (:nome-parlamentar vereador)
             :mandato (mandato->wire mandato legislatura comissoes)
             :comissoes (mapv comissao->wire comissoes)}
            "ficha de vereador"))
