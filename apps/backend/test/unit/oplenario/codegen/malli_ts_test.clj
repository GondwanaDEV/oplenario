(ns oplenario.codegen.malli-ts-test
  "Unit: o codegen Malli->TS (1o corte, FE0). Verifica o emissor contra os models reais — a fronteira
  core->TS (Inv.5) e' derivada do schema, nunca escrita a mao; este teste e' o gate dessa derivacao."
  (:require [clojure.test :refer [deftest is]]
            [clojure.string :as str]
            [oplenario.cadastros.models.cadastro :as cad]
            [oplenario.codegen.gerar :as gerar]
            [oplenario.codegen.malli-ts :as ts]
            [oplenario.identidade.models.identidade :as idn]))

(deftest interface-simples-uuid-e-string
  (is (= "export interface Identidade {\n  id: string;\n  cpf: string;\n  nome: string;\n}\n"
         (ts/interface-ts "Identidade" idn/Identidade))
      "uuid e [:re ...] viram string; chaves simples"))

(deftest enum-vira-uniao-de-literais-ordenada
  (let [out (ts/interface-ts "Vinculo" idn/Vinculo)]
    (is (str/includes? out "tipo: \"admin_ente\" | \"cidadao\" | \"servidor\" | \"vereador\";") "enum tipo (ordenado)")
    (is (str/includes? out "estado: \"ativo\" | \"encerrado\" | \"suspenso\";") "enum estado (ordenado)")))

(deftest data-vira-string-e-optional-maybe
  (let [out (ts/interface-ts "Mandato" cad/Mandato)]
    (is (str/includes? out "vigenciaInicio: string;") "LocalDate -> string + chave camelCase")
    (is (str/includes? out "vigenciaFim?: string | null;") "optional + maybe -> campo? : T | null")
    (is (str/includes? out "partido?: string | null;") "string opcional anulavel")
    (is (str/includes? out "estado: \"cassado\" | \"concluido\" | \"falecido\" | \"licenciado\" | \"renunciado\" | \"vigente\";")
        "enum de estado do mandato")))

(deftest gerar-tudo-emite-todas-as-interfaces-com-banner
  (let [out (gerar/gerar-tudo)]
    (is (str/starts-with? out "// GERADO") "banner de 'nao editar a mao'")
    (is (every? #(str/includes? out (str "export interface " % " {"))
                ["Ente" "Legislatura" "Vereador" "Mandato" "Comissao" "ComissaoMembro"
                 "TramitacaoResumoOut" "PendenciasResumoOut" "SessoesResumoOut" "PresencaResumoOut"
                 "EsicCumprimentoOut" "RelatorPendenteOut" "RelatoresPendentesOut" "CardIndisponivelOut"
                 "MesaOut"])
        "todas as interfaces do manifesto presentes")
    ;; identidade NAO entra no manifesto — o model Identidade carrega :cpf; exporta-lo vazaria PII no
    ;; contrato do front (so via wire/out sem-CPF, carry FE0). Guarda anti-regressao.
    (is (not (str/includes? out "export interface Identidade {")) "Identidade (com CPF) NAO vaza nos tipos TS")
    (is (not (str/includes? out "cpf")) "nenhum campo cpf no contrato do front")
    (is (not (re-find #": unknown;" out))
        "nenhum campo caiu no fallback bare 'unknown' (Record<string, unknown> e' tipo valido, nao fallback)")))

(deftest referencia-nomeada-nao-inlina-record-generico
  ;; um :map ANINHADO cuja forma bate EXATAMENTE com um schema ja' nomeado no manifesto -> emite o NOME
  ;; da interface (referencia), nao "Record<string, unknown>".
  (let [interno [:map {:closed true} [:x :int]]
        externo [:map {:closed true} [:campo interno]]
        out (ts/interface-ts {interno "Interno"} "Externo" externo)]
    (is (str/includes? out "campo: Interno;") "schema nomeado no manifesto vira referencia de tipo")))

(deftest sequential-vira-array-ts
  (let [item [:map {:closed true} [:id :string]]
        pai  [:map {:closed true} [:itens [:sequential item]]]
        out (ts/interface-ts {item "Item"} "Pai" pai)]
    (is (str/includes? out "itens: Item[];"))))

(deftest interface-ts-2-aridade-preserva-comportamento-antigo
  ;; backward-compat: chamada sem o mapa de referencias segue inlinando maps aninhados como antes.
  (let [interno [:map {:closed true} [:x :int]]
        externo [:map {:closed true} [:campo interno]]]
    (is (str/includes? (ts/interface-ts "Externo" externo) "campo: Record<string, unknown>;"))))

(deftest or-vira-uniao-ts
  ;; [:or A B] -> uniao TS "A | B"; cada ramo resolvido recursivamente pela mesma referencia nomeada
  ;; (achado da review de A5+A6: MesaOut tem 3 campos [:or <fechado> CardIndisponivelOut] p/ degradacao
  ;; por card — sem este caso, ts-tipo cai no fallback bare "unknown" p/ esses campos).
  (let [a   [:map {:closed true} [:x :int]]
        b   [:map {:closed true} [:y :string]]
        out (ts/interface-ts {a "A" b "B"} "Pai" [:map {:closed true} [:campo [:or a b]]])]
    (is (str/includes? out "campo: A | B;"))))

(deftest literal-igual-vira-tipo-literal-ts
  ;; [:= true] (sentinel CardIndisponivelOut) -> tipo literal TS `true`, nao "unknown".
  (let [out (ts/interface-ts "Sentinela" [:map {:closed true} [:indisponivel [:= true]]])]
    (is (str/includes? out "indisponivel: true;"))))

(deftest mesa-out-referencia-os-tipos-aninhados-por-nome
  (let [out (gerar/gerar-tudo)]
    (is (str/includes? out "tramitacao: TramitacaoResumoOut;"))
    (is (str/includes? out "pendencias: PendenciasResumoOut;"))
    (is (str/includes? out "sessoes: SessoesResumoOut;"))
    ;; os 3 cards novos sao [:or <fechado> CardIndisponivelOut] (Critical review fix pos-A5/A6) -> uniao TS.
    (is (str/includes? out "presencaResumo: PresencaResumoOut | CardIndisponivelOut;"))
    (is (str/includes? out "esicCumprimento: EsicCumprimentoOut | CardIndisponivelOut;"))
    (is (str/includes? out "relatoresPendentes: RelatoresPendentesOut | CardIndisponivelOut;"))
    (is (str/includes? out "complianceTce: Record<string, unknown>;") "card opaco por design")))
