-- ETL: OLTP (public) -> staging (stg) -> modelo estrela (dw_estrela) -> floco de neve (dw_floco).  Ref. §4.3 item 2.
--
-- Executado a cada subida da aplicacao (spring.sql.init.*): so (re)cria as funcoes e procedures, nao carrega nada.
-- A carga e feita por:
--     CALL etl.executar();        incremental: so os itens de venda concluida que ainda nao estao no DW
--     CALL etl.executar(true);    completa: esvazia o DW e carrega tudo de novo
-- A aplicacao chama a procedure em POST /api/etl/run (EtlService). Cada CALL e um comando so, entao roda numa
-- transacao: se algum passo falhar, nada do DW muda (nem o registro em etl.execucao).
--
-- Passos:
--   1. extrair            copia do OLTP para o staging, ja transformando (padronizacao, nulos, codigos, medidas)
--   2. dim_tempo          gera os dias dos anos que as vendas cobrem
--   3. dimensoes estrela  produto, loja e funcionario por upsert (SCD tipo 1); cliente com SCD tipo 2 na cidade/UF
--   4. fato estrela       troca chaves naturais (uuid) pelas chaves substitutas e insere
--   5. floco              normaliza as dimensoes (categoria, cidade, estado) e copia o fato com as mesmas chaves
--
-- Os comandos sao separados por duas barras de ponto e virgula (spring.sql.init.separator).

-- Funcoes de padronizacao -------------------------------------------------------------------------------------

-- Tira espacos das pontas e junta espacos repetidos; texto vazio vira nulo (para o coalesce tratar).
CREATE OR REPLACE FUNCTION etl.padronizar_texto(texto text) RETURNS text AS $$
    SELECT nullif(btrim(regexp_replace(texto, '\s+', ' ', 'g')), '');
$$ LANGUAGE sql IMMUTABLE;;

-- Sigla da UF em maiusculas; vazia ou desconhecida vira NI (nao informado).
CREATE OR REPLACE FUNCTION etl.padronizar_uf(sigla text) RETURNS char(2) AS $$
    SELECT coalesce((SELECT u.uf FROM etl.uf u WHERE u.uf = upper(btrim(sigla))), 'NI');
$$ LANGUAGE sql STABLE;;

-- Dia de hoje no fuso das lojas (o mesmo do seed e das consultas Criteria).
CREATE OR REPLACE FUNCTION etl.hoje() RETURNS date AS $$
    SELECT (now() AT TIME ZONE 'America/Bahia')::date;
$$ LANGUAGE sql STABLE;;

-- 1. Extracao + transformacao para o staging ------------------------------------------------------------------

