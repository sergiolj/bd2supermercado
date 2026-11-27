-- ============================================================================
-- 1. ROLL-UP (Agrupamento hierarquico crescente: detalhado -> consolidado)
-- ============================================================================

-- 1.1 Roll-up Temporal: Faturamento por Ano -> Semestre -> Trimestre -> Mes
SELECT
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
  AND p.categoria IN ('Acougue', 'Hortifruti', 'Bebidas', 'Mercearia')
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

-- 5.1 Pivot: Faturamento por Categoria (linhas) x Trimestre (colunas)
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
-- 6. COMPARATIVO DE DESEMPENHO (EXPLAIN ANALYZE: OLTP vs ESTRELA vs FLOCO)
-- ============================================================================

-- 6.1 Consulta analitica no banco transacional (OLTP - schema public)
EXPLAIN ANALYZE
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

-- 6.2 Mesma consulta no Modelo Estrela (dw_estrela)
EXPLAIN ANALYZE
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

-- 6.3 Mesma consulta no Modelo Floco de Neve (dw_floco)
EXPLAIN ANALYZE
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