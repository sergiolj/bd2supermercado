CREATE OR REPLACE PROCEDURE etl.executar_carga(p_carga_completa BOOLEAN DEFAULT FALSE)
LANGUAGE plpgsql
AS $$
DECLARE
v_id_execucao     INTEGER;
    v_qtd_antes       INTEGER := 0;
    v_qtd_depois      INTEGER := 0;
    v_fatos_inseridos INTEGER := 0;
    v_data_min        DATE;
    v_data_max        DATE;
BEGIN
    IF NOT pg_try_advisory_xact_lock(987654321) THEN
        RAISE EXCEPTION 'Ja existe uma execucao de ETL em andamento.';
END IF;

INSERT INTO etl.log_execucao (modo_completo, status)
VALUES (p_carga_completa, 'EM_ANDAMENTO')
    RETURNING id_execucao INTO v_id_execucao;

IF p_carga_completa THEN
        TRUNCATE TABLE dw_floco.fato_venda,
                       dw_floco.dim_produto,
                       dw_floco.dim_categoria,
                       dw_floco.dim_cliente,
                       dw_floco.dim_loja,
                       dw_floco.dim_cidade,
                       dw_floco.dim_estado,
                       dw_floco.dim_funcionario,
                       dw_floco.dim_tempo
        RESTART IDENTITY CASCADE;

TRUNCATE TABLE dw_estrela.fato_venda,
                       dw_estrela.dim_produto,
                       dw_estrela.dim_cliente,
                       dw_estrela.dim_loja,
                       dw_estrela.dim_funcionario,
                       dw_estrela.dim_tempo
        RESTART IDENTITY CASCADE;

INSERT INTO dw_estrela.dim_cliente (
    sk_cliente, cliente_uuid, nome_cliente, cidade, uf, data_inicio, data_fim, atual
)
VALUES (0, NULL, 'Cliente Nao Identificado', 'Nao informado', 'NI', '1900-01-01', NULL, TRUE)
    ON CONFLICT (sk_cliente) DO NOTHING;
END IF;

SELECT COUNT(*) INTO v_qtd_antes FROM dw_estrela.fato_venda;

-- 1. EXTRACAO E PADRONIZACAO PARA STAGING
TRUNCATE TABLE stg.venda_item_stg;

INSERT INTO stg.venda_item_stg (
    sale_id, sale_uuid, sale_item_id, data_venda,
    product_uuid, product_barcode, product_name, product_unit,
    category_uuid, category_name,
    customer_uuid, customer_name, customer_city, customer_state,
    store_uuid, store_name, store_city, store_state,
    seller_uuid, seller_name, seller_role,
    quantidade, preco_unitario, valor_bruto, desconto, valor_liquido
)
SELECT
    s.id,
    s.uuid,
    si.id,
    (s.created_at AT TIME ZONE 'America/Bahia')::DATE,
        p.uuid,
    COALESCE(NULLIF(TRIM(p.barcode), ''), 'SEM-CODIGO'),
    INITCAP(TRIM(p.name)),
    CASE p.unit_of_measure
        WHEN 'UNIT' THEN 'Unidade'
        WHEN 'KG'   THEN 'Quilograma'
        WHEN 'G'    THEN 'Grama'
        WHEN 'L'    THEN 'Litro'
        WHEN 'ML'   THEN 'Mililitro'
        ELSE COALESCE(p.unit_of_measure, 'Nao informado')
        END,
    c.uuid,
    INITCAP(TRIM(c.name)),
    cp.uuid,
    CASE WHEN cp.uuid IS NOT NULL THEN INITCAP(TRIM(cp.name)) ELSE NULL END,
    CASE WHEN cp.uuid IS NOT NULL THEN COALESCE(NULLIF(INITCAP(TRIM(cp.city)), ''), 'Nao informado') ELSE NULL END,
    CASE WHEN cp.uuid IS NOT NULL THEN COALESCE(NULLIF(UPPER(TRIM(cp.state)), ''), 'NI') ELSE NULL END,
    st.uuid,
    TRIM(st.name),
    COALESCE(NULLIF(INITCAP(TRIM(st.city)), ''), 'Nao informado'),
    COALESCE(NULLIF(UPPER(TRIM(st.state)), ''), 'NI'),
    sp.uuid,
    INITCAP(TRIM(sp.name)),
    CASE sf.role
        WHEN 'CASHIER'   THEN 'Caixa'
        WHEN 'MANAGER'   THEN 'Gerente'
        WHEN 'STOCKIST'  THEN 'Estoquista'
        WHEN 'ADMIN'     THEN 'Administrador'
        ELSE COALESCE(sf.role, 'Nao informado')
        END,
    si.quantity,
    ROUND(si.unit_price_in_cents / 100.0, 2),
    ROUND((si.quantity * si.unit_price_in_cents) / 100.0, 2),
    0.00,
    ROUND((si.quantity * si.unit_price_in_cents) / 100.0, 2)
