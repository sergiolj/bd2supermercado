# Consultas OLAP: fundamentos e planejamento

> Base teórica da tarefa "2 consultas para cada operação OLAP" (Ref. §4.3 item 3) · Responsável: Sérgio
> **Status:** estudo e planejamento. As consultas serão escritas e executadas quando o DW do Pedro (schemas
> `dw_estrela`/`dw_floco` e ETL) estiver no repositório. Nomes de tabelas e colunas abaixo são **ilustrativos** e
> serão ajustados aos scripts reais.

## 1. Ponto de partida: o modelo estrela

Pelas decisões do plano da equipe (seção 4), o fato tem **uma linha por item vendido** em venda concluída
(`COMPLETED`). Ele é ligado a 5 dimensões por chaves substitutas (SK):

```
                 dim_tempo (data, dia, mês, trimestre, ano)
                      │
dim_cliente ── fato_venda ── dim_produto (produto, categoria)
 (cidade, UF)    │      │
          dim_loja    dim_funcionario
        (cidade, UF)     (vendedor)

Medidas do fato: quantidade, valor_bruto, desconto, valor_liquido
```

No floco de neve, `dim_produto` aponta para `dim_categoria`, e `dim_cliente` e `dim_loja` apontam para
`dim_cidade → dim_estado`.

> **Atenção (desconto):** o plano previa `SaleItem.discountInCents`, mas o campo ainda não existe no OLTP. Até ele
> existir, `desconto = 0` e `valor_liquido = valor_bruto`. Isso precisa ser alinhado com o Pablo e o Pedro.

## 2. Por que essas consultas usam SQL nativo

As consultas OLAP rodam **direto no DW**, em SQL, e não em JPQL:

1. as tabelas do DW **não são entidades JPA**: são carregadas pelo ETL, não pelo sistema;
2. `ROLLUP`, `CUBE`, `GROUPING SETS` e `FILTER` **não fazem parte da JPQL padrão** (ver a seção de JPQL);
3. a comparação de desempenho exige `EXPLAIN ANALYZE`, que é comando do PostgreSQL.

Na aplicação Java, elas podem ser expostas por `EntityManager.createNativeQuery(...)` ou guardadas como scripts
`.sql`, conforme a Ref. §5 ("scripts SQL: OLAP").

## 3. As cinco operações, duas consultas cada

### 3.1 Roll-up: subir na hierarquia

**Ideia:** partir do detalhe e consolidar. A forma mais expressiva é o `ROLLUP`, que devolve todos os níveis numa
consulta só.

**R1. Faturamento por ano → trimestre → mês, com subtotais (dimensão Tempo).**
```sql
SELECT t.ano, t.trimestre, t.mes,
       SUM(f.valor_liquido)                      AS faturamento,
       GROUPING(t.ano, t.trimestre, t.mes)       AS nivel   -- 0 = mês, 1 = trimestre, 3 = ano, 7 = total
  FROM dw_estrela.fato_venda f
  JOIN dw_estrela.dim_tempo  t ON t.sk_tempo = f.sk_tempo
 GROUP BY ROLLUP (t.ano, t.trimestre, t.mes)
 ORDER BY t.ano, t.trimestre, t.mes;
```
Linhas com `mes` nulo são subtotais do trimestre, com `trimestre` nulo são subtotais do ano, e a última é o total
geral. A coluna `nivel` (bitmask do `GROUPING`) evita confundir esses nulos de subtotal com dados ausentes.

**R2. Faturamento por UF → cidade → loja (dimensão Loja).** Mesmo padrão, na hierarquia geográfica: mostra quanto
cada estado representa da rede e como isso se divide por cidade.

### 3.2 Drill-down: descer na hierarquia

**Ideia:** o analista vê um número agregado e quer abri-lo. Em SQL, é a mesma consulta com **mais colunas** no
`GROUP BY` e, em geral, um filtro no membro que está sendo aberto.

**D1. De categoria para produto.** Primeiro o faturamento por categoria; depois, dentro da categoria líder, por
produto:
```sql
SELECT p.produto, SUM(f.quantidade) AS qtd, SUM(f.valor_liquido) AS faturamento
  FROM dw_estrela.fato_venda f
  JOIN dw_estrela.dim_produto p ON p.sk_produto = f.sk_produto
 WHERE p.categoria = 'Laticínios e Ovos'
 GROUP BY p.produto
 ORDER BY faturamento DESC;
```

