(ns oplenario.identidade.acesso-adapters-in-test
  "Gate de entrada da superficie ADMINISTRATIVA de identidade. O CPF e' validado AQUI (nao so' pela
  assertion do db/, que some com -da) — CPF invalido = 400, nunca linha ruim no banco."
  (:require [clojure.test :refer [deftest is]]
            [oplenario.identidade.adapters.in.acesso :as adapters-in]))

(def ^:private ator {:ente-id (random-uuid) :identidade-id (random-uuid)})

(defn- tipo-do [f] (try (f) nil (catch clojure.lang.ExceptionInfo e (:tipo (ex-data e)))))

(deftest cpf-invalido-e-recusado-na-borda
  (is (= :validacao/invalido
         (tipo-do #(adapters-in/criar-identidade->dominio ator {"cpf" "11111111111" "nome" "X"})))
      "11-iguais passa no regex mas falha no digito verificador — a borda recusa")
  (is (= :validacao/invalido
         (tipo-do #(adapters-in/criar-identidade->dominio ator {"cpf" "12345678900" "nome" "X"})))
      "digito verificador errado -> 400")
  (is (= :validacao/invalido
         (tipo-do #(adapters-in/criar-identidade->dominio ator {"cpf" "529.982.247-25" "nome" "X"})))
      "formatado com pontuacao -> 400 (o wire e' 11 digitos crus; nao coagimos silenciosamente)"))

(deftest cpf-valido-passa
  (let [m (adapters-in/criar-identidade->dominio ator {"cpf" "52998224725" "nome" "Helena Matos"})]
    (is (= "52998224725" (:cpf m)))
    (is (= "Helena Matos" (:nome m)))
    (is (uuid? (:id m)) "id gerado aqui, nunca vindo do corpo")))

(deftest chave-forjada-e-recusada
  (is (= :validacao/invalido
         (tipo-do #(adapters-in/criar-identidade->dominio ator {"cpf" "52998224725" "nome" "X"
                                                                "id" "00000000-0000-0000-0000-000000000000"})))
      "o :closed do schema recusa `id` forjado — por isso keywordizar NAO filtra antes de validar"))

(deftest conceder-acesso-so-aceita-papeis-conhecidos
  (let [ident (random-uuid)]
    (is (= :validacao/invalido
           (tipo-do #(adapters-in/conceder-acesso->dominio
                      ator {"identidade-id" (str ident) "tipo" "vereador"
                            "papeis" ["admin_ente"] "email" "h@c.local"})))
        "conceder admin_ente por esta rota seria escalada de privilegio — allowlist recusa")
    (let [m (adapters-in/conceder-acesso->dominio
             ator {"identidade-id" (str ident) "tipo" "vereador"
                   "papeis" ["vereador"] "email" "helena@camara.local"})]
      (is (= ident (:identidade-id m)))
      (is (= ["vereador"] (:papeis m))))))

(deftest email-malformado-recusado
  (is (= :validacao/invalido
         (tipo-do #(adapters-in/conceder-acesso->dominio
                    ator {"identidade-id" (str (random-uuid)) "tipo" "vereador"
                          "papeis" ["vereador"] "email" "sem-arroba"})))
      "e-mail malformado -> 400 (o convite nunca chegaria; falhar cedo e' honesto)"))

(deftest auditor-e-um-servidor-que-so-le-a-trilha
  (let [ident (random-uuid)
        m (adapters-in/conceder-acesso->dominio
           ator {"identidade-id" (str ident) "tipo" "servidor" "papeis" ["auditor"] "email" "controle@camara.local"})]
    (is (= ["auditor"] (:papeis m)))
    (is (= "servidor" (:tipo m))))
  (is (= :validacao/invalido
         (tipo-do #(adapters-in/conceder-acesso->dominio
                    ator {"identidade-id" (str (random-uuid)) "tipo" "servidor" "papeis" ["vereador"] "email" "h@c.local"})))
      "papel de vereador num vinculo de servidor: incoerente -> 400")
  (is (= :validacao/invalido
         (tipo-do #(adapters-in/conceder-acesso->dominio
                    ator {"identidade-id" (str (random-uuid)) "tipo" "vereador" "papeis" ["auditor"] "email" "h@c.local"})))
      "o vereador nao se faz auditor pela mesma concessao"))

;; ---- ADR-0019: o papel `juridico` (servidor + qualificacao + OAB) ----

(defn- corpo-juridico [& {:as extra}]
  (merge {"identidade-id" (str (random-uuid)) "tipo" "servidor" "papeis" ["juridico"] "email" "adv@camara.local"
          "qualificacao" "efetivo" "oab" "CE 12345"}
         extra))