FROM public.sale_items si
         JOIN public.sale s          ON s.id = si.sale_id
         JOIN public.product p       ON p.id = si.product_id
         JOIN public.category c      ON c.id = p.category_id
         JOIN public.store st        ON st.id = s.store_id
         JOIN public.staff sf        ON sf.id = s.seller_id
         JOIN public.person sp       ON sp.id = sf.id
         LEFT JOIN public.customer cu ON cu.id = s.customer_id
         LEFT JOIN public.person cp   ON cp.id = cu.id
WHERE s.status = 'COMPLETED'
  AND NOT EXISTS (
    SELECT 1
    FROM dw_estrela.fato_venda fv
    WHERE fv.venda_uuid = s.uuid
      AND fv.id_item_origem = si.id
);

-- 2. CARGA DA DIMENSAO TEMPO
SELECT MIN(data_venda), MAX(data_venda)
INTO v_data_min, v_data_max
FROM stg.venda_item_stg;

IF v_data_min IS NOT NULL AND v_data_max IS NOT NULL THEN
        INSERT INTO dw_estrela.dim_tempo (
            sk_tempo, data_completa, dia, mes, nome_mes,
            trimestre, semestre, ano, dia_semana, nome_dia_semana, fim_de_semana
        )
SELECT
    TO_CHAR(d, 'YYYYMMDD')::INTEGER,
        d,
    EXTRACT(DAY FROM d)::SMALLINT,
        EXTRACT(MONTH FROM d)::SMALLINT,
        CASE EXTRACT(MONTH FROM d)::INTEGER
                WHEN 1  THEN 'Janeiro'
                WHEN 2  THEN 'Fevereiro'
                WHEN 3  THEN 'Marco'
                WHEN 4  THEN 'Abril'
                WHEN 5  THEN 'Maio'
                WHEN 6  THEN 'Junho'
                WHEN 7  THEN 'Julho'
                WHEN 8  THEN 'Agosto'
                WHEN 9  THEN 'Setembro'
                WHEN 10 THEN 'Outubro'
                WHEN 11 THEN 'Novembro'
                WHEN 12 THEN 'Dezembro'
END,
            EXTRACT(QUARTER FROM d)::SMALLINT,
            CASE WHEN EXTRACT(MONTH FROM d) <= 6 THEN 1 ELSE 2 END::SMALLINT,
            EXTRACT(YEAR FROM d)::SMALLINT,
            EXTRACT(ISODOW FROM d)::SMALLINT,
            CASE EXTRACT(ISODOW FROM d)::INTEGER
                WHEN 1 THEN 'Segunda-feira'
                WHEN 2 THEN 'Terca-feira'
                WHEN 3 THEN 'Quarta-feira'
                WHEN 4 THEN 'Quinta-feira'
                WHEN 5 THEN 'Sexta-feira'
                WHEN 6 THEN 'Sabado'
                WHEN 7 THEN 'Domingo'
END,
            EXTRACT(ISODOW FROM d) IN (6, 7)
        FROM generate_series(
            DATE_TRUNC('year', v_data_min)::DATE,
            (DATE_TRUNC('year', v_data_max) + INTERVAL '1 year - 1 day')::DATE,
            INTERVAL '1 day'
        ) AS d
        ON CONFLICT (sk_tempo) DO NOTHING;
END IF;

    -- 3. CARGA DAS DIMENSOES TIPO 1 (PRODUTO, LOJA, FUNCIONARIO)
INSERT INTO dw_estrela.dim_produto (produto_uuid, codigo_barras, nome_produto, unidade_medida, categoria)
SELECT DISTINCT
    product_uuid, product_barcode, product_name, product_unit, category_name
FROM stg.venda_item_stg
    ON CONFLICT (produto_uuid) DO UPDATE SET
    codigo_barras  = EXCLUDED.codigo_barras,
                                      nome_produto   = EXCLUDED.nome_produto,
                                      unidade_medida = EXCLUDED.unidade_medida,
                                      categoria      = EXCLUDED.categoria;