CREATE OR REPLACE PROCEDURE etl.extrair() AS $$
BEGIN
    TRUNCATE stg.produto, stg.loja, stg.funcionario, stg.cliente, stg.venda_item;

    -- Dimensoes: extracao completa (sao pequenas). Inclui os apagados (soft delete): vendas antigas apontam para eles.
    INSERT INTO stg.produto (produto_uuid, codigo_barras, nome, unidade_medida, categoria_uuid, categoria, ativo)
    SELECT p.uuid,
           btrim(p.barcode),
           coalesce(etl.padronizar_texto(p.name), 'Sem nome'),
           CASE p.unit_of_measure
               WHEN 'UNIT'       THEN 'Unidade'
               WHEN 'KG'         THEN 'Quilograma'
               WHEN 'GRAM'       THEN 'Grama'
               WHEN 'LITER'      THEN 'Litro'
               WHEN 'MILLILITER' THEN 'Mililitro'
               ELSE p.unit_of_measure
           END,
           c.uuid,
           coalesce(etl.padronizar_texto(c.name), 'Sem categoria'),
           p.deleted_at IS NULL
      FROM public.product p
      JOIN public.category c ON c.id = p.category_id;

    INSERT INTO stg.loja (loja_uuid, nome, cidade, uf)
    SELECT s.uuid,
           coalesce(etl.padronizar_texto(s.name), 'Sem nome'),
           coalesce(etl.padronizar_texto(s.city), 'Não informado'),
           etl.padronizar_uf(s.state)
      FROM public.store s;

    INSERT INTO stg.funcionario (funcionario_uuid, nome, cargo)
    SELECT p.uuid,
           coalesce(etl.padronizar_texto(p.name), 'Sem nome'),
           CASE st.role
               WHEN 'ADMIN'    THEN 'Administrador'
               WHEN 'MANAGER'  THEN 'Gerente'
               WHEN 'CASHIER'  THEN 'Caixa'
               WHEN 'STOCKIST' THEN 'Estoquista'
               ELSE st.role
           END
      FROM public.staff st
      JOIN public.person p ON p.id = st.id;

    INSERT INTO stg.cliente (cliente_uuid, nome, cidade, uf)
    SELECT p.uuid,
           coalesce(etl.padronizar_texto(p.name), 'Sem nome'),
           coalesce(etl.padronizar_texto(p.city), 'Não informado'),
           etl.padronizar_uf(p.state)
      FROM public.customer cu
      JOIN public.person p ON p.id = cu.id;

    -- Fato: extracao incremental. So vendas concluidas (as canceladas e em aberto nao sao faturamento), e so os
    -- itens que ainda nao estao no DW. Uma venda em aberto entra quando for concluida, numa execucao seguinte.
    INSERT INTO stg.venda_item (venda_uuid, id_item_origem, data_venda, produto_uuid, loja_uuid, funcionario_uuid,
                                cliente_uuid, quantidade, preco_unitario, valor_bruto, desconto, valor_liquido)
    SELECT s.uuid,
           i.id,
           (s.created_at AT TIME ZONE 'America/Bahia')::date,   -- o dia da venda no calendario da loja
           p.uuid,
           st.uuid,
           seller.uuid,
           buyer.uuid,                                           -- nulo quando a venda nao identificou o cliente
           i.quantity,
           i.unit_price_in_cents / 100.0,                        -- centavos (OLTP) -> reais (DW)
           round(i.quantity * i.unit_price_in_cents) / 100.0,    -- valor_bruto = quantidade x preco
           0,                                                    -- desconto: o OLTP ainda nao registra desconto
           round(i.quantity * i.unit_price_in_cents) / 100.0     -- valor_liquido = bruto - desconto
      FROM public.sale_items i
      JOIN public.sale s       ON s.id = i.sale_id
      JOIN public.product p    ON p.id = i.product_id
      JOIN public.store st     ON st.id = s.store_id
      JOIN public.person seller ON seller.id = s.seller_id
      LEFT JOIN public.person buyer ON buyer.id = s.customer_id
     WHERE s.status = 'COMPLETED'
       AND NOT EXISTS (SELECT 1
                         FROM dw_estrela.fato_venda f
                        WHERE f.venda_uuid = s.uuid
                          AND f.id_item_origem = i.id);
END;
$$ LANGUAGE plpgsql;;

-- 2. Dimensao Tempo -------------------------------------------------------------------------------------------
-- Gera todos os dias dos anos cobertos pelas vendas extraidas (do 1o de janeiro do primeiro ano a 31 de dezembro
-- do ultimo), sem buracos. Os nomes vem de listas fixas, para nao depender do idioma do servidor.

CREATE OR REPLACE PROCEDURE etl.carregar_dim_tempo() AS $$
DECLARE
    meses text[] := ARRAY['Janeiro', 'Fevereiro', 'Março', 'Abril', 'Maio', 'Junho', 'Julho', 'Agosto',
                          'Setembro', 'Outubro', 'Novembro', 'Dezembro'];
    dias  text[] := ARRAY['Segunda-feira', 'Terça-feira', 'Quarta-feira', 'Quinta-feira', 'Sexta-feira',
                          'Sábado', 'Domingo'];
