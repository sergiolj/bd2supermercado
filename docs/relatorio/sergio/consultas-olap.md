# Consultas OLAP sobre o Data Warehouse

> Ref. §4.3 item 3 · Responsável: Sérgio · Consultas em `src/main/resources/db/dw/03_consultas_olap.sql`
> As 10 consultas base (2 por operação) e o comparativo foram escritos pelo Pedro junto com o DW (PR #17). Esta
> seção revisa as consultas, registra as correções e acrescenta `CUBE` e `GROUPING SETS`.

## 1. Onde as consultas rodam

As consultas rodam direto no DW, em SQL nativo do PostgreSQL, e não em JPQL:

1. as tabelas do DW **não são entidades JPA**: quem as carrega é o ETL (`CALL etl.executar_carga(...)`), não o
   sistema;
2. `ROLLUP`, `CUBE`, `GROUPING SETS`, `GROUPING()` e `FILTER` **não fazem parte da JPQL padrão** (seção de JPQL);
3. a comparação de desempenho usa `EXPLAIN ANALYZE`, que é comando do PostgreSQL.

O arquivo não entra no `spring.sql.init`: roda à mão, no psql ou no Adminer, depois da carga do DW.

## 2. O modelo consultado

O fato `fato_venda` tem **uma linha por item vendido** em venda concluída: 12.467 linhas com o seed atual, de
1.895 vendas entre outubro de 2024 e novembro de 2026. As medidas são `quantidade`, `preco_unitario`,
`valor_bruto`, `desconto` e `valor_liquido`.

| Dimensão | Hierarquia usada nas consultas | Estrela | Floco |
|---|---|---|---|
| Tempo | dia → mês → trimestre → semestre → ano; dia da semana; fim de semana | `dim_tempo` | `dim_tempo` |
| Produto | produto → categoria | `dim_produto` (categoria como coluna) | `dim_produto` → `dim_categoria` |
| Loja | loja → cidade → UF | `dim_loja` (cidade e UF como colunas) | `dim_loja` → `dim_cidade` → `dim_estado` |
| Cliente | cliente → cidade → UF; identificado ou não (`sk_cliente = 0`) | `dim_cliente` | `dim_cliente` → `dim_cidade` → `dim_estado` |
| Funcionário | funcionário → cargo | `dim_funcionario` | `dim_funcionario` |

## 3. As consultas, por operação

| # | Operação | Pergunta | Construção SQL |
|---|---|---|---|
| 1.1 | Roll-up | Faturamento por mês, com subtotais de trimestre, semestre, ano e o total geral | `GROUP BY ROLLUP (ano, semestre, trimestre, (mes, nome_mes))` + `GROUPING()` |
| 1.2 | Roll-up | Faturamento por loja, com subtotais de cidade e UF (no **floco**) | `ROLLUP (uf, cidade, loja)` + `GROUPING()` |
| 2.1 | Drill-down | De categoria para produto: quantidade, receita e preço médio | `GROUP BY` categoria, produto, unidade |
| 2.2 | Drill-down | De loja para cargo, funcionário e mês | `GROUP BY` loja, cargo, funcionário, ano, mês |
| 3.1 | Slice | Só a categoria Bebidas, por loja e mês | `WHERE p.categoria = 'Bebidas'` |
| 3.2 | Slice | Só os fins de semana, por dia e categoria | `WHERE t.fim_de_semana` |
| 4.1 | Dice | Clientes identificados × fins de semana × 5 categorias, por cidade e trimestre | `WHERE` em Cliente, Tempo e Produto |
| 4.2 | Dice | Caixas × 1º semestre, por cidade da loja | `WHERE` em Funcionário e Tempo |
| 5.1 | Pivot | Categorias nas linhas, trimestres de 2025 nas colunas | `SUM(CASE WHEN trimestre = n ...)` |
| 5.2 | Pivot | Lojas nas linhas, dias da semana nas colunas | `SUM(CASE WHEN dia_semana = n ...)` |
| 6.1 | Cubo | UF da loja × categoria em 2025, com todas as combinações de subtotal | `GROUP BY CUBE (uf, categoria)` |
| 6.2 | Conjuntos de agrupamento | Clientes identificados × não identificados, por loja **e** por dia da semana | `GROUPING SETS` + `FILTER (WHERE ...)` |

### 3.1 Como ler `ROLLUP`, `CUBE` e `GROUPING()`

`ROLLUP (ano, semestre, trimestre, mes)` devolve, numa só consulta, as linhas de cada mês e mais os subtotais.
Nas linhas de subtotal, as colunas "resumidas" vêm `NULL`. A função `GROUPING(ano, semestre, trimestre, mes)`
devolve um número binário, com um bit por coluna, que vale 1 quando a coluna foi resumida. Assim a consulta 1.1
rotula cada linha:

| `GROUPING(...)` | Bits (ano, sem., trim., mês) | Linha |
|---|---|---|
| 0 | 0000 | Mês |
| 1 | 0001 | Subtotal do trimestre |
| 3 | 0011 | Subtotal do semestre |
| 7 | 0111 | Subtotal do ano |
| 15 | 1111 | Total geral |

Na consulta 1.1, a última linha (`Total geral`) traz 1.895 vendas e R$ 164.751,70, os mesmos números do sistema de
vendas. É uma conferência rápida de que o ETL não perdeu nem duplicou nada.

O `CUBE (uf, categoria)` da consulta 6.1 gera os quatro agrupamentos possíveis (UF × categoria, só UF, só categoria
e total). O `GROUPING SETS` da 6.2 gera só os dois pedidos (por loja e por dia da semana): equivale a dois
`GROUP BY` unidos com `UNION ALL`, mas lê o fato uma vez só.

## 4. Revisão das consultas do PR #17

Todas as consultas originais rodam e devolvem resultados coerentes. Foram feitas estas correções:

| Consulta | Problema | Correção |
|---|---|---|
| 4.1 (Dice) | O filtro `IN ('Acougue', 'Hortifruti', 'Bebidas', 'Mercearia')` usava nomes que não existem no seed. Só "Bebidas" casava: o resultado tinha 16 linhas de uma categoria só | Filtro por prefixo (`ILIKE ANY`): Carnes, Produtos Frescos, Bebidas e Mercearia. Agora são 80 linhas de 5 categorias. O prefixo também resiste à padronização de nomes do ETL (o `INITCAP` grava "Carnes, Aves E Peixes") |
| 5.1 (Pivot) | Somava trimestres de anos diferentes (as vendas vão de out/2024 a nov/2026) numa coluna chamada `total_anual` | Filtro `t.ano = 2025`, o único ano completo |
| 1.1 e 1.2 (Roll-up) | Subtotais apareciam só como `NULL`, sem dizer de que nível eram | Coluna `nivel` com `GROUPING()` |
| — | O plano pedia `ROLLUP/CUBE`; não havia `CUBE` | Consultas 6.1 (`CUBE`) e 6.2 (`GROUPING SETS`) |
| 7.x (comparativo) | `EXPLAIN ANALYZE` sem `BUFFERS` e sem orientação de método | `EXPLAIN (ANALYZE, BUFFERS)` e o método no cabeçalho da seção |

## 5. O que os dados mostram

Os dados são sintéticos (gerados com IA), então os números ilustram as operações, não um supermercado real:

- **Lojas (slice em 2025).** A Pituba lidera com R$ 24,6 mil, 47% acima da Barra (R$ 16,8 mil), a outra loja de
  Salvador. As três lojas fora de Salvador ficam entre R$ 12,1 mil e R$ 13,0 mil. O ticket médio varia pouco,
  entre R$ 83 e R$ 92: a diferença de faturamento vem do **número de vendas**, não do valor de cada compra.
- **Dia da semana (pivot 5.2).** Sexta e sábado são os maiores dias. Somados, os fins de semana são 32,3% do
  faturamento, mas a média por dia com vendas é maior no fim de semana (R$ 267) do que nos dias úteis (R$ 229).
- **Categorias (drill-down 2.1).** Mercearia e Grãos (14,1%), Carnes, Aves e Peixes (10,8%) e Bebidas (9,0%)
  lideram.
- **Sazonalidade (roll-up 1.1).** Em 2025, o 1º trimestre foi o maior (R$ 21,8 mil) e o 3º o menor (R$ 17,3 mil).
- **Identificação do cliente (6.2).** 59,6% das vendas têm cliente identificado, próximo dos 60% definidos para o
  seed. A participação muda por dia da semana: 55,9% do faturamento de segunda-feira contra 65,1% do de terça.