INSERT INTO dw_estrela.dim_loja (loja_uuid, nome_loja, cidade, uf)
SELECT DISTINCT
    store_uuid, store_name, store_city, store_state
FROM stg.venda_item_stg
    ON CONFLICT (loja_uuid) DO UPDATE SET
    nome_loja = EXCLUDED.nome_loja,
                                   cidade    = EXCLUDED.cidade,
                                   uf        = EXCLUDED.uf;

INSERT INTO dw_estrela.dim_funcionario (funcionario_uuid, nome_funcionario, cargo)
SELECT DISTINCT
    seller_uuid, seller_name, seller_role
FROM stg.venda_item_stg
    ON CONFLICT (funcionario_uuid) DO UPDATE SET
    nome_funcionario = EXCLUDED.nome_funcionario,
                                          cargo            = EXCLUDED.cargo;

-- 4. CARGA DA DIMENSAO CLIENTE (SCD TIPO 2 PARA CIDADE E UF)
UPDATE dw_estrela.dim_cliente dc
SET data_fim = CURRENT_DATE - 1,
    atual    = FALSE
    FROM (
        SELECT DISTINCT customer_uuid, customer_name, customer_city, customer_state
        FROM stg.venda_item_stg
        WHERE customer_uuid IS NOT NULL
    ) src
WHERE dc.cliente_uuid = src.customer_uuid
  AND dc.atual = TRUE
  AND (dc.cidade <> src.customer_city OR dc.uf <> src.customer_state);

UPDATE dw_estrela.dim_cliente dc
SET nome_cliente = src.customer_name
    FROM (
        SELECT DISTINCT customer_uuid, customer_name
        FROM stg.venda_item_stg
        WHERE customer_uuid IS NOT NULL
    ) src
WHERE dc.cliente_uuid = src.customer_uuid
  AND dc.atual = TRUE
  AND dc.nome_cliente <> src.customer_name;

INSERT INTO dw_estrela.dim_cliente (
    cliente_uuid, nome_cliente, cidade, uf, data_inicio, data_fim, atual
)
SELECT DISTINCT
    src.customer_uuid,
    src.customer_name,
    src.customer_city,
    src.customer_state,
    CASE
        WHEN EXISTS (SELECT 1 FROM dw_estrela.dim_cliente old WHERE old.cliente_uuid = src.customer_uuid)
            THEN CURRENT_DATE
        ELSE '1900-01-01'::DATE
END,
        NULL::DATE,
        TRUE
    FROM stg.venda_item_stg src
    WHERE src.customer_uuid IS NOT NULL
      AND NOT EXISTS (
          SELECT 1
          FROM dw_estrela.dim_cliente dc
          WHERE dc.cliente_uuid = src.customer_uuid
            AND dc.atual = TRUE
      );

    -- 5. CARGA DA FATO VENDA (MODELO ESTRELA)
INSERT INTO dw_estrela.fato_venda (
    venda_uuid, id_item_origem, sk_tempo, sk_produto, sk_cliente, sk_loja, sk_funcionario,
    quantidade, preco_unitario, valor_bruto, desconto, valor_liquido
)
SELECT
    s.sale_uuid,
    s.sale_item_id,
    dt.sk_tempo,
    dp.sk_produto,
    COALESCE(dc.sk_cliente, 0),
    dl.sk_loja,
    df.sk_funcionario,
    s.quantidade,
    s.preco_unitario,
    s.valor_bruto,
    s.desconto,
    s.valor_liquido
FROM stg.venda_item_stg s
         JOIN dw_estrela.dim_tempo dt       ON dt.data_completa = s.data_venda
         JOIN dw_estrela.dim_produto dp     ON dp.produto_uuid = s.product_uuid
         JOIN dw_estrela.dim_loja dl        ON dl.loja_uuid = s.store_uuid
         JOIN dw_estrela.dim_funcionario df ON df.funcionario_uuid = s.seller_uuid
         LEFT JOIN dw_estrela.dim_cliente dc
                   ON dc.cliente_uuid = s.customer_uuid
                       AND s.data_venda >= dc.data_inicio
                       AND (dc.data_fim IS NULL OR s.data_venda <= dc.data_fim)
    ON CONFLICT (venda_uuid, id_item_origem) DO NOTHING;

-- 6. SINCRONIZACAO DO MODELO FLOCO DE NEVE (dw_floco)
INSERT INTO dw_floco.dim_tempo
SELECT * FROM dw_estrela.dim_tempo
    ON CONFLICT (sk_tempo) DO NOTHING;