BEGIN
    INSERT INTO dw_estrela.dim_tempo (sk_tempo, data, dia, dia_semana, nome_dia_semana, fim_de_semana, semana_ano,
                                      mes, nome_mes, trimestre, semestre, ano)
    SELECT to_char(d, 'YYYYMMDD')::integer,
           d,
           extract(day FROM d),
           extract(isodow FROM d),
           dias[extract(isodow FROM d)::integer],
           extract(isodow FROM d) IN (6, 7),
           extract(week FROM d),
           extract(month FROM d),
           meses[extract(month FROM d)::integer],
           extract(quarter FROM d),
           CASE WHEN extract(month FROM d) <= 6 THEN 1 ELSE 2 END,
           extract(year FROM d)
      FROM (SELECT generate_series(date_trunc('year', min(data_venda)),
                                   date_trunc('year', max(data_venda)) + interval '1 year - 1 day',
                                   interval '1 day')::date AS d
              FROM stg.venda_item) dias_do_periodo
    ON CONFLICT (sk_tempo) DO NOTHING;
END;
$$ LANGUAGE plpgsql;;

-- 3. Dimensoes do modelo estrela -------------------------------------------------------------------------------

-- Membro fixo do cliente nao identificado (sk 0). Recriado depois de uma carga completa.
CREATE OR REPLACE PROCEDURE etl.garantir_cliente_nao_identificado() AS $$
BEGIN
    INSERT INTO dw_estrela.dim_cliente (sk_cliente, cliente_uuid, nome, cidade, uf, estado, regiao,
                                        data_inicio, data_fim, atual)
    VALUES (0, NULL, 'Não identificado', 'Não informado', 'NI', 'Não informado', 'Não informado',
            DATE '1900-01-01', DATE '9999-12-31', true)
    ON CONFLICT (sk_cliente) DO NOTHING;
END;
$$ LANGUAGE plpgsql;;

-- Produto, loja e funcionario: SCD tipo 1 (a mudanca sobrescreve). O WHERE do DO UPDATE evita regravar linhas
-- que nao mudaram.
CREATE OR REPLACE PROCEDURE etl.carregar_dimensoes_tipo1() AS $$
BEGIN
    INSERT INTO dw_estrela.dim_produto AS d (produto_uuid, codigo_barras, nome, unidade_medida, categoria, ativo)
    SELECT produto_uuid, codigo_barras, nome, unidade_medida, categoria, ativo
      FROM stg.produto
    ON CONFLICT (produto_uuid) DO UPDATE
       SET codigo_barras = EXCLUDED.codigo_barras, nome = EXCLUDED.nome, unidade_medida = EXCLUDED.unidade_medida,
           categoria = EXCLUDED.categoria, ativo = EXCLUDED.ativo
     WHERE (d.codigo_barras, d.nome, d.unidade_medida, d.categoria, d.ativo)
           IS DISTINCT FROM (EXCLUDED.codigo_barras, EXCLUDED.nome, EXCLUDED.unidade_medida, EXCLUDED.categoria,
                             EXCLUDED.ativo);

    INSERT INTO dw_estrela.dim_loja AS d (loja_uuid, nome, cidade, uf, estado, regiao)
    SELECT s.loja_uuid, s.nome, s.cidade, s.uf, u.nome, u.regiao
      FROM stg.loja s
      JOIN etl.uf u ON u.uf = s.uf
    ON CONFLICT (loja_uuid) DO UPDATE
       SET nome = EXCLUDED.nome, cidade = EXCLUDED.cidade, uf = EXCLUDED.uf, estado = EXCLUDED.estado,
           regiao = EXCLUDED.regiao
     WHERE (d.nome, d.cidade, d.uf) IS DISTINCT FROM (EXCLUDED.nome, EXCLUDED.cidade, EXCLUDED.uf);

    INSERT INTO dw_estrela.dim_funcionario AS d (funcionario_uuid, nome, cargo)
    SELECT funcionario_uuid, nome, cargo
      FROM stg.funcionario
    ON CONFLICT (funcionario_uuid) DO UPDATE
       SET nome = EXCLUDED.nome, cargo = EXCLUDED.cargo
     WHERE (d.nome, d.cargo) IS DISTINCT FROM (EXCLUDED.nome, EXCLUDED.cargo);
END;
$$ LANGUAGE plpgsql;;