**D2. Do ano para os meses e depois para os dias da semana**, para responder se o movimento cresce no fim de
semana. Isso depende de `dim_tempo` ter o dia da semana, um atributo que o OLTP não guarda e o ETL calcula.

### 3.3 Slice: fixar uma dimensão

**Ideia:** cortar uma "fatia" do cubo fixando **um** membro de **uma** dimensão. O resultado tem uma dimensão a
menos.

**S1. Só o ano de 2025:** faturamento por loja e categoria com `WHERE t.ano = 2025`.

**S2. Só uma loja:** faturamento por mês e categoria com `WHERE l.loja = 'Mercado Central - Pituba'`. É o
relatório que o gerente daquela loja receberia.

### 3.4 Dice: restringir várias dimensões

**Ideia:** escolher um subcubo, com condições em **duas ou mais** dimensões ao mesmo tempo.

**DC1. Lojas da Bahia × bebidas × 1º semestre de 2026:**
```sql
SELECT l.loja, p.categoria, t.mes, SUM(f.valor_liquido) AS faturamento
  FROM dw_estrela.fato_venda f
  JOIN dw_estrela.dim_loja    l ON l.sk_loja    = f.sk_loja
  JOIN dw_estrela.dim_produto p ON p.sk_produto = f.sk_produto
  JOIN dw_estrela.dim_tempo   t ON t.sk_tempo   = f.sk_tempo
 WHERE l.uf = 'BA'
   AND p.categoria IN ('Bebidas', 'Bebidas Alcoólicas')
   AND t.ano = 2026 AND t.mes BETWEEN 1 AND 6
 GROUP BY l.loja, p.categoria, t.mes;
```

**DC2. Clientes identificados × fins de semana × ticket acima de um valor**, combinando Cliente (exclui o
"Não identificado"), Tempo (sábado e domingo) e a própria medida. Esta usa `CUBE (loja, categoria)` para entregar
todas as combinações de subtotal do subcubo de uma vez.

### 3.5 Pivot: trocar linhas por colunas

**Ideia:** os mesmos números, com outra orientação. O PostgreSQL não tem operador `PIVOT`. As duas formas usuais
são:

**P1. Agregação condicional com `FILTER`** (padrão SQL:2003): lojas nas linhas e trimestres nas colunas.
```sql
SELECT l.loja,
       SUM(f.valor_liquido) FILTER (WHERE t.trimestre = 1) AS t1,
       SUM(f.valor_liquido) FILTER (WHERE t.trimestre = 2) AS t2,
       SUM(f.valor_liquido) FILTER (WHERE t.trimestre = 3) AS t3,
       SUM(f.valor_liquido) FILTER (WHERE t.trimestre = 4) AS t4
  FROM dw_estrela.fato_venda f
  JOIN dw_estrela.dim_loja  l ON l.sk_loja  = f.sk_loja
  JOIN dw_estrela.dim_tempo t ON t.sk_tempo = f.sk_tempo
 WHERE t.ano = 2025
 GROUP BY l.loja;
```

**P2. A rotação inversa:** categorias nas linhas e lojas nas colunas. Pode usar `crosstab` da extensão
`tablefunc`, útil quando as colunas não são conhecidas de antemão.

## 4. Resumo da tarefa

| Operação | Consulta 1 | Consulta 2 | Construção SQL principal |
|---|---|---|---|
| Roll-up | Tempo: mês → trimestre → ano | Loja: loja → cidade → UF | `GROUP BY ROLLUP`, `GROUPING()` |
| Drill-down | Categoria → produto | Ano → mês → dia da semana | `GROUP BY` com mais colunas, `WHERE` no membro |
| Slice | Ano = 2025 | Loja = Pituba | `WHERE` em uma dimensão |
| Dice | BA × bebidas × 1º semestre | Cliente identificado × fim de semana × ticket | `WHERE` em várias dimensões, `CUBE` |
| Pivot | Loja × trimestre | Categoria × loja | `FILTER (WHERE ...)`, `crosstab` |

## 5. O que depende de outras partes

- **Pedro:** nomes finais dos schemas, tabelas e colunas; atributos de `dim_tempo` (trimestre, dia da semana).
- **Pablo/Pedro:** desconto no item de venda, para `valor_liquido` diferir de `valor_bruto`.
- **Bruna:** os padrões do seed (2.000 vendas em 26 meses, 5 lojas) definem se os resultados terão sentido de
  negócio. Vale conferir com ela quais padrões foram gerados, para escolher filtros de slice e dice que mostrem algo.
