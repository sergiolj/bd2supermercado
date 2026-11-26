# OLTP e OLAP: processamento transacional e analítico

> Seção do relatório teórico · Ref. §3.1 item 2e · Responsável: Sérgio
> O quadro comparativo resumido OLTP × OLAP é a questão 11 (Luiz). Esta seção trata dos conceitos e das operações.

## 1. Dois jeitos de usar um banco de dados

Um supermercado faz duas perguntas muito diferentes aos seus dados:

- *"Registre esta venda, dê baixa no estoque e confirme o pagamento."* É uma operação do dia a dia, que precisa
  terminar em milissegundos e não pode deixar o banco inconsistente.
- *"Qual categoria mais cresceu em cada loja nos últimos dois anos, mês a mês?"* É uma pergunta de análise, que
  varre milhares de linhas de histórico e resume tudo em poucos números.

O primeiro tipo é o **OLTP** (*On-Line Transaction Processing*). O segundo é o **OLAP** (*On-Line Analytical
Processing*). Os requisitos dos dois são opostos, e por isso a literatura recomenda bancos e modelagens separados
para cada um (CHAUDHURI; DAYAL, 1997; KIMBALL; ROSS, 2013).

## 2. OLTP

O OLTP sustenta a operação do negócio. Suas características:

- **Transações curtas e muito frequentes**, cada uma lendo ou alterando poucas linhas, quase sempre pela chave.
- **Propriedades ACID** (atomicidade, consistência, isolamento e durabilidade) e controle de concorrência.
- **Modelo normalizado** (em geral até a 3ª forma normal), para evitar redundância e anomalias de atualização.
- **Dado atual.** O registro reflete o estado de agora; o histórico não é a prioridade.
- **Muitos usuários simultâneos**: caixas, estoquistas, sistemas integrados.

No projeto, o sistema de vendas em Spring Boot + Hibernate é o OLTP. Fechar uma venda grava o pagamento, muda o
status da venda e lança uma movimentação de estoque por item, **tudo na mesma transação**: se faltar saldo, nada é
gravado. As linhas de estoque são travadas com `SELECT ... FOR UPDATE` para duas vendas não disputarem o mesmo
saldo. Isso é OLTP típico: correção e concorrência acima de tudo.

## 3. OLAP

O termo OLAP foi popularizado por Codd, Codd e Salley (1993), que defendiam ferramentas para o analista explorar
dados de forma **multidimensional** e interativa. Suas características:

- **Consultas complexas e pouco frequentes**, que leem grandes volumes e devolvem resumos (somas, médias,
  contagens).
- **Predomínio de leitura.** Os dados chegam em carga periódica (ETL), não por transações do usuário.
- **Histórico.** O valor está em comparar períodos, e o dado não é sobrescrito: uma mudança vira uma nova versão,
  como no SCD tipo 2.
- **Modelo desnormalizado** (esquema estrela ou floco de neve), pensado para poucas junções e agregação rápida
  (KIMBALL; ROSS, 2013).
- **Poucos usuários**: analistas e gestores.

O banco que atende o OLAP é o *Data Warehouse*. Para Inmon (2005), ele é uma coleção de dados orientada por
assunto, integrada, não volátil e variante no tempo, que apoia as decisões gerenciais.

### 3.1 Por que não fazer análise direto no OLTP?

1. **Concorrência.** Uma consulta analítica que varre a tabela de vendas inteira disputa disco, memória e CPU com
   os caixas.
2. **Modelo.** A normalização que protege o OLTP obriga a análise a fazer muitas junções. No projeto, faturamento
   por categoria exige `sale_items → sale → product → category`, além de calcular o mês linha a linha.
3. **Histórico.** O OLTP guarda a cidade **atual** do cliente. Se ele se mudou, as vendas antigas "mudam de
   cidade" numa análise feita direto no OLTP. O DW preserva a versão da época.
4. **Integração e limpeza.** O DW junta e padroniza os dados (nomes, nulos, códigos) uma vez, no ETL, em vez de
   em toda consulta.

## 4. O modelo multidimensional

A análise OLAP enxerga os dados como um **cubo**:

- **Medidas** (os números, que ficam no **fato**): quantidade, valor bruto, desconto, valor líquido.
- **Dimensões** (o contexto): Tempo, Produto, Loja, Cliente, Funcionário.
- **Hierarquias** dentro de cada dimensão, do mais detalhado ao mais resumido:
  - Tempo: dia → mês → trimestre → ano;
  - Produto: produto → categoria;
  - Loja: loja → cidade → UF.

