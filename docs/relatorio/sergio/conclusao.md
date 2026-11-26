# Conclusão

> Seção do relatório · Responsável: Sérgio · **Esqueleto**: depende dos resultados do ETL, do OLAP, da comparação
> de desempenho e do Apriori. Os trechos entre colchetes serão substituídos pelos números e achados reais.

O trabalho construiu, sobre o mesmo cenário de uma rede de supermercados, um sistema transacional e um ambiente
analítico, e mostrou na prática por que os dois são separados.

**Na etapa OLTP**, o mapeamento objeto-relacional com JPA e Hibernate permitiu escrever o sistema em termos de
objetos e relacionamentos, enquanto o banco manteve normalização e integridade. O projeto também mostrou que a
abstração tem limites: entender o SQL gerado foi necessário para evitar o problema N+1 e para paginar consultas
com `JOIN FETCH`. Regras que precisam valer para qualquer cliente do banco, como o saldo de estoque, ficaram em
*triggers* e restrições do próprio PostgreSQL.

**Na etapa analítica**, [resumo do ETL: volumes carregados, tratamento de nulos, SCD tipo 2 demonstrado]. As
consultas OLAP com `ROLLUP` e `CUBE` mostraram [principais achados de negócio]. A comparação de desempenho
indicou que [resultado: estrela × floco × OLTP, número de junções e tempos], o que [confirma/contraria] a hipótese
de que o modelo dimensional transfere trabalho da consulta para a carga. A mineração com Apriori encontrou
[regras de associação mais relevantes, com suporte e confiança] e sugeriu [ações de negócio].

**Limitações.** Os dados são sintéticos, gerados com apoio de IA, e o volume é pequeno para medir desempenho.
[outras limitações observadas]

**Trabalhos futuros.** [ex.: carga incremental agendada, mais medidas no fato (margem), MOLAP, volumes maiores]