-- Cliente: SCD tipo 2 na cidade/UF, tipo 1 no nome.
--   - nome mudou: corrige em todas as versoes (e correcao de cadastro, nao fato historico);
--   - cidade/UF mudou: fecha a versao atual (data_fim = hoje, atual = false) e abre uma nova a partir de hoje;
--   - cliente novo: primeira versao valendo desde sempre (1900-01-01), para cobrir as vendas antigas.
-- As vendas ja carregadas continuam apontando para a versao da epoca; as novas pegam a versao do dia da venda.
CREATE OR REPLACE PROCEDURE etl.carregar_dim_cliente(INOUT novas_versoes integer DEFAULT 0) AS $$
DECLARE
    v_hoje date := etl.hoje();
BEGIN
    UPDATE dw_estrela.dim_cliente d
       SET nome = s.nome
      FROM stg.cliente s
     WHERE d.cliente_uuid = s.cliente_uuid
       AND d.nome IS DISTINCT FROM s.nome;

    WITH fechadas AS (
        UPDATE dw_estrela.dim_cliente d
           SET data_fim = v_hoje,
               atual    = false
          FROM stg.cliente s
         WHERE d.cliente_uuid = s.cliente_uuid
           AND d.atual
           AND (d.cidade, d.uf) IS DISTINCT FROM (s.cidade, s.uf)
        RETURNING d.cliente_uuid
    )
    SELECT count(*) INTO novas_versoes FROM fechadas;

    INSERT INTO dw_estrela.dim_cliente (cliente_uuid, nome, cidade, uf, estado, regiao, data_inicio, data_fim, atual)
    SELECT s.cliente_uuid, s.nome, s.cidade, s.uf, u.nome, u.regiao,
           CASE WHEN EXISTS (SELECT 1 FROM dw_estrela.dim_cliente d WHERE d.cliente_uuid = s.cliente_uuid)
                THEN v_hoje                -- ja tinha versao: a nova vale a partir de hoje
                ELSE DATE '1900-01-01'     -- primeira versao
           END,
           DATE '9999-12-31',
           true
      FROM stg.cliente s
      JOIN etl.uf u ON u.uf = s.uf
     WHERE NOT EXISTS (SELECT 1
                         FROM dw_estrela.dim_cliente d
                        WHERE d.cliente_uuid = s.cliente_uuid
                          AND d.atual);
END;
$$ LANGUAGE plpgsql;;

-- 4. Fato do modelo estrela -----------------------------------------------------------------------------------
-- Troca cada chave natural (uuid) pela chave substituta da dimensao. O cliente e a versao valida no dia da venda;
-- venda sem cliente vai para o membro "Não identificado" (sk 0), sem nulo no fato.
CREATE OR REPLACE PROCEDURE etl.carregar_fato_estrela(INOUT inseridos integer DEFAULT 0) AS $$
DECLARE
    v_extraidos integer;
BEGIN
    INSERT INTO dw_estrela.fato_venda (sk_tempo, sk_produto, sk_cliente, sk_loja, sk_funcionario, venda_uuid,
                                       id_item_origem, quantidade, preco_unitario, valor_bruto, desconto,
                                       valor_liquido)
    SELECT to_char(v.data_venda, 'YYYYMMDD')::integer,
           dp.sk_produto,
           coalesce(dc.sk_cliente, 0),
           dl.sk_loja,
           df.sk_funcionario,
           v.venda_uuid,
           v.id_item_origem,
           v.quantidade,
           v.preco_unitario,
           v.valor_bruto,
           v.desconto,
           v.valor_liquido
      FROM stg.venda_item v
      JOIN dw_estrela.dim_produto dp     ON dp.produto_uuid = v.produto_uuid
      JOIN dw_estrela.dim_loja dl        ON dl.loja_uuid = v.loja_uuid
      JOIN dw_estrela.dim_funcionario df ON df.funcionario_uuid = v.funcionario_uuid
      LEFT JOIN dw_estrela.dim_cliente dc
             ON dc.cliente_uuid = v.cliente_uuid
            AND v.data_venda >= dc.data_inicio
            AND v.data_venda <  dc.data_fim;
    GET DIAGNOSTICS inseridos = ROW_COUNT;

    -- Conferencia: todo item extraido tem de virar uma linha do fato. Se alguma dimensao faltou, a junção acima
    -- descartaria o item em silencio; aqui a carga inteira e desfeita.
    SELECT count(*) INTO v_extraidos FROM stg.venda_item;
    IF inseridos <> v_extraidos THEN
        RAISE EXCEPTION 'ETL: % itens extraidos, mas % linhas inseridas no fato (dimensao faltando?)',
            v_extraidos, inseridos;
    END IF;
