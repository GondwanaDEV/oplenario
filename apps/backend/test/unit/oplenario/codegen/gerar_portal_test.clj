(ns oplenario.codegen.gerar-portal-test
  "Unit: o manifesto do codegen do PORTAL PUBLICO (FE Onda A2, Fatia A2.0, Task 0.2) — espelha
  malli-ts-test (gate da derivacao schema Malli -> TS), mas para oplenario.codegen.gerar-portal."
  (:require [clojure.test :refer [deftest is]]
            [clojure.string :as str]
            [oplenario.codegen.gerar-portal :as gerar-portal]))

(deftest gerar-tudo-emite-todas-as-interfaces-do-portal-com-banner
  (let [out (gerar-portal/gerar-tudo)]
    (is (str/starts-with? out "// GERADO") "banner de 'nao editar a mao'")
    (is (every? #(str/includes? out (str "export interface " % " {"))
                ["NormaOut" "MateriaOut" "FichaOut" "EncarregadoOut" "AcompanhamentoEsicOut"
                 "AcompanhamentoOuvidoriaOut"
                 "LegislaturaOut" "MateriaDeAutoriaOut" "VotoPublicoOut" "PresencaOut"
                 "PerfilVereadorOut"])
        "todas as interfaces do manifesto do portal presentes")
    (is (not (re-find #": unknown;" out))
        "nenhum campo caiu no fallback bare 'unknown'")))

(deftest ficha-out-referencia-norma-out-por-nome
  ;; FichaOut aninha NormaOut em :norma — NormaOut precisa estar ANTES no manifesto p/ a igualdade
  ;; estrutural casar (mesmo racional do manifesto da Mesa, MesaOut + seus 8 tipos aninhados).
  (let [out (gerar-portal/gerar-tudo)]
    (is (str/includes? out "norma?: NormaOut | null;")
        "referencia nomeada, nao Record<string, unknown> inlinado")))

(deftest acompanhamentos-tem-enum-de-estado-real
  ;; os dois acompanhamentos publicos (e-SIC/ouvidoria) tem :estado como enum fechado (nao :string livre) —
  ;; confirma que o codegen emite a uniao de literais, nao um "unknown"/"string" solto.
  (let [out (gerar-portal/gerar-tudo)]
    (is (str/includes? out "estado: \"em_analise\" | \"indeferido\" | \"protocolado\" | \"respondido\";")
        "AcompanhamentoEsicOut.estado (vocabulario de participacao/logic estados-pedido)")
    (is (str/includes? out "estado: \"arquivada\" | \"em_analise\" | \"protocolada\" | \"respondida\";")
        "AcompanhamentoOuvidoriaOut.estado (vocabulario de participacao/logic estados-manifestacao)")))

(deftest perfil-vereador-out-exporta-os-dois-sinais-de-honestidade-do-i5
  ;; I-5 fatia 6: a tela (Task 5) e' obrigada, pelo wire, a (a) tratar `janelaDeExercicioConhecida: false`
  ;; como "sem periodo de exercicio registrado" e nunca como 0%, e (b) declarar `presencaProjetadaDesde`
  ;; quando o mandato exibido comecar antes dessa data. Se os campos nao chegarem TIPADOS ao front, as duas
  ;; obrigacoes viram convencao verbal. Este e' o gate.
  (let [out (gerar-portal/gerar-tudo)]
    (is (str/includes? out "export interface PresencaOut {\n  sessoesPresente: number;\n  sessoesComChamada: number;\n  janelaDeExercicioConhecida: boolean;\n}\n"))
    (is (str/includes? out "presenca: PresencaOut;")
        "referencia nomeada, nao um objeto inlinado — o mesmo racional de FichaOut/NormaOut")
    (is (str/includes? out "presencaProjetadaDesde: string;"))
    (is (str/includes? out "acervoComEloDeAutoriaDesde: string;")
        "as DUAS constantes de recorte de projecao chegam ao front, nao so' a de acervo")))

(deftest encarregado-out-nao-vaza-campos-internos
  ;; EncarregadoOut = EncarregadoPublicoOut (renomeado no manifesto do portal) — so nome/rotulo/email,
  ;; nenhum id/ente-id interno (a rota e' publica e anonima).
  (let [out (gerar-portal/gerar-tudo)]
    (is (str/includes? out "export interface EncarregadoOut {\n  nome: string;\n  rotulo: string;\n  email: string;\n}\n"))))
