-- ============================================================================
-- CONSULTAS OLAP SOBRE O DATA WAREHOUSE (Ref. §4.3 item 3)
--
-- Nao fazem parte do spring.sql.init: rodam a mao, depois de carregar o DW (CALL etl.executar_carga(true)).
--   psql -U postgres -d grocery -f src/main/resources/db/dw/03_consultas_olap.sql
--   (ou cole uma consulta por vez no Adminer/DBeaver)
-- Os comandos terminam em ;; como os demais scripts do projeto (o psql aceita o ; extra).
--
-- Duas consultas por operacao (secoes 1 a 5), mais CUBE e GROUPING SETS (secao 6) e o comparativo de desempenho
-- (secao 7). Nas consultas com ROLLUP/CUBE, GROUPING() diz qual linha e subtotal: o NULL de um subtotal nao se
-- confunde com um dado ausente.
-- ============================================================================

-- ============================================================================
-- 1. ROLL-UP (Agrupamento hierarquico crescente: detalhado -> consolidado)
-- ============================================================================

-- 1.1 Roll-up Temporal: Faturamento por Ano -> Semestre -> Trimestre -> Mes
SELECT
    CASE GROUPING(t.ano, t.semestre, t.trimestre, t.mes)
        WHEN 0  THEN 'Mes'
        WHEN 1  THEN 'Subtotal do trimestre'
        WHEN 3  THEN 'Subtotal do semestre'
        WHEN 7  THEN 'Subtotal do ano'
        WHEN 15 THEN 'Total geral'
    END AS nivel,
    t.ano,
    t.semestre,
    t.trimestre,
    t.mes,
    t.nome_mes,
    COUNT(DISTINCT f.venda_uuid) AS total_vendas,
    SUM(f.quantidade)            AS itens_vendidos,
    SUM(f.valor_liquido)         AS faturamento_total
FROM dw_estrela.fato_venda f
         JOIN dw_estrela.dim_tempo t ON t.sk_tempo = f.sk_tempo
GROUP BY ROLLUP (t.ano, t.semestre, t.trimestre, (t.mes, t.nome_mes))
ORDER BY t.ano NULLS LAST, t.semestre NULLS LAST, t.trimestre NULLS LAST, t.mes NULLS LAST;;

-- 1.2 Roll-up Geografico (Modelo Floco de Neve): Faturamento por Estado -> Cidade -> Loja
SELECT
    CASE GROUPING(e.uf, c.nome_cidade, l.nome_loja)
        WHEN 0 THEN 'Loja'
        WHEN 1 THEN 'Subtotal da cidade'
        WHEN 3 THEN 'Subtotal do estado'
        WHEN 7 THEN 'Total geral'
    END AS nivel,
    e.uf,
    c.nome_cidade,
    l.nome_loja,
    COUNT(DISTINCT f.venda_uuid) AS total_vendas,
    SUM(f.valor_liquido)         AS faturamento_total
FROM dw_floco.fato_venda f
         JOIN dw_floco.dim_loja l   ON l.sk_loja = f.sk_loja
         JOIN dw_floco.dim_cidade c ON c.sk_cidade = l.sk_cidade
         JOIN dw_floco.dim_estado e ON e.sk_estado = c.sk_estado
GROUP BY ROLLUP (e.uf, c.nome_cidade, l.nome_loja)
ORDER BY e.uf NULLS LAST, c.nome_cidade NULLS LAST, l.nome_loja NULLS LAST;;

-- ============================================================================
-- 2. DRILL-DOWN (Detalhamento progressivo: visao macro -> micro)
-- ============================================================================

-- 2.1 Drill-down de Produto: Categoria -> Produto -> Unidade de Medida
SELECT
    p.categoria,
    p.nome_produto,
    p.unidade_medida,
    SUM(f.quantidade)    AS qtd_vendida,
    SUM(f.valor_liquido) AS receita_liquida,
    ROUND(AVG(f.preco_unitario), 2) AS preco_medio
FROM dw_estrela.fato_venda f
         JOIN dw_estrela.dim_produto p ON p.sk_produto = f.sk_produto
GROUP BY p.categoria, p.nome_produto, p.unidade_medida
ORDER BY p.categoria, receita_liquida DESC;;

-- 2.2 Drill-down de Atendimento: Loja -> Cargo -> Funcionario -> Mes
SELECT
    l.nome_loja,
    fn.cargo,
    fn.nome_funcionario,
    t.ano,
    t.mes,
    COUNT(DISTINCT f.venda_uuid) AS qtd_atendimentos,
    SUM(f.valor_liquido)         AS total_vendido