INSERT INTO dw_floco.dim_categoria (nome_categoria)
SELECT DISTINCT categoria FROM dw_estrela.dim_produto
    ON CONFLICT (nome_categoria) DO NOTHING;

INSERT INTO dw_floco.dim_produto (
    sk_produto, produto_uuid, codigo_barras, nome_produto, unidade_medida, sk_categoria
)
SELECT
    p.sk_produto, p.produto_uuid, p.codigo_barras, p.nome_produto, p.unidade_medida, c.sk_categoria
FROM dw_estrela.dim_produto p
         JOIN dw_floco.dim_categoria c ON c.nome_categoria = p.categoria
    ON CONFLICT (sk_produto) DO UPDATE SET
    codigo_barras  = EXCLUDED.codigo_barras,
                                    nome_produto   = EXCLUDED.nome_produto,
                                    unidade_medida = EXCLUDED.unidade_medida,
                                    sk_categoria   = EXCLUDED.sk_categoria;

INSERT INTO dw_floco.dim_estado (uf)
SELECT DISTINCT uf FROM dw_estrela.dim_cliente
UNION
SELECT DISTINCT uf FROM dw_estrela.dim_loja
    ON CONFLICT (uf) DO NOTHING;

INSERT INTO dw_floco.dim_cidade (nome_cidade, sk_estado)
SELECT DISTINCT d.cidade, e.sk_estado
FROM (
         SELECT cidade, uf FROM dw_estrela.dim_cliente
         UNION
         SELECT cidade, uf FROM dw_estrela.dim_loja
     ) d
         JOIN dw_floco.dim_estado e ON e.uf = d.uf
    ON CONFLICT (nome_cidade, sk_estado) DO NOTHING;

INSERT INTO dw_floco.dim_cliente (
    sk_cliente, cliente_uuid, nome_cliente, sk_cidade, data_inicio, data_fim, atual
)
SELECT
    c.sk_cliente, c.cliente_uuid, c.nome_cliente, cid.sk_cidade, c.data_inicio, c.data_fim, c.atual
FROM dw_estrela.dim_cliente c
         JOIN dw_floco.dim_estado e   ON e.uf = c.uf
         JOIN dw_floco.dim_cidade cid ON cid.nome_cidade = c.cidade AND cid.sk_estado = e.sk_estado
    ON CONFLICT (sk_cliente) DO UPDATE SET
    nome_cliente = EXCLUDED.nome_cliente,
                                    sk_cidade    = EXCLUDED.sk_cidade,
                                    data_fim     = EXCLUDED.data_fim,
                                    atual        = EXCLUDED.atual;

INSERT INTO dw_floco.dim_loja (sk_loja, loja_uuid, nome_loja, sk_cidade)
SELECT
    l.sk_loja, l.loja_uuid, l.nome_loja, cid.sk_cidade
FROM dw_estrela.dim_loja l
         JOIN dw_floco.dim_estado e   ON e.uf = l.uf
         JOIN dw_floco.dim_cidade cid ON cid.nome_cidade = l.cidade AND cid.sk_estado = e.sk_estado
    ON CONFLICT (sk_loja) DO UPDATE SET
    nome_loja = EXCLUDED.nome_loja,
                                 sk_cidade = EXCLUDED.sk_cidade;

INSERT INTO dw_floco.dim_funcionario
SELECT * FROM dw_estrela.dim_funcionario
    ON CONFLICT (sk_funcionario) DO UPDATE SET
    nome_funcionario = EXCLUDED.nome_funcionario,
                                        cargo            = EXCLUDED.cargo;

INSERT INTO dw_floco.fato_venda
SELECT * FROM dw_estrela.fato_venda
    ON CONFLICT (venda_uuid, id_item_origem) DO NOTHING;

SELECT COUNT(*) INTO v_qtd_depois FROM dw_estrela.fato_venda;
v_fatos_inseridos := v_qtd_depois - v_qtd_antes;

UPDATE etl.log_execucao
SET data_fim        = NOW(),
    fatos_inseridos = v_fatos_inseridos,
    status          = 'CONCLUIDO',
    mensagem        = FORMAT('Antes: %s | Depois: %s | Inseridos: %s', v_qtd_antes, v_qtd_depois, v_fatos_inseridos)
WHERE id_execucao = v_id_execucao;
END;
$$;;