(deftest juridico-e-um-servidor-com-qualificacao-e-oab
  (let [m (adapters-in/conceder-acesso->dominio ator (corpo-juridico))]
    (is (= ["juridico"] (:papeis m)))
    (is (= "servidor" (:tipo m)))
    (is (= {:qualificacao "efetivo" :oab "CE 12345"} (:perfil-juridico m))))
  (doseq [q ["efetivo" "comissionado" "contratado"]]
    (is (= q (get-in (adapters-in/conceder-acesso->dominio ator (corpo-juridico "qualificacao" q))
                     [:perfil-juridico :qualificacao]))
        "as tres qualificacoes do Eixo 1 passam")))

(deftest oab-e-normalizada
  (doseq [[bruta esperada] [["ce12345" "CE 12345"]
                            ["  ce - 12345 " "CE 12345"]
                            ["CE-12345" "CE 12345"]
                            ["Ce   12345" "CE 12345"]
                            ["sp 1234567" "SP 1234567"]
                            ["CE 12345A" "CE 12345A"]
                            ["ce 12345a" "CE 12345A"]]]
    (is (= esperada (get-in (adapters-in/conceder-acesso->dominio ator (corpo-juridico "oab" bruta))
                            [:perfil-juridico :oab]))
        (str "normaliza " (pr-str bruta)))))

(deftest sem-juridico-nao-ha-perfil
  (let [m (adapters-in/conceder-acesso->dominio
           ator {"identidade-id" (str (random-uuid)) "tipo" "servidor" "papeis" ["auditor"] "email" "c@camara.local"})]
    (is (not (contains? m :perfil-juridico)) "auditor nao carrega perfil juridico")))

(deftest juridico-exige-qualificacao-e-oab
  (is (= :validacao/invalido
         (tipo-do #(adapters-in/conceder-acesso->dominio ator (dissoc (corpo-juridico) "qualificacao"))))
      "sem qualificacao -> 400")
  (is (= :validacao/invalido
         (tipo-do #(adapters-in/conceder-acesso->dominio ator (dissoc (corpo-juridico) "oab"))))
      "sem oab -> 400")
  (is (= :validacao/invalido
         (tipo-do #(adapters-in/conceder-acesso->dominio
                    ator (dissoc (corpo-juridico) "qualificacao" "oab"))))
      "sem nenhum dos dois -> 400"))

(deftest juridico-recusa-qualificacao-ou-oab-invalida
  (is (= :validacao/invalido
         (tipo-do #(adapters-in/conceder-acesso->dominio ator (corpo-juridico "qualificacao" "estagiario"))))
      "qualificacao fora do vocabulario -> 400")
  (doseq [oab ["" "   " "12345" "CE" "CEA 12345" "C1 12345" "CE 12345678" "CE 12345AB" "CE 12.345" "CE 12345; DROP"]]
    (is (= :validacao/invalido
           (tipo-do #(adapters-in/conceder-acesso->dominio ator (corpo-juridico "oab" oab))))
        (str "oab invalida -> 400: " (pr-str oab)))))

(deftest qualificacao-e-oab-so-valem-com-juridico
  (is (= :validacao/invalido
         (tipo-do #(adapters-in/conceder-acesso->dominio
                    ator {"identidade-id" (str (random-uuid)) "tipo" "servidor" "papeis" ["auditor"]
                          "email" "c@camara.local" "qualificacao" "efetivo"})))
      "qualificacao sem juridico -> 400")
  (is (= :validacao/invalido
         (tipo-do #(adapters-in/conceder-acesso->dominio
                    ator {"identidade-id" (str (random-uuid)) "tipo" "servidor" "papeis" ["auditor"]
                          "email" "c@camara.local" "oab" "CE 12345"})))
      "oab sem juridico -> 400")
  (is (= :validacao/invalido
         (tipo-do #(adapters-in/conceder-acesso->dominio
                    ator {"identidade-id" (str (random-uuid)) "tipo" "vereador" "papeis" ["vereador"]
                          "email" "v@camara.local" "qualificacao" "efetivo" "oab" "CE 12345"})))
      "o vereador nao carrega perfil juridico -> 400"))

(deftest juridico-e-concedido-sozinho-e-so-a-servidor
  (is (= :validacao/invalido
         (tipo-do #(adapters-in/conceder-acesso->dominio ator (corpo-juridico "papeis" ["auditor" "juridico"]))))
      "juridico + auditor na mesma concessao -> 400")
  (is (= :validacao/invalido
         (tipo-do #(adapters-in/conceder-acesso->dominio ator (corpo-juridico "papeis" ["juridico" "juridico"]))))
      "papel repetido nao e' 'exatamente 1' -> 400")
  (is (= :validacao/invalido
         (tipo-do #(adapters-in/conceder-acesso->dominio ator (corpo-juridico "tipo" "vereador"))))
      "vereador com juridico: papel incompativel com o tipo -> 400")
  (is (= :validacao/invalido
         (tipo-do #(adapters-in/conceder-acesso->dominio ator (corpo-juridico "papeis" ["vereador" "juridico"]))))
      "vereador + juridico num servidor -> 400"))