FROM dw_estrela.fato_venda f
         JOIN dw_estrela.dim_loja l         ON l.sk_loja = f.sk_loja
         JOIN dw_estrela.dim_funcionario fn ON fn.sk_funcionario = f.sk_funcionario
         JOIN dw_estrela.dim_tempo t        ON t.sk_tempo = f.sk_tempo
GROUP BY l.nome_loja, fn.cargo, fn.nome_funcionario, t.ano, t.mes
ORDER BY l.nome_loja, total_vendido DESC, t.ano, t.mes;;

-- ============================================================================
-- 3. SLICE (Corte do cubo fixando 1 dimensao especifica)
-- ============================================================================

-- 3.1 Slice por Categoria: Desempenho de vendas apenas da categoria 'Bebidas' por loja e mes
SELECT
    l.nome_loja,
    t.ano,
    t.nome_mes,
    SUM(f.quantidade)    AS volume_vendido,
    SUM(f.valor_liquido) AS faturamento
FROM dw_estrela.fato_venda f
         JOIN dw_estrela.dim_produto p ON p.sk_produto = f.sk_produto
         JOIN dw_estrela.dim_loja l    ON l.sk_loja = f.sk_loja
         JOIN dw_estrela.dim_tempo t   ON t.sk_tempo = f.sk_tempo
WHERE p.categoria = 'Bebidas'
GROUP BY l.nome_loja, t.ano, t.mes, t.nome_mes
ORDER BY t.ano, t.mes, faturamento DESC;;

-- 3.2 Slice Temporal (Fins de Semana): Comportamento de compras realizadas aos sabados e domingos
SELECT
    t.nome_dia_semana,
    p.categoria,
    COUNT(DISTINCT f.venda_uuid) AS qtd_cupons,
    SUM(f.valor_liquido)         AS faturamento_fim_de_semana
FROM dw_estrela.fato_venda f
         JOIN dw_estrela.dim_tempo t   ON t.sk_tempo = f.sk_tempo
         JOIN dw_estrela.dim_produto p ON p.sk_produto = f.sk_produto
WHERE t.fim_de_semana = TRUE
GROUP BY t.dia_semana, t.nome_dia_semana, p.categoria
ORDER BY t.dia_semana, faturamento_fim_de_semana DESC;;

-- ============================================================================
-- 4. DICE (Subcubo filtrando 2 ou mais dimensoes simultaneamente)
-- ============================================================================

-- 4.1 Dice: Vendas de clientes identificados em fins de semana para categorias selecionadas
SELECT
    c.cidade AS cidade_cliente,
    p.categoria,
    t.trimestre,
    COUNT(DISTINCT f.venda_uuid) AS total_vendas,
    SUM(f.valor_liquido)         AS faturamento
FROM dw_estrela.fato_venda f
         JOIN dw_estrela.dim_cliente c ON c.sk_cliente = f.sk_cliente
         JOIN dw_estrela.dim_produto p ON p.sk_produto = f.sk_produto
         JOIN dw_estrela.dim_tempo t   ON t.sk_tempo = f.sk_tempo
WHERE f.sk_cliente <> 0
  AND t.fim_de_semana = TRUE
  -- Prefixos em vez de nomes exatos: os nomes das categorias passam por INITCAP no ETL
  -- (ex.: 'Carnes, Aves E Peixes'), e o filtro continua valendo se essa padronizacao mudar.
  AND p.categoria ILIKE ANY (ARRAY['Carnes%', 'Produtos Frescos%', 'Bebidas%', 'Mercearia%'])
GROUP BY c.cidade, p.categoria, t.trimestre
ORDER BY faturamento DESC;;

-- 4.2 Dice: Vendas operadas por Caixas no 1o semestre por cidade da loja
SELECT
    l.cidade AS cidade_loja,
    l.nome_loja,
    fn.nome_funcionario,
    SUM(f.quantidade)    AS itens_registrados,
    SUM(f.valor_liquido) AS receita_gerada
FROM dw_estrela.fato_venda f
         JOIN dw_estrela.dim_loja l         ON l.sk_loja = f.sk_loja
         JOIN dw_estrela.dim_funcionario fn ON fn.sk_funcionario = f.sk_funcionario
         JOIN dw_estrela.dim_tempo t        ON t.sk_tempo = f.sk_tempo
WHERE t.semestre = 1
  AND fn.cargo = 'Caixa'
