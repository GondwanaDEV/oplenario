(ns oplenario.encerramento.protecao
  "HOST (§22.10) — a PROTECAO DO CIDADAO na exportacao da Casa (ADR-0018 Eixo 4.2 + ADR-0017 4c). PURO.

  A Casa recebe os dados dela, mas nao mais do que a tela da trilha mostra sobre quem e' so' CIDADAO:
  - o id de quem, nesta Casa, so' tem vinculo de cidadao (nenhum vinculo de servidor/vereador/admin, nenhum papel)
    vira, em QUALQUER campo exportado (coluna uuid, texto, JSON, lista), o MESMO pseudonimo que a tela da trilha usa
    (`#a1b2c3`, estavel por Casa). Servidores, vereadores e agentes seguem com o id real: sao atos da funcao publica;
  - na trilha (`auditoria.registro`), toda linha em que o ator agiu como cidadao sai com o pseudonimo e SEM o IP — mesmo
    que a pessoa tambem seja servidora, porque ali ela agiu como cidada;
  - e a linha do ato que PODE ser anonimo (protocolar manifestacao de ouvidoria — Lei 13.460, art. 10 §7o) sai sem
    ator nenhum: o horario dela cruzado com o recibo de uma manifestacao anonima reidentificaria quem a fez. A propria
    manifestacao anonima ja' nao guarda o manifestante (nem `created_by`) no banco.

  A corrente da trilha e' conferida sobre os dados REAIS antes desta troca (auditoria/verificacao.json); por isso o
  hash recomputado de uma linha com pseudonimo nao reproduz o selo original — o LEIA-ME explica."
  (:require [clojure.string :as str]))

(set! *warn-on-reflection* true)

(def uuid-re #"[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")

(def acoes-que-podem-ser-anonimas
  "Os atos do cidadao cuja linha na trilha sai SEM ator (o ato em si pode ter sido anonimo)."
  #{"participacao/protocolar-manifestacao"})

(defn protetor
  "(fn [texto] -> texto): troca cada UUID de `cidadaos` (strings minusculas) pelo pseudonimo `(pseudonimo id)`."
  [cidadaos pseudonimo]
  (if (empty? cidadaos)
    identity
    (fn [s]
      (when s
        (if (re-find uuid-re s)
          (str/replace s uuid-re (fn [m] (let [id (str/lower-case m)] (if (contains? cidadaos id) (pseudonimo id) m))))
          s)))))

(defn protetor-de-linha
  "(fn [textos] -> textos) para a tabela `nome` (\"schema.tabela\") com as colunas `colunas`. Toda tabela: `proteger`
  em cada campo. A trilha: alem disso, a linha do ato como cidadao leva o pseudonimo do ator e perde o IP, e a do
  ato que pode ser anonimo perde o ator."
  [nome colunas proteger pseudonimo]
  (let [geral (fn [textos] (mapv proteger textos))]
    (if-not (= "auditoria.registro" nome)
      geral
      (let [idx (into {} (map-indexed (fn [i c] [c i])) colunas)
            [i-tipo i-id i-ip i-acao] (map idx ["ator_tipo" "identidade_id" "ip" "acao"])]
        (fn [textos]
          (let [v (geral textos)]
            (if (and i-tipo (= "cidadao" (nth textos i-tipo)))
              (cond-> v
                i-ip (assoc i-ip nil)
                (and i-id (nth textos i-id)) (assoc i-id (pseudonimo (str/lower-case (nth textos i-id))))
                (and i-id i-acao (acoes-que-podem-ser-anonimas (nth textos i-acao))) (assoc i-id nil))
              v)))))))
