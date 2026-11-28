# Conclusão

> Seção do relatório · Responsável: Sérgio · **Rascunho**: falta a parte da mineração (Apriori), que depende da
> entrega da Bruna. O trecho entre colchetes será substituído pelos achados reais.

O trabalho construiu, sobre o mesmo cenário de uma rede de supermercados, um sistema transacional e um ambiente
analítico, e mostrou na prática por que os dois são separados.

**Na etapa OLTP**, o mapeamento objeto-relacional com JPA e Hibernate permitiu escrever o sistema em termos de
objetos e relacionamentos, enquanto o banco manteve normalização e integridade. O projeto também mostrou que a
abstração tem limites: entender o SQL gerado foi necessário para evitar o problema N+1 e para paginar consultas
com `JOIN FETCH`. Regras que precisam valer para qualquer cliente do banco, como o saldo de estoque, ficaram em
*triggers* e restrições do próprio PostgreSQL.

**Na etapa analítica**, o ETL em PL/pgSQL levou as 12.467 linhas de itens de vendas concluídas para os modelos
estrela e floco de neve. A carga aplica chaves substitutas, dimensão Tempo gerada, um membro fixo para o cliente
não identificado e SCD tipo 2 na cidade do cliente. A contagem e o total (R$ 164.751,70) batem nos três modelos.
A segunda execução incremental não insere nada, e a mudança de cidade de um cliente gera uma nova versão sem
alterar as vendas antigas.

As consultas OLAP mostraram as cinco operações e as extensões `ROLLUP`, `CUBE` e `GROUPING SETS`, com subtotais
rotulados por `GROUPING()`. Nos dados sintéticos, a loja da Pituba lidera o faturamento, sexta e sábado são os
maiores dias, e Mercearia e Grãos é a principal categoria. A diferença entre as lojas vem do número de vendas,
não do ticket médio, que varia pouco.

A comparação de desempenho confirmou a hipótese de que o modelo dimensional transfere trabalho da consulta para a
carga. No volume do seed, a estrela respondeu em metade do tempo do OLTP. Com 250 mil itens, a diferença chegou a
10 vezes, porque a agregação do OLTP passou a ordenar em disco, enquanto a da estrela continuou em memória e em
paralelo. O floco ficou de 13% a 17% atrás da estrela, o custo da junção extra com a categoria. O teste também
mostrou que o número de junções sozinho não explica o desempenho: o floco tem tantas junções quanto o OLTP e é
bem mais rápido, porque faz menos trabalho por linha.

A mineração com Apriori encontrou [regras de associação mais relevantes, com suporte e confiança] e sugeriu
[ações de negócio].

**Limitações.** Os dados são sintéticos, gerados com apoio de IA, e o volume é pequeno; o teste de escala replicou
as mesmas linhas. O OLTP ainda não registra desconto, então valor bruto e valor líquido são iguais no DW. O SCD
tipo 2 só registra a mudança de cidade de um cliente quando ele faz uma nova compra.

**Trabalhos futuros.** Agendar a carga incremental, por exemplo de madrugada. Registrar desconto no item de venda.
Acrescentar medidas ao fato, como custo e margem. Avaliar índices e agregados pré-calculados para volumes maiores.