GROUP BY l.cidade, l.nome_loja, fn.nome_funcionario
ORDER BY receita_gerada DESC;;

-- ============================================================================
-- 5. PIVOT (Tabulacao cruzada: linhas x colunas)
-- ============================================================================

-- 5.1 Pivot: Faturamento por Categoria (linhas) x Trimestre (colunas) de 2025
-- Fixa um ano: as vendas vao de out/2024 a nov/2026, e sem o filtro cada coluna somaria trimestres de anos diferentes.
-- 2025 e o unico ano completo no seed.
SELECT
    p.categoria,
    COALESCE(SUM(CASE WHEN t.trimestre = 1 THEN f.valor_liquido END), 0) AS trim_1,
    COALESCE(SUM(CASE WHEN t.trimestre = 2 THEN f.valor_liquido END), 0) AS trim_2,
    COALESCE(SUM(CASE WHEN t.trimestre = 3 THEN f.valor_liquido END), 0) AS trim_3,
    COALESCE(SUM(CASE WHEN t.trimestre = 4 THEN f.valor_liquido END), 0) AS trim_4,
    SUM(f.valor_liquido) AS total_anual
FROM dw_estrela.fato_venda f
         JOIN dw_estrela.dim_produto p ON p.sk_produto = f.sk_produto
         JOIN dw_estrela.dim_tempo t   ON t.sk_tempo = f.sk_tempo
WHERE t.ano = 2025
GROUP BY p.categoria
ORDER BY total_anual DESC;;

-- 5.2 Pivot: Faturamento por Loja (linhas) x Dia da Semana (colunas)
SELECT
    l.nome_loja,
    COALESCE(SUM(CASE WHEN t.dia_semana = 1 THEN f.valor_liquido END), 0) AS seg,
    COALESCE(SUM(CASE WHEN t.dia_semana = 2 THEN f.valor_liquido END), 0) AS ter,
    COALESCE(SUM(CASE WHEN t.dia_semana = 3 THEN f.valor_liquido END), 0) AS qua,
    COALESCE(SUM(CASE WHEN t.dia_semana = 4 THEN f.valor_liquido END), 0) AS qui,
    COALESCE(SUM(CASE WHEN t.dia_semana = 5 THEN f.valor_liquido END), 0) AS sex,
    COALESCE(SUM(CASE WHEN t.dia_semana = 6 THEN f.valor_liquido END), 0) AS sab,
    COALESCE(SUM(CASE WHEN t.dia_semana = 7 THEN f.valor_liquido END), 0) AS dom,
    SUM(f.valor_liquido) AS total_loja
FROM dw_estrela.fato_venda f
         JOIN dw_estrela.dim_loja l  ON l.sk_loja = f.sk_loja
         JOIN dw_estrela.dim_tempo t ON t.sk_tempo = f.sk_tempo
GROUP BY l.nome_loja
ORDER BY total_loja DESC;;

-- ============================================================================
-- 6. CUBE E GROUPING SETS (todos os subtotais de uma vez)
-- ============================================================================

-- 6.1 CUBE: Faturamento de 2025 por UF da loja x Categoria, com todas as combinacoes de subtotal
-- CUBE (a, b) gera os agrupamentos (a, b), (a), (b) e (): o total de cada UF, de cada categoria e o geral.
SELECT
    CASE GROUPING(l.uf, p.categoria)
        WHEN 0 THEN 'UF x Categoria'
        WHEN 1 THEN 'Total da UF'
        WHEN 2 THEN 'Total da categoria'
        WHEN 3 THEN 'Total geral'
    END AS nivel,
    l.uf,
    p.categoria,
    COUNT(DISTINCT f.venda_uuid) AS total_vendas,
    SUM(f.valor_liquido)         AS faturamento
FROM dw_estrela.fato_venda f
         JOIN dw_estrela.dim_loja l    ON l.sk_loja = f.sk_loja
         JOIN dw_estrela.dim_produto p ON p.sk_produto = f.sk_produto
         JOIN dw_estrela.dim_tempo t   ON t.sk_tempo = f.sk_tempo
WHERE t.ano = 2025
GROUP BY CUBE (l.uf, p.categoria)
ORDER BY GROUPING(l.uf, p.categoria), l.uf, faturamento DESC;;