Cada célula do cubo é o valor de uma medida para uma combinação de membros das dimensões, por exemplo o
*faturamento líquido de Laticínios na loja Pituba em março de 2026*. Com 3 dimensões a figura é literalmente um
cubo; com mais, o nome continua (hipercubo).

## 5. Operações OLAP

| Operação | O que faz | Exemplo no supermercado | Em SQL |
|---|---|---|---|
| **Roll-up** (consolidar) | Sobe na hierarquia, resumindo | Do faturamento **mensal** para o **anual** | Agrupar por menos colunas, ou `ROLLUP` |
| **Drill-down** (detalhar) | Desce na hierarquia, detalhando | Do **ano** para os **meses** de 2026 | Agrupar por mais colunas |
| **Slice** (fatiar) | Fixa **um** valor de **uma** dimensão | Só o ano de 2025 | `WHERE` com uma condição de igualdade |
| **Dice** (cubo menor) | Restringe **várias** dimensões | Lojas da BA, categorias de bebidas, 1º semestre | `WHERE` com várias condições |
| **Pivot** (rotacionar) | Troca linhas por colunas | Lojas nas linhas e meses nas colunas | Agregação condicional (`FILTER`) ou `crosstab` |

Roll-up e drill-down são a mesma operação em sentidos opostos. Slice e dice reduzem o cubo, e pivot muda só a
apresentação, sem alterar os números. Há ainda o *drill-across*, que cruza dois fatos pelas dimensões em comum, e o
*drill-through*, que vai do agregado às linhas do OLTP que o formam.

## 6. Como o OLAP é implementado

- **ROLAP** (*Relational OLAP*): o cubo fica em tabelas relacionais (esquema estrela ou floco) e as operações
  viram SQL com `GROUP BY`. É o caso deste projeto, com o DW no próprio PostgreSQL.
- **MOLAP** (*Multidimensional OLAP*): o cubo é pré-calculado num armazenamento próprio, em matriz. As respostas
  são muito rápidas, mas há custo de carga e de espaço.
- **HOLAP** (*Hybrid*): combina os dois, com detalhe em ROLAP e agregados em MOLAP.

### 6.1 Extensões do SQL para OLAP

O `GROUP BY` comum produz **um** nível de agregação por consulta. Um relatório com subtotais por mês, por ano e o
total geral exigiria três consultas unidas com `UNION ALL`. Gray et al. (1997) propuseram o operador **CUBE**
para resolver isso, e o padrão SQL:1999 incorporou `ROLLUP`, `CUBE` e `GROUPING SETS`, todos suportados pelo
PostgreSQL (POSTGRESQL GLOBAL DEVELOPMENT GROUP, 2024):

- `GROUP BY ROLLUP (ano, mes)` gera os agrupamentos `(ano, mes)`, `(ano)` e `()`. São subtotais **hierárquicos**,
  n + 1 níveis para n colunas. É o roll-up numa única consulta.
- `GROUP BY CUBE (loja, categoria)` gera `(loja, categoria)`, `(loja)`, `(categoria)` e `()`. São **todas** as
  combinações, 2ⁿ agrupamentos.
- `GROUP BY GROUPING SETS ((loja), (categoria))` gera exatamente os agrupamentos listados.
- `GROUPING(coluna)` vale 1 quando a coluna foi "resumida" naquela linha. Serve para distinguir o `NULL` de
  subtotal de um `NULL` que existe nos dados.

## 7. No projeto

| | OLTP | OLAP (DW) |
|---|---|---|
| Esquema no PostgreSQL | `public` | `dw_estrela` e `dw_floco` (com `stg` para o staging) |
| Como os dados entram | API REST (`/api/sale`, `/api/purchase` etc.) | ETL em PL/pgSQL, disparado por `POST /api/etl/run` |
| Unidade de dado | Venda, item, pagamento, movimentação | Fato: uma linha por item vendido em venda concluída |
| Consultas | JPQL e Criteria (Hibernate) | SQL nativo com `ROLLUP`/`CUBE` |
| Histórico | Estado atual | Preservado (SCD tipo 2 na cidade do cliente) |

A demonstração final mostra a ponte entre os dois mundos: uma venda registrada pela API (OLTP) passa pelo ETL e
aparece nas consultas do DW (OLAP).
