(ns oplenario.compliance.db.remessa)

;; funcoes sobre datasource — 'compliance.remessa_gerada': artefato IMUTAVEL por versao (re-emissao = nova
;; versao, nunca muta hash/ref); o estado de submissao evolui via UPDATE no ciclo (rascunho -> ... -> aceita|rejeitada)