-- 6.2 GROUPING SETS: Clientes identificados x nao identificados, por loja e por dia da semana, numa consulta so
-- Equivale a dois GROUP BY unidos com UNION ALL, mas le o fato uma vez.
SELECT
    CASE WHEN GROUPING(l.nome_loja) = 0 THEN 'Por loja' ELSE 'Por dia da semana' END AS visao,
    l.nome_loja,
    t.nome_dia_semana,
    SUM(f.valor_liquido) FILTER (WHERE f.sk_cliente <> 0) AS faturamento_identificado,
    SUM(f.valor_liquido) FILTER (WHERE f.sk_cliente = 0)  AS faturamento_nao_identificado,
    ROUND(100.0 * SUM(f.valor_liquido) FILTER (WHERE f.sk_cliente <> 0) / SUM(f.valor_liquido), 1)
                                                           AS pct_identificado
FROM dw_estrela.fato_venda f
         JOIN dw_estrela.dim_loja l  ON l.sk_loja = f.sk_loja
         JOIN dw_estrela.dim_tempo t ON t.sk_tempo = f.sk_tempo
GROUP BY GROUPING SETS ((l.nome_loja), (t.dia_semana, t.nome_dia_semana))
ORDER BY visao, l.nome_loja, MIN(t.dia_semana);;

-- ============================================================================
-- 7. COMPARATIVO DE DESEMPENHO (EXPLAIN ANALYZE: OLTP vs ESTRELA vs FLOCO)
--
-- Metodo (detalhes e resultados em docs/relatorio/sergio/comparacao-desempenho.md):
--   1. depois da carga, atualizar as estatisticas, fora de transacao (VACUUM nao roda dentro de uma):
--        VACUUM ANALYZE sale;; VACUUM ANALYZE sale_items;; VACUUM ANALYZE dw_estrela.fato_venda;; ...
--   2. executar cada consulta varias vezes, descartar a primeira e usar a mediana do "Execution Time";
--   3. BUFFERS mostra se as paginas vieram da memoria (shared hit) ou do disco (read).
-- As tres consultas devolvem as mesmas 2.332 linhas (loja x categoria x ano x mes).
-- ============================================================================

-- 7.1 Consulta analitica no banco transacional (OLTP - schema public)
EXPLAIN (ANALYZE, BUFFERS)
SELECT
    st.name AS loja,
    c.name  AS categoria,
    EXTRACT(YEAR FROM (s.created_at AT TIME ZONE 'America/Bahia')) AS ano,
    EXTRACT(MONTH FROM (s.created_at AT TIME ZONE 'America/Bahia')) AS mes,
    SUM(ROUND(si.quantity * si.unit_price_in_cents) / 100.0) AS faturamento
FROM public.sale_items si
         JOIN public.sale s     ON s.id = si.sale_id
         JOIN public.product p  ON p.id = si.product_id
         JOIN public.category c ON c.id = p.category_id
         JOIN public.store st   ON st.id = s.store_id
WHERE s.status = 'COMPLETED'
GROUP BY st.name, c.name, ano, mes
ORDER BY ano, mes, faturamento DESC;;

-- 7.2 Mesma consulta no Modelo Estrela (dw_estrela)
EXPLAIN (ANALYZE, BUFFERS)
SELECT
    l.nome_loja AS loja,
    p.categoria AS categoria,
    t.ano,
    t.mes,
    SUM(f.valor_liquido) AS faturamento
FROM dw_estrela.fato_venda f
         JOIN dw_estrela.dim_loja l    ON l.sk_loja = f.sk_loja
         JOIN dw_estrela.dim_produto p ON p.sk_produto = f.sk_produto
         JOIN dw_estrela.dim_tempo t   ON t.sk_tempo = f.sk_tempo
GROUP BY l.nome_loja, p.categoria, t.ano, t.mes
ORDER BY t.ano, t.mes, faturamento DESC;;

-- 7.3 Mesma consulta no Modelo Floco de Neve (dw_floco)
EXPLAIN (ANALYZE, BUFFERS)
SELECT
    l.nome_loja      AS loja,
    c.nome_categoria AS categoria,
    t.ano,
    t.mes,
    SUM(f.valor_liquido) AS faturamento
FROM dw_floco.fato_venda f
         JOIN dw_floco.dim_loja l      ON l.sk_loja = f.sk_loja
         JOIN dw_floco.dim_produto p   ON p.sk_produto = f.sk_produto
         JOIN dw_floco.dim_categoria c ON c.sk_categoria = p.sk_categoria
         JOIN dw_floco.dim_tempo t     ON t.sk_tempo = f.sk_tempo
GROUP BY l.nome_loja, c.nome_categoria, t.ano, t.mes
ORDER BY t.ano, t.mes, faturamento DESC;;