END;
$$ LANGUAGE plpgsql;;

-- 5. Floco de neve --------------------------------------------------------------------------------------------
-- Parte das dimensoes da estrela, ja tratadas, e as normaliza. Produto, loja, cliente, funcionario e tempo mantem as
-- mesmas chaves substitutas, e o fato e copiado: os dois modelos guardam exatamente os mesmos dados.
CREATE OR REPLACE PROCEDURE etl.carregar_floco() AS $$
BEGIN
    INSERT INTO dw_floco.dim_estado AS d (uf, nome, regiao)
    SELECT uf, nome, regiao FROM etl.uf
    ON CONFLICT (uf) DO UPDATE
       SET nome = EXCLUDED.nome, regiao = EXCLUDED.regiao
     WHERE (d.nome, d.regiao) IS DISTINCT FROM (EXCLUDED.nome, EXCLUDED.regiao);

    INSERT INTO dw_floco.dim_cidade (nome, sk_estado)
    SELECT DISTINCT x.cidade, e.sk_estado
      FROM (SELECT cidade, uf FROM dw_estrela.dim_loja
            UNION
            SELECT cidade, uf FROM dw_estrela.dim_cliente) x
      JOIN dw_floco.dim_estado e ON e.uf = x.uf
    ON CONFLICT (nome, sk_estado) DO NOTHING;

    INSERT INTO dw_floco.dim_categoria AS d (categoria_uuid, nome)
    SELECT DISTINCT categoria_uuid, categoria FROM stg.produto
    ON CONFLICT (categoria_uuid) DO UPDATE
       SET nome = EXCLUDED.nome
     WHERE d.nome IS DISTINCT FROM EXCLUDED.nome;

    INSERT INTO dw_floco.dim_produto AS d (sk_produto, produto_uuid, codigo_barras, nome, unidade_medida, ativo,
                                           sk_categoria)
    SELECT p.sk_produto, p.produto_uuid, p.codigo_barras, p.nome, p.unidade_medida, p.ativo, c.sk_categoria
      FROM dw_estrela.dim_produto p
      JOIN stg.produto s           ON s.produto_uuid = p.produto_uuid
      JOIN dw_floco.dim_categoria c ON c.categoria_uuid = s.categoria_uuid
    ON CONFLICT (sk_produto) DO UPDATE
       SET codigo_barras = EXCLUDED.codigo_barras, nome = EXCLUDED.nome, unidade_medida = EXCLUDED.unidade_medida,
           ativo = EXCLUDED.ativo, sk_categoria = EXCLUDED.sk_categoria
     WHERE (d.codigo_barras, d.nome, d.unidade_medida, d.ativo, d.sk_categoria)
           IS DISTINCT FROM (EXCLUDED.codigo_barras, EXCLUDED.nome, EXCLUDED.unidade_medida, EXCLUDED.ativo,
                             EXCLUDED.sk_categoria);

    INSERT INTO dw_floco.dim_loja AS d (sk_loja, loja_uuid, nome, sk_cidade)
    SELECT l.sk_loja, l.loja_uuid, l.nome, ci.sk_cidade
      FROM dw_estrela.dim_loja l
      JOIN dw_floco.dim_estado e ON e.uf = l.uf
      JOIN dw_floco.dim_cidade ci ON ci.nome = l.cidade AND ci.sk_estado = e.sk_estado
    ON CONFLICT (sk_loja) DO UPDATE
       SET nome = EXCLUDED.nome, sk_cidade = EXCLUDED.sk_cidade
     WHERE (d.nome, d.sk_cidade) IS DISTINCT FROM (EXCLUDED.nome, EXCLUDED.sk_cidade);

    INSERT INTO dw_floco.dim_cliente AS d (sk_cliente, cliente_uuid, nome, sk_cidade, data_inicio, data_fim, atual)
    SELECT c.sk_cliente, c.cliente_uuid, c.nome, ci.sk_cidade, c.data_inicio, c.data_fim, c.atual
      FROM dw_estrela.dim_cliente c
      JOIN dw_floco.dim_estado e ON e.uf = c.uf
      JOIN dw_floco.dim_cidade ci ON ci.nome = c.cidade AND ci.sk_estado = e.sk_estado
    ON CONFLICT (sk_cliente) DO UPDATE
       SET nome = EXCLUDED.nome, sk_cidade = EXCLUDED.sk_cidade, data_inicio = EXCLUDED.data_inicio,
           data_fim = EXCLUDED.data_fim, atual = EXCLUDED.atual
     WHERE (d.nome, d.sk_cidade, d.data_inicio, d.data_fim, d.atual)
           IS DISTINCT FROM (EXCLUDED.nome, EXCLUDED.sk_cidade, EXCLUDED.data_inicio, EXCLUDED.data_fim,
                             EXCLUDED.atual);

    INSERT INTO dw_floco.dim_funcionario AS d (sk_funcionario, funcionario_uuid, nome, cargo)
    SELECT sk_funcionario, funcionario_uuid, nome, cargo FROM dw_estrela.dim_funcionario
    ON CONFLICT (sk_funcionario) DO UPDATE
       SET nome = EXCLUDED.nome, cargo = EXCLUDED.cargo
     WHERE (d.nome, d.cargo) IS DISTINCT FROM (EXCLUDED.nome, EXCLUDED.cargo);

    INSERT INTO dw_floco.dim_tempo
    SELECT * FROM dw_estrela.dim_tempo
    ON CONFLICT (sk_tempo) DO NOTHING;

    INSERT INTO dw_floco.fato_venda
    SELECT f.*
      FROM dw_estrela.fato_venda f
     WHERE NOT EXISTS (SELECT 1
                         FROM dw_floco.fato_venda g
                        WHERE g.venda_uuid = f.venda_uuid
                          AND g.id_item_origem = f.id_item_origem);
END;
$$ LANGUAGE plpgsql;;

-- Orquestracao ------------------------------------------------------------------------------------------------

CREATE OR REPLACE PROCEDURE etl.executar(completa boolean DEFAULT false) AS $$
DECLARE
    v_id       bigint;
    v_itens    integer;
    v_fatos    integer := 0;
    v_versoes  integer := 0;
BEGIN
    -- Uma execucao por vez: uma segunda chamada espera a primeira terminar.
    PERFORM pg_advisory_xact_lock(hashtext('etl.executar'));

    INSERT INTO etl.execucao (tipo) VALUES (CASE WHEN completa THEN 'COMPLETA' ELSE 'INCREMENTAL' END)
    RETURNING id INTO v_id;

    IF completa THEN
        TRUNCATE dw_floco.fato_venda, dw_floco.dim_cliente, dw_floco.dim_loja, dw_floco.dim_produto,
                 dw_floco.dim_funcionario, dw_floco.dim_tempo, dw_floco.dim_categoria, dw_floco.dim_cidade,
                 dw_floco.dim_estado,
                 dw_estrela.fato_venda, dw_estrela.dim_cliente, dw_estrela.dim_loja, dw_estrela.dim_produto,
                 dw_estrela.dim_funcionario, dw_estrela.dim_tempo
        RESTART IDENTITY;
    END IF;

    CALL etl.garantir_cliente_nao_identificado();
    CALL etl.extrair();
    SELECT count(*) INTO v_itens FROM stg.venda_item;

    CALL etl.carregar_dim_tempo();
    CALL etl.carregar_dimensoes_tipo1();
    CALL etl.carregar_dim_cliente(v_versoes);
    CALL etl.carregar_fato_estrela(v_fatos);
    CALL etl.carregar_floco();

    -- Estatisticas atualizadas para o planejador (as consultas OLAP e o EXPLAIN ANALYZE dependem delas).
    ANALYZE dw_estrela.fato_venda;
    ANALYZE dw_floco.fato_venda;

    UPDATE etl.execucao
       SET fim = clock_timestamp(),
           itens_extraidos = v_itens,
           fatos_inseridos = v_fatos,
           clientes_novas_versoes = v_versoes
     WHERE id = v_id;
END;
$$ LANGUAGE plpgsql;;
