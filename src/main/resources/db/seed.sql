-- Seed de dados semi-realistas para o banco "grocery" (PostgreSQL).
--
-- Volume gerado:
--    20 categorias | 200 produtos | 100 fornecedores | 3 lojas | 16 funcionarios
--   2.000 vendas (1.997 historicas + 3 em andamento) divididas entre as lojas (cerca de 45% / 33% / 22%),
--   com itens, pagamentos e baixas de estoque, espalhadas por 26 meses (780 dias) ate ontem; compras de
--   reposicao, perdas (LOSS) e historico de precos coerentes entre si. Leva alguns segundos.
--
-- Como executar:
--   a) ./mvnw -Pseed   (sobe o Spring sem web, cria tabelas/triggers se faltarem, popula e encerra)
--   b) ./mvnw spring-boot:run "-Dspring-boot.run.profiles=seed"   (idem, mas deixa a aplicacao no ar)
--   c) psql -U postgres -d grocery -f src/main/resources/db/seed.sql
--      (aqui as tabelas e os triggers precisam existir: suba a aplicacao pelo menos uma vez)
--
-- Os comandos sao separados por duas barras de ponto e virgula, igual a inventory_trigger.sql,
-- para funcionar tanto no psql quanto no spring.sql.init (ver application-seed.properties).
--
-- Garantias:
--   * Idempotente: se ja existir loja, fornecedor, funcionario, preco, venda ou movimentacao, nada e
--     inserido (so um aviso). Categorias e produtos criados pelo Config.java sao reaproveitados
--     (por nome / codigo de barras), entao o total fica em 20 / 200 com ou sem eles.
--   * Reproduzivel: usa setseed() e nenhum sorteio depende do plano de execucao, entao a mesma
--     versao do PostgreSQL gera sempre o mesmo conjunto de dados (as datas acompanham o dia em que
--     o seed roda, ja que a janela termina em "ontem"). Os UUIDs tambem sao deterministicos (md5).
--   * O estoque NUNCA e escrito direto: so inserimos inventory_movement em ordem cronologica
--     e o trigger mantem inventory em dia (se o seed violasse o CHECK de saldo >= 0, falharia).
--   * Tudo-ou-nada: o bloco principal roda numa transacao so; se algo falhar, nada fica pela metade.
--
-- Premissas de negocio (o codigo ainda nao tem regra de venda, entao ficaram explicitas aqui):
--   * Multi-loja: catalogo (categoria, produto, preco) e fornecedores sao da rede. Cada loja tem os seus
--     funcionarios (o ADMIN e da rede e fica sem loja), as suas vendas e o seu estoque; reposicao, perdas e
--     vencimento de lotes sao simulados por (loja, produto). O vendedor de uma venda e caixa da loja dela.
--   * Estoque so baixa em venda COMPLETED (uma movimentacao SALE por item). Vendas CANCELED e OPEN
--     tem itens, mas nao movimentam estoque (nao existe movimentacao de estorno, e a movimentacao
--     e imutavel).
--   * price e um historico: a linha vigente tem deleted_at nulo; a substituida recebe deleted_at
--     igual ao created_at da proxima. sale_items.unit_price_in_cents e o preco vigente na data.
--   * Documentos (CPF/CNPJ) e codigos de barras (EAN-13) tem digito verificador valido; CPF/CNPJ
--     guardados so com digitos. Itens pesaveis usam EAN-13 interno (prefixo 2).
--   * Para recomecar do zero:  TRUNCATE sale, payment, sale_items, inventory_movement, inventory,
--     price, product, category, supplier, store, staff, customer, person RESTART IDENTITY CASCADE;

SET client_encoding TO 'UTF8';;

-- =============================================================================================
-- Funcoes auxiliares: ficam no schema temporario da sessao e somem quando a conexao fecha.
-- =============================================================================================

-- Digitos verificadores do CPF (base de 9 digitos) -> CPF de 11 digitos.
CREATE OR REPLACE FUNCTION pg_temp.fn_cpf(base bigint) RETURNS text AS $$
DECLARE
    b text := lpad(base::text, 9, '0');
    s int;
    d1 int;
    d2 int;
BEGIN
    s := 0;
    FOR i IN 1..9 LOOP s := s + substr(b, i, 1)::int * (11 - i); END LOOP;
    d1 := (s * 10) % 11;
    IF d1 = 10 THEN d1 := 0; END IF;
    s := 0;
    FOR i IN 1..9 LOOP s := s + substr(b, i, 1)::int * (12 - i); END LOOP;
    s := s + d1 * 2;
    d2 := (s * 10) % 11;
    IF d2 = 10 THEN d2 := 0; END IF;
    RETURN b || d1::text || d2::text;
END;
$$ LANGUAGE plpgsql IMMUTABLE;;

-- Digitos verificadores do CNPJ (base de 8 digitos, filial 0001) -> CNPJ de 14 digitos.
CREATE OR REPLACE FUNCTION pg_temp.fn_cnpj(base bigint) RETURNS text AS $$
DECLARE
    b  text := lpad(base::text, 8, '0') || '0001';
    w1 int[] := ARRAY[5,4,3,2,9,8,7,6,5,4,3,2];
    w2 int[] := ARRAY[6,5,4,3,2,9,8,7,6,5,4,3,2];
    s  int;
    d1 int;
    d2 int;
BEGIN
    s := 0;
    FOR i IN 1..12 LOOP s := s + substr(b, i, 1)::int * w1[i]; END LOOP;
    d1 := s % 11;
    d1 := CASE WHEN d1 < 2 THEN 0 ELSE 11 - d1 END;
    s := 0;
    FOR i IN 1..12 LOOP s := s + substr(b, i, 1)::int * w2[i]; END LOOP;
    s := s + d1 * w2[13];
    d2 := s % 11;
    d2 := CASE WHEN d2 < 2 THEN 0 ELSE 11 - d2 END;
    RETURN b || d1::text || d2::text;
END;
$$ LANGUAGE plpgsql IMMUTABLE;;

-- Completa um prefixo de 12 digitos com o digito verificador do EAN-13.
CREATE OR REPLACE FUNCTION pg_temp.fn_ean13(prefix12 text) RETURNS text AS $$
DECLARE
    s int := 0;
BEGIN
    FOR i IN 1..12 LOOP
        s := s + substr(prefix12, i, 1)::int * CASE WHEN i % 2 = 1 THEN 1 ELSE 3 END;
    END LOOP;
    RETURN prefix12 || ((10 - s % 10) % 10)::text;
END;
$$ LANGUAGE plpgsql IMMUTABLE;;

-- "Laticinios Vale Verde Ltda" -> "laticiniosvaleverde" (para montar e-mails).
CREATE OR REPLACE FUNCTION pg_temp.fn_slug(nome text) RETURNS text AS $$
    -- translate antes do lower(): em bancos com locale C o lower() nao mexe em letras acentuadas.
    SELECT regexp_replace(
               regexp_replace(
                   lower(translate(nome,
                                   'áàâãäéèêëíìîïóòôõöúùûüçÁÀÂÃÄÉÈÊËÍÌÎÏÓÒÔÕÖÚÙÛÜÇ',
                                   'aaaaaeeeeiiiiooooouuuucAAAAAEEEEIIIIOOOOOUUUUC')),
                   '\s+(ltda|s\.a\.|s/a|me|epp|eireli)\s*$', ''),
               '[^a-z0-9]', '', 'g');
$$ LANGUAGE sql IMMUTABLE;;

-- Numero aleatorio com distribuicao normal padrao (Box-Muller), usando o random() semeado.
CREATE OR REPLACE FUNCTION pg_temp.fn_normal() RETURNS double precision AS $$
    SELECT sqrt(-2 * ln(1 - random())) * cos(2 * pi() * random());
$$ LANGUAGE sql VOLATILE;;

-- Arredonda para um preco de varejo "com cara de preco": termina em ,x9 / ,x0 (em centavos).
CREATE OR REPLACE FUNCTION pg_temp.fn_retail_price(cents numeric) RETURNS bigint AS $$
    SELECT greatest(
        CASE WHEN cents < 100 THEN round(cents / 5) * 5
             ELSE round(cents / 10) * 10 - CASE WHEN random() < 0.65 THEN 1 ELSE 0 END
        END, 49)::bigint;
$$ LANGUAGE sql VOLATILE;;

-- =============================================================================================
-- Bloco principal
-- =============================================================================================

DO $seed$
DECLARE
    -- ---- Parametros do volume (mexa aqui para gerar mais/menos dados) -----------------------
    c_sales        constant int  := 2000;               -- total de vendas (inclui as c_open em andamento)
    c_open         constant int  := 3;                  -- vendas OPEN: carrinhos abertos agora
    c_seed         constant double precision := 0.42;   -- setseed() exige valor entre -1 e 1
    c_tz           constant text := 'America/Bahia';
    c_months       constant int  := 26;                 -- janela das vendas: 26 x 30 dias (~2 anos e 2 meses)
    -- ----------------------------------------------------------------------------------------
    v_end          date := current_date - 1;             -- ultimo dia com vendas historicas
    v_start        date := (current_date - 1) - (c_months * 30);
    v_open         date;                                 -- dia da carga inicial de estoque
    v_day          date;
    v_ts           timestamptz;
    v_total_w      double precision;
    v_n            int;
    v_i            int;
    v_u            double precision;
    r              record;
    slot           record;                               -- (nao chamar de "s": colide com alias das consultas)
    shop           record;                               -- loja da vez nos lacos por loja
    v_store_n      int;
    v_store_id     bigint;
    v_changes      int;
    v_cur          numeric;
    v_from         timestamptz;
    v_to           timestamptz;
    v_factor       numeric;
    v_dates        date[];
    v_k            int;
    v_sale_id      bigint;
    v_status       text;
    v_total        bigint;
    v_pid          bigint;
    v_qty          numeric(12,3);
    v_stock        numeric(12,3);
    v_price        bigint;
    v_pay_ts       timestamptz;
    v_method       text;
    v_method2      text;
    v_split        double precision;
    v_part         bigint;
    v_client       text;
    v_seller       bigint;
    v_sellers      bigint[];
    v_sale_count   int := 0;
    v_buy_count    int := 0;
    v_loss_count   int := 0;
    v_order_qty    numeric(12,3);
    v_supplier     bigint;
    v_cost         bigint;
    v_expires      date;
    v_nitems       int;
BEGIN
    -- ---- Guarda de idempotencia -----------------------------------------------------------
    IF EXISTS (SELECT 1 FROM supplier)
       OR EXISTS (SELECT 1 FROM store)
       OR EXISTS (SELECT 1 FROM staff)
       OR EXISTS (SELECT 1 FROM price)
       OR EXISTS (SELECT 1 FROM sale)
       OR EXISTS (SELECT 1 FROM inventory_movement) THEN
        RAISE NOTICE 'seed ignorado: ja existem dados de loja/fornecedor/funcionario/preco/venda/estoque (veja o TRUNCATE no topo do arquivo).';
        RETURN;
    END IF;

    PERFORM setseed(c_seed);
    v_open := v_start - 7;

    -- ==========================================================================================
    -- 1) Categorias (20). As 15 primeiras sao as mesmas do Config.java; as outras 5 sao novas.
    -- ==========================================================================================
    CREATE TEMP TABLE tmp_cat (
        idx          int PRIMARY KEY,
        name         text NOT NULL,
        id           bigint,
        -- parametros de reposicao / perda / margem (usados na simulacao de estoque)
        shelf_min    int,        -- validade do lote, em dias (NULL = sem validade)
        shelf_max    int,
        cost_ratio   numeric,    -- custo medio / preco de venda
        cover_days   int,        -- dias de cobertura que a reposicao tenta atingir
        loss_prob    numeric,    -- chance diaria de uma perda (quebra/vencimento) por produto
        qty_k        numeric     -- "tamanho" tipico da compra do cliente em itens UNIT
    ) ON COMMIT DROP;

    INSERT INTO tmp_cat (idx, name, shelf_min, shelf_max, cost_ratio, cover_days, loss_prob, qty_k) VALUES
        ( 1, 'Produtos Frescos e Hortifrúti',          5,   12, 0.62,  5, 0.0040, 0.6),
        ( 2, 'Padaria e Confeitaria',                  4,    7, 0.55,  3, 0.0030, 0.8),
        ( 3, 'Laticínios e Ovos',                     10,   35, 0.74,  7, 0.0015, 0.7),
        ( 4, 'Carnes, Aves e Peixes',                  5,   12, 0.72,  5, 0.0020, 0.5),
        ( 5, 'Mercearia e Grãos',                    180,  540, 0.78, 21, 0.0004, 0.5),
        ( 6, 'Cereais e Matinais',                   180,  360, 0.74, 21, 0.0004, 0.4),
        ( 7, 'Alimentos Processados e Enlatados',    365,  900, 0.75, 28, 0.0003, 0.6),
        ( 8, 'Congelados',                           120,  300, 0.72, 14, 0.0010, 0.5),
        ( 9, 'Bebidas',                              120,  360, 0.76, 14, 0.0008, 1.1),
        (10, 'Doces e Salgadinhos',                   90,  240, 0.70, 21, 0.0010, 0.7),
        (11, 'Materiais de Limpeza',                NULL, NULL, 0.68, 30, 0.0003, 0.4),
        (12, 'Higiene e Cuidados Pessoais',          540,  900, 0.66, 30, 0.0003, 0.5),
        (13, 'Artigos de Descarte e Papelaria',     NULL, NULL, 0.64, 30, 0.0002, 0.5),
        (14, 'Cuidados com o Bebê',                  365,  720, 0.72, 30, 0.0004, 0.4),
        (15, 'Produtos para Pet Shop',               180,  540, 0.70, 30, 0.0004, 0.4),
        (16, 'Frios e Embutidos',                      8,   30, 0.72,  7, 0.0020, 0.5),
        (17, 'Molhos, Temperos e Condimentos',       365,  720, 0.70, 28, 0.0003, 0.4),
        (18, 'Bebidas Alcoólicas',                   365,  900, 0.74, 21, 0.0004, 0.9),
        (19, 'Utilidades Domésticas',               NULL, NULL, 0.62, 45, 0.0002, 0.3),
        (20, 'Massas e Biscoitos',                   150,  300, 0.74, 21, 0.0006, 0.6);

    -- Reaproveita categorias que o Config.java ja criou (mesmo nome) e cria as que faltam.
    INSERT INTO category (uuid, name, created_at)
    SELECT md5('category:' || t.name)::uuid, t.name,
           (v_open - 60 - (t.idx * 9))::timestamp AT TIME ZONE c_tz
      FROM tmp_cat t
     WHERE NOT EXISTS (SELECT 1 FROM category c WHERE c.name = t.name);

    UPDATE tmp_cat t SET id = c.id FROM category c WHERE c.name = t.name;

    -- Linhas criadas pelo Config.java na hora (created_at = agora) ficariam depois das vendas.
    UPDATE category SET created_at = (v_open - 60 - (id % 30)::int)::timestamp AT TIME ZONE c_tz
     WHERE created_at > v_open::timestamp AT TIME ZONE c_tz
       AND id IN (SELECT id FROM tmp_cat);

    -- ==========================================================================================
    -- 2) Produtos (200)
    --    idx_cat | nome | descricao | unidade | preco ATUAL em centavos | popularidade (1-10)
    --    Itens KG/LITER/GRAM/MILLILITER sao vendidos a granel/pesados: preco por unidade de medida.
    -- ==========================================================================================
    CREATE TEMP TABLE tmp_prod (
        n            int PRIMARY KEY,         -- ordem de insercao (1..200)
        cat_idx      int NOT NULL,
        name         text NOT NULL,
        descr        text,
        unit         text NOT NULL,
        price_now    bigint NOT NULL,
        pop          int NOT NULL,
        id           bigint,
        barcode      text,
        -- derivados na etapa 3
        weight       double precision,        -- peso de popularidade (amostragem de itens)
        daily_units  double precision,        -- demanda diaria esperada, na unidade de medida
        pack         numeric,                 -- lote de compra da rede (a loja compra lotes menores: tmp_store_prod)
        no_restock_after date,                -- ruptura proposital nos ultimos dias (para consultas de estoque baixo)
        qty_k        numeric,                 -- copiados de tmp_cat: o sorteio roda numa tabela so, sem JOIN,
        loss_prob    numeric,                 -- para o resultado nao depender do plano de execucao
        sup_main     bigint,
        sup_alt      bigint,
        review_dow   int,                     -- dia da semana (1=seg..6=sab) da entrega do fornecedor
        review_dow2  int,                     -- 2a entrega semanal (so perecíveis)
        perishable   boolean                  -- (lote da loja e validade do lote ficam em tmp_store_prod)
    ) ON COMMIT DROP;

    INSERT INTO tmp_prod (n, cat_idx, name, descr, unit, price_now, pop)
    SELECT row_number() OVER (), * FROM (VALUES
        -- 1. Produtos Frescos e Hortifrúti (22)
        ( 1, 'Banana Prata',              'Banana prata fresca, vendida por kg',                'KG',    599, 9),
        ( 1, 'Banana da Terra',           'Banana da terra para cozinhar, vendida por kg',      'KG',    749, 3),
        ( 1, 'Maçã Gala',                 'Maçã gala nacional, vendida por kg',                 'KG',    999, 6),
        ( 1, 'Laranja Pera',              'Laranja pera para suco, vendida por kg',             'KG',    449, 6),
        ( 1, 'Mamão Formosa',             'Mamão formosa maduro, vendido por kg',               'KG',    579, 5),
        ( 1, 'Melancia',                  'Melancia inteira ou em pedaços, vendida por kg',     'KG',    349, 4),
        ( 1, 'Abacaxi Pérola',            'Abacaxi pérola doce, vendido por kg',                'KG',    599, 3),
        ( 1, 'Limão Tahiti',              'Limão tahiti, vendido por kg',                       'KG',    649, 6),
        ( 1, 'Uva Verde sem Semente',     'Uva verde sem semente, vendida por kg',              'KG',   1790, 3),
        ( 1, 'Manga Tommy',               'Manga tommy madura, vendida por kg',                 'KG',    799, 4),
        ( 1, 'Tomate Salada',             'Tomate salada, vendido por kg',                      'KG',    699, 9),
        ( 1, 'Cebola Amarela',            'Cebola amarela, vendida por kg',                     'KG',    549, 9),
        ( 1, 'Batata Inglesa',            'Batata inglesa lavada, vendida por kg',              'KG',    599, 8),
        ( 1, 'Cenoura',                   'Cenoura fresca, vendida por kg',                     'KG',    499, 6),
        ( 1, 'Alface Crespa',             'Alface crespa, unidade',                             'UNIT', 349, 5),
        ( 1, 'Cheiro-Verde',              'Maço de coentro e cebolinha',                        'UNIT', 249, 6),
        ( 1, 'Abóbora Jacaré',            'Abóbora jacaré, vendida por kg',                     'KG',    399, 2),
        ( 1, 'Pepino',                    'Pepino japonês, vendido por kg',                     'KG',    599, 3),
        ( 1, 'Pimentão Verde',            'Pimentão verde, vendido por kg',                     'KG',    899, 3),
        ( 1, 'Aipim (Mandioca) Descascado','Aipim descascado e embalado, vendido por kg',       'KG',    749, 4),
        ( 1, 'Alho Nacional',             'Alho nacional, vendido por kg',                      'KG',   2990, 4),
        ( 1, 'Inhame',                    'Inhame fresco, vendido por kg',                      'KG',    899, 2),
        -- 2. Padaria e Confeitaria (8)
        ( 2, 'Pão Francês',               'Pão francês fresquinho, vendido por kg',             'KG',   1590, 10),
        ( 2, 'Pão de Forma Tradicional',  'Pão de forma 500g',                                  'UNIT', 799, 8),
        ( 2, 'Bolo de Aipim',             'Bolo de aipim caseiro, fatia grande',                'UNIT', 649, 3),
        ( 2, 'Bolo de Cenoura com Chocolate','Bolo de cenoura com cobertura de chocolate',      'UNIT', 1990, 3),
        ( 2, 'Pão de Queijo Congelado 400g','Pão de queijo tradicional, pacote 400g',           'UNIT', 1190, 5),
        ( 2, 'Sonho de Creme',            'Sonho recheado com creme de confeiteiro',            'UNIT', 449, 3),
        ( 2, 'Torrada Integral 140g',     'Torrada integral, pacote 140g',                      'UNIT', 579, 3),
        ( 2, 'Broa de Milho',             'Broa de milho artesanal, unidade',                   'UNIT', 299, 2),
        -- 3. Laticínios e Ovos (12)
        ( 3, 'Leite Integral 1L',         'Leite integral UHT caixa 1 litro',                   'UNIT', 579, 10),
        ( 3, 'Leite Desnatado 1L',        'Leite desnatado UHT caixa 1 litro',                  'UNIT', 599, 4),
        ( 3, 'Iogurte Natural 170g',      'Iogurte natural integral, pote 170g',                'UNIT', 249, 5),
        ( 3, 'Iogurte de Morango 900g',   'Iogurte sabor morango, garrafa 900g',                'UNIT', 1090, 5),
        ( 3, 'Manteiga com Sal 200g',     'Manteiga com sal, pote 200g',                        'UNIT', 1190, 6),
        ( 3, 'Margarina Cremosa 500g',    'Margarina cremosa com sal, pote 500g',               'UNIT', 859, 7),
        ( 3, 'Requeijão Cremoso 200g',    'Requeijão cremoso tradicional, copo 200g',           'UNIT', 799, 6),
        ( 3, 'Queijo Coalho 400g',        'Queijo coalho para churrasco, espeto 400g',          'UNIT', 2490, 5),
        ( 3, 'Creme de Leite 200g',       'Creme de leite UHT, caixa 200g',                     'UNIT', 329, 6),
        ( 3, 'Leite Condensado 395g',     'Leite condensado, lata 395g',                        'UNIT', 689, 6),
        ( 3, 'Ovos Brancos Dúzia',        'Ovos brancos grandes, bandeja com 12',               'UNIT', 1090, 9),
        ( 3, 'Ovos Caipira 20 un',        'Ovos caipira, bandeja com 20',                       'UNIT', 2790, 3),
        -- 4. Carnes, Aves e Peixes (14)
        ( 4, 'Contra-Filé Bovino',        'Contra-filé bovino em bife, vendido por kg',         'KG',   4990, 5),
        ( 4, 'Alcatra em Bifes',          'Alcatra bovina em bifes, vendida por kg',            'KG',   4590, 4),
        ( 4, 'Carne Moída de Primeira',   'Carne moída bovina de primeira, vendida por kg',     'KG',   3790, 8),
        ( 4, 'Acém em Cubos',             'Acém bovino em cubos, vendido por kg',               'KG',   3290, 5),
        ( 4, 'Costela Bovina',            'Costela bovina com osso, vendida por kg',            'KG',   2890, 4),
        ( 4, 'Carne de Sol',              'Carne de sol dessalgada, vendida por kg',            'KG',   4290, 4),
        ( 4, 'Peito de Frango',           'Peito de frango sem osso, vendido por kg',           'KG',   1890, 9),
        ( 4, 'Coxa e Sobrecoxa de Frango','Coxa e sobrecoxa de frango, vendidas por kg',        'KG',   1290, 9),
        ( 4, 'Frango Inteiro Resfriado',  'Frango inteiro resfriado, vendido por kg',           'KG',   1190, 6),
        ( 4, 'Linguiça Toscana',          'Linguiça toscana suína, vendida por kg',             'KG',   2190, 7),
        ( 4, 'Bisteca Suína',             'Bisteca suína, vendida por kg',                      'KG',   2090, 4),
        ( 4, 'Tilápia em Filé',           'Filé de tilápia fresco, vendido por kg',             'KG',   4290, 3),
        ( 4, 'Camarão Cinza Médio',       'Camarão cinza médio limpo, vendido por kg',          'KG',   7990, 2),
        ( 4, 'Fígado Bovino',             'Fígado bovino fatiado, vendido por kg',              'KG',   1690, 2),
        -- 5. Mercearia e Grãos (14)
        ( 5, 'Arroz Branco Tipo 1 5kg',   'Arroz branco agulhinha tipo 1, pacote 5kg',          'UNIT', 3190, 10),
        ( 5, 'Arroz Parboilizado 1kg',    'Arroz parboilizado, pacote 1kg',                     'UNIT',  799, 5),
        ( 5, 'Feijão Carioca 1kg',        'Feijão carioca tipo 1, pacote 1kg',                  'UNIT',  949, 10),
        ( 5, 'Feijão Preto 1kg',          'Feijão preto tipo 1, pacote 1kg',                    'UNIT', 1090, 4),
        ( 5, 'Açúcar Refinado 1kg',       'Açúcar refinado, pacote 1kg',                        'UNIT',  449, 9),
        ( 5, 'Açúcar Cristal 5kg',        'Açúcar cristal, pacote 5kg',                         'UNIT', 2190, 5),
        ( 5, 'Farinha de Mandioca 1kg',   'Farinha de mandioca torrada, pacote 1kg',            'UNIT',  799, 7),
        ( 5, 'Farinha de Trigo 1kg',      'Farinha de trigo tipo 1, pacote 1kg',                'UNIT',  499, 6),
        ( 5, 'Fubá Mimoso 500g',          'Fubá mimoso, pacote 500g',                           'UNIT',  349, 3),
        ( 5, 'Sal Refinado 1kg',          'Sal refinado iodado, pacote 1kg',                    'UNIT',  289, 7),
        ( 5, 'Óleo de Soja 900ml',        'Óleo de soja refinado, garrafa 900ml',               'UNIT',  829, 9),
        ( 5, 'Azeite Extra Virgem 500ml', 'Azeite de oliva extra virgem, vidro 500ml',          'UNIT', 4290, 4),
        ( 5, 'Café Torrado e Moído 500g', 'Café torrado e moído tradicional, pacote 500g',      'UNIT', 2490, 10),
        ( 5, 'Milho de Pipoca 500g',      'Milho de pipoca premium, pacote 500g',               'UNIT',  699, 3),
        -- 6. Cereais e Matinais (8)
        ( 6, 'Aveia em Flocos 200g',      'Aveia em flocos finos, caixa 200g',                  'UNIT',  479, 4),
        ( 6, 'Granola Tradicional 800g',  'Granola tradicional, pacote 800g',                   'UNIT', 1990, 3),
        ( 6, 'Cereal Matinal de Milho 300g','Flocos de milho açucarados, caixa 300g',           'UNIT',  1190, 4),
        ( 6, 'Achocolatado em Pó 400g',   'Achocolatado em pó, pote 400g',                      'UNIT',  899, 8),
        ( 6, 'Cuscuz de Milho Flocão 500g','Flocão de milho para cuscuz, pacote 500g',          'UNIT',  229, 9),
        ( 6, 'Tapioca Goma Hidratada 1kg','Goma de tapioca hidratada, pacote 1kg',              'UNIT',  999, 6),
        ( 6, 'Barra de Cereal Frutas 3un','Barra de cereal sabor frutas vermelhas, pacote 3un', 'UNIT',  449, 4),
        ( 6, 'Mingau de Aveia 180g',      'Mingau instantâneo sabor baunilha, sachê 180g',      'UNIT',  359, 2),
        -- 7. Alimentos Processados e Enlatados (8)
        ( 7, 'Milho Verde em Conserva 170g','Milho verde em conserva, lata 170g',               'UNIT',  349, 5),
        ( 7, 'Ervilha em Conserva 170g',  'Ervilha em conserva, lata 170g',                     'UNIT',  349, 4),
        ( 7, 'Sardinha em Óleo 125g',     'Sardinha em óleo comestível, lata 125g',             'UNIT',  599, 5),
        ( 7, 'Atum Sólido em Óleo 170g',  'Atum sólido em óleo, lata 170g',                     'UNIT',  899, 5),
        ( 7, 'Extrato de Tomate 340g',    'Extrato de tomate concentrado, lata 340g',           'UNIT',  499, 6),
        ( 7, 'Goiabada Cascão 300g',      'Goiabada cascão em barra, 300g',                     'UNIT',  549, 3),
        ( 7, 'Palmito Pupunha 300g',      'Palmito de pupunha em conserva, vidro 300g',         'UNIT', 1690, 2),
        ( 7, 'Azeitona Verde sem Caroço 200g','Azeitona verde sem caroço, sachê 200g',          'UNIT',  699, 3),
        -- 8. Congelados (8)
        ( 8, 'Hambúrguer Bovino 672g',    'Hambúrguer bovino congelado, caixa com 12 un',       'UNIT', 2390, 6),
        ( 8, 'Nuggets de Frango 300g',    'Nuggets de frango empanados, pacote 300g',           'UNIT', 1390, 6),
        ( 8, 'Pizza Mussarela 460g',      'Pizza de mussarela congelada, 460g',                 'UNIT', 1990, 5),
        ( 8, 'Lasanha à Bolonhesa 600g',  'Lasanha congelada à bolonhesa, 600g',                'UNIT', 2190, 4),
        ( 8, 'Batata Palito Pré-Frita 1kg','Batata palito pré-frita congelada, pacote 1kg',     'UNIT', 1690, 5),
        ( 8, 'Sorvete Napolitano 2L',     'Sorvete sabor napolitano, pote 2 litros',            'UNIT', 2490, 6),
        ( 8, 'Polpa de Cajá 100g',        'Polpa de fruta congelada de cajá, 100g',             'UNIT',  349, 4),
        ( 8, 'Açaí Congelado 1kg',        'Polpa de açaí congelada, 1kg',                       'UNIT', 2290, 4),
        -- 9. Bebidas (14)
        ( 9, 'Refrigerante Cola 2L',      'Refrigerante sabor cola, garrafa 2 litros',          'UNIT',  929, 10),
        ( 9, 'Refrigerante Guaraná 2L',   'Refrigerante sabor guaraná, garrafa 2 litros',       'UNIT',  829, 8),
        ( 9, 'Refrigerante Lata 350ml',   'Refrigerante sabores sortidos, lata 350ml',          'UNIT',  399, 7),
        ( 9, 'Suco de Uva Integral 1L',   'Suco de uva integral sem adição de açúcar, 1 litro', 'UNIT',  1190, 4),
        ( 9, 'Suco em Pó Sabor Laranja',  'Suco em pó sabor laranja, sachê 25g',                'UNIT',   99, 6),
        ( 9, 'Água Mineral sem Gás 1,5L', 'Água mineral natural sem gás, garrafa 1,5 litro',    'UNIT',  229, 10),
        ( 9, 'Água Mineral com Gás 500ml','Água mineral com gás, garrafa 500ml',                'UNIT',  229, 4),
        ( 9, 'Água Mineral Galão 20L',    'Água mineral natural, galão retornável 20 litros',   'UNIT',  1190, 5),
        ( 9, 'Energético 269ml',          'Bebida energética, lata 269ml',                      'UNIT',  599, 4),
        ( 9, 'Chá Gelado Limão 1,5L',     'Chá gelado sabor limão, garrafa 1,5 litro',          'UNIT',  699, 3),
        ( 9, 'Água de Coco 1L',           'Água de coco esterilizada, caixa 1 litro',           'UNIT',  1290, 4),
        ( 9, 'Isotônico Laranja 500ml',   'Bebida isotônica sabor laranja, garrafa 500ml',      'UNIT',  549, 3),
        ( 9, 'Café Solúvel 100g',         'Café solúvel granulado, vidro 100g',                 'UNIT',  1890, 3),
        ( 9, 'Mate Gelado Natural 1,5L',  'Chá mate gelado natural, garrafa 1,5 litro',         'UNIT',  699, 3),
        -- 10. Doces e Salgadinhos (10)
        (10, 'Chocolate Classic ao Leite','Barra de chocolate ao leite 80g',                    'UNIT',  549, 8),
        (10, 'Bombom Sortido 1kg',        'Caixa de bombons sortidos, 1kg',                     'UNIT',  3490, 3),
        (10, 'Chiclete de Hortelã',       'Chiclete sabor hortelã, pacote com 5 un',            'UNIT',  199, 4),
        (10, 'Pirulito Sortido 50un',     'Pirulitos frutas sortidos, pacote com 50 un',        'UNIT',  1190, 2),
        (10, 'Batata Chips Original 96g', 'Batata chips sabor original, pacote 96g',            'UNIT',  1090, 7),
        (10, 'Salgadinho de Milho 100g',  'Salgadinho de milho sabor queijo, pacote 100g',      'UNIT',  699, 6),
        (10, 'Amendoim Japonês 500g',     'Amendoim japonês crocante, pacote 500g',             'UNIT',  1090, 3),
        (10, 'Paçoca Rolha 20un',         'Paçoca de amendoim tipo rolha, pote 20un',           'UNIT',  1490, 4),
        (10, 'Doce de Leite 400g',        'Doce de leite cremoso, pote 400g',                   'UNIT',  1090, 3),
        (10, 'Gelatina Sabor Morango 20g','Gelatina em pó sabor morango, sachê 20g',            'UNIT',  199, 3),
        -- 11. Materiais de Limpeza (12)
        (11, 'Detergente Líquido Neutro 500ml','Detergente líquido neutro, frasco 500ml',       'UNIT',  269, 10),
        (11, 'Sabão em Pó 1,6kg',         'Sabão em pó multiação, caixa 1,6kg',                 'UNIT', 2190, 8),
        (11, 'Sabão em Barra 5un',        'Sabão em barra neutro, pacote com 5 un',             'UNIT',  1190, 5),
        (11, 'Amaciante 2L',              'Amaciante de roupas concentrado, 2 litros',          'UNIT', 1790, 6),
        (11, 'Água Sanitária 2L',         'Água sanitária 2 litros',                            'UNIT',  699, 8),
        (11, 'Desinfetante Floral 2L',    'Desinfetante perfumado floral, 2 litros',            'UNIT',  899, 6),
        (11, 'Limpa Vidros 500ml',        'Limpa vidros com gatilho, 500ml',                    'UNIT',  1090, 2),
        (11, 'Esponja Multiuso 3un',      'Esponja de dupla face, pacote com 3 un',             'UNIT',  499, 8),
        (11, 'Saco de Lixo 50L 30un',     'Saco de lixo preto 50 litros, rolo com 30 un',       'UNIT',  1290, 6),
        (11, 'Álcool 70% 1L',             'Álcool etílico 70% líquido, 1 litro',                'UNIT',  1190, 4),
        (11, 'Lustra Móveis 200ml',       'Lustra móveis lavanda, 200ml',                       'UNIT',  1490, 1),
        (11, 'Desinfetante a Granel',     'Desinfetante perfumado a granel, vendido por litro', 'LITER',  690, 2),
        -- 12. Higiene e Cuidados Pessoais (14)
        (12, 'Papel Higiênico Folha Dupla 12un','Papel higiênico folha dupla, pacote com 12 rolos','UNIT', 1890, 10),
        (12, 'Sabonete em Barra 85g',     'Sabonete em barra hidratante, 85g',                  'UNIT',  229, 9),
        (12, 'Creme Dental 90g',          'Creme dental com flúor, tubo 90g',                   'UNIT',  349, 9),
        (12, 'Escova Dental Macia',       'Escova dental cerdas macias',                        'UNIT',  699, 5),
        (12, 'Shampoo 350ml',             'Shampoo hidratação, frasco 350ml',                   'UNIT',  1390, 6),
        (12, 'Condicionador 350ml',       'Condicionador hidratação, frasco 350ml',             'UNIT',  1390, 5),
        (12, 'Desodorante Aerossol 150ml','Desodorante antitranspirante aerossol, 150ml',       'UNIT',  1190, 6),
        (12, 'Absorvente com Abas 8un',   'Absorvente noturno com abas, pacote com 8 un',       'UNIT',  699, 6),
        (12, 'Fio Dental 50m',            'Fio dental menta, 50 metros',                        'UNIT',  549, 3),
        (12, 'Hastes Flexíveis 75un',     'Hastes flexíveis de algodão, caixa com 75 un',       'UNIT',  379, 3),
        (12, 'Aparelho de Barbear 2un',   'Aparelho de barbear descartável, pacote com 2 un',   'UNIT',  699, 3),
        (12, 'Sabonete Líquido 250ml',    'Sabonete líquido para as mãos, refil 250ml',         'UNIT',  1190, 3),
        (12, 'Protetor Solar FPS 50 120ml','Protetor solar FPS 50, frasco 120ml',               'UNIT',  3990, 2),
        (12, 'Álcool em Gel 70% 500g',    'Álcool em gel antisséptico 70%, 500g',               'UNIT',  1090, 3),
        -- 13. Artigos de Descarte e Papelaria (5)
        (13, 'Copo Descartável 200ml 100un','Copo descartável 200ml, pacote com 100 un',        'UNIT',  799, 5),
        (13, 'Guardanapo de Papel 50un',  'Guardanapo de papel folha simples, pacote 50 un',    'UNIT',  329, 6),
        (13, 'Papel Alumínio 30cm x 7,5m','Papel alumínio rolo 30cm por 7,5m',                  'UNIT',  899, 4),
        (13, 'Caneta Esferográfica Azul', 'Caneta esferográfica ponta média azul',              'UNIT',  199, 3),
        (13, 'Caderno Brochura 96fls',    'Caderno brochura capa dura, 96 folhas',              'UNIT',  1490, 2),
        -- 14. Cuidados com o Bebê (5)
        (14, 'Fralda Descartável M 36un', 'Fralda descartável tamanho M, pacote com 36 un',     'UNIT', 3990, 6),
        (14, 'Fralda Descartável G 32un', 'Fralda descartável tamanho G, pacote com 32 un',     'UNIT', 3990, 6),
        (14, 'Lenço Umedecido 100un',     'Lenço umedecido para bebê, pacote com 100 un',       'UNIT', 1090, 5),
        (14, 'Fórmula Infantil 800g',     'Fórmula infantil de seguimento, lata 800g',          'UNIT', 7490, 3),
        (14, 'Pomada para Assaduras 45g', 'Pomada preventiva de assaduras, bisnaga 45g',        'UNIT', 1690, 3),
        -- 15. Produtos para Pet Shop (5)
        (15, 'Ração para Cães Adultos 10kg','Ração seca para cães adultos, pacote 10kg',        'UNIT', 14990, 4),
        (15, 'Ração para Gatos 1kg',      'Ração seca para gatos adultos, pacote 1kg',          'UNIT', 2490, 3),
        (15, 'Sachê para Gatos 85g',      'Alimento úmido para gatos, sachê 85g',               'UNIT',  349, 4),
        (15, 'Petisco para Cães 65g',     'Petisco palito para cães, pacote 65g',               'UNIT',  899, 3),
        (15, 'Areia Higiênica para Gatos 4kg','Areia higiênica granulada, pacote 4kg',          'UNIT', 1890, 3),
        -- 16. Frios e Embutidos (8)
        (16, 'Presunto Cozido Fatiado',   'Presunto cozido fatiado, vendido por kg',            'KG',   3490, 6),
        (16, 'Mussarela Fatiada',         'Queijo mussarela fatiado, vendido por kg',           'KG',   4290, 7),
        (16, 'Queijo Prato Fatiado',      'Queijo prato fatiado, vendido por kg',               'KG',   4590, 4),
        (16, 'Mortadela Defumada',        'Mortadela defumada fatiada, vendida por kg',         'KG',   2290, 5),
        (16, 'Salame Italiano Fatiado',   'Salame italiano fatiado, vendido por kg',            'KG',   7490, 2),
        (16, 'Peito de Peru Defumado',    'Peito de peru defumado fatiado, vendido por kg',     'KG',   6490, 2),
        (16, 'Salsicha Hot Dog 500g',     'Salsicha tipo hot dog, pacote 500g',                 'UNIT', 1090, 6),
        (16, 'Bacon em Cubos 250g',       'Bacon defumado em cubos, pacote 250g',               'UNIT', 1690, 4),
        -- 17. Molhos, Temperos e Condimentos (10)
        (17, 'Maionese 500g',             'Maionese tradicional, pote 500g',                    'UNIT',  899, 7),
        (17, 'Ketchup 380g',              'Ketchup tradicional, bisnaga 380g',                  'UNIT',  799, 6),
        (17, 'Molho de Tomate 300g',      'Molho de tomate tradicional, sachê 300g',            'UNIT',  349, 8),
        (17, 'Vinagre de Álcool 750ml',   'Vinagre de álcool, garrafa 750ml',                   'UNIT',  349, 4),
        (17, 'Pimenta do Reino Moída 50g','Pimenta do reino moída, pote 50g',                   'UNIT',  699, 3),
        (17, 'Colorau 100g',              'Colorífico em pó, pacote 100g',                      'UNIT',  349, 5),
        (17, 'Tempero Completo 300g',     'Tempero completo com pimenta, pote 300g',            'UNIT',  899, 7),
        (17, 'Azeite de Dendê 200ml',     'Azeite de dendê, garrafa 200ml',                     'UNIT',  1190, 5),
        (17, 'Castanha de Caju a Granel', 'Castanha de caju torrada a granel, vendida por grama','GRAM',    14, 3),
        (17, 'Mel Puro a Granel',         'Mel de abelha puro a granel, vendido por mililitro', 'MILLILITER', 8, 2),
        -- 18. Bebidas Alcoólicas (8)
        (18, 'Cerveja Pilsen Lata 350ml', 'Cerveja pilsen, lata 350ml',                         'UNIT',  379, 10),
        (18, 'Cerveja Long Neck 330ml',   'Cerveja premium, garrafa long neck 330ml',           'UNIT',  549, 6),
        (18, 'Cerveja Pilsen Garrafa 600ml','Cerveja pilsen, garrafa 600ml',                    'UNIT',  839, 6),
        (18, 'Vinho Tinto Seco 750ml',    'Vinho tinto seco de mesa, garrafa 750ml',            'UNIT',  3290, 4),
        (18, 'Vinho Branco Suave 750ml',  'Vinho branco suave de mesa, garrafa 750ml',          'UNIT',  2990, 3),
        (18, 'Cachaça Artesanal 700ml',   'Cachaça artesanal envelhecida, garrafa 700ml',       'UNIT',  3490, 4),
        (18, 'Vodka Nacional 1L',         'Vodka nacional, garrafa 1 litro',                    'UNIT',  3190, 3),
        (18, 'Espumante Brut 750ml',      'Espumante brut, garrafa 750ml',                      'UNIT',  4990, 2),
        -- 19. Utilidades Domésticas (5)
        (19, 'Pilha Alcalina AA 4un',     'Pilha alcalina AA, cartela com 4 un',                'UNIT',  1990, 4),
        (19, 'Lâmpada LED 9W',            'Lâmpada LED bulbo 9W luz branca',                    'UNIT',  1290, 4),
        (19, 'Vela Branca 10un',          'Vela branca comum, pacote com 10 un',                'UNIT',   899, 3),
        (19, 'Fósforo 40 palitos 10un',   'Caixa de fósforos, pacote com 10 caixas',            'UNIT',   799, 3),
        (19, 'Pano de Prato Algodão',     'Pano de prato de algodão estampado',                 'UNIT',   799, 3),
        -- 20. Massas e Biscoitos (10)
        (20, 'Macarrão Espaguete 500g',   'Macarrão espaguete sêmola, pacote 500g',             'UNIT',  499, 9),
        (20, 'Macarrão Parafuso 500g',    'Macarrão parafuso sêmola, pacote 500g',              'UNIT',  499, 6),
        (20, 'Macarrão Instantâneo Galinha 80g','Macarrão instantâneo sabor galinha, pacote 80g','UNIT', 249, 8),
        (20, 'Biscoito Cream Cracker 400g','Biscoito cream cracker, pacote 400g',               'UNIT',  549, 8),
        (20, 'Biscoito Recheado Chocolate 130g','Biscoito recheado sabor chocolate, pacote 130g','UNIT', 299, 9),
        (20, 'Biscoito Maisena 350g',     'Biscoito maisena, pacote 350g',                      'UNIT',  499, 6),
        (20, 'Biscoito Água e Sal 350g',  'Biscoito água e sal, pacote 350g',                   'UNIT',  549, 5),
        (20, 'Torrada Alho 140g',         'Torrada sabor alho, pacote 140g',                    'UNIT',  499, 3),
        (20, 'Farofa Pronta 500g',        'Farofa temperada pronta, pacote 500g',               'UNIT',  599, 5),
        (20, 'Massa para Lasanha 500g',   'Massa para lasanha sêmola, pacote 500g',             'UNIT',  799, 3)
    ) AS p(cat_idx, name, descr, unit, price_now, pop);

    SELECT count(*) INTO v_n FROM tmp_prod;
    IF v_n <> 200 THEN
        RAISE EXCEPTION 'catalogo de produtos deveria ter 200 itens, tem %', v_n;
    END IF;

    -- Codigo de barras: EAN-13 nacional (789) para embalados; EAN-13 interno (2) para pesaveis/granel.
    -- O "Chocolate Classic" mantem o codigo que o Config.java ja usa.
    UPDATE tmp_prod SET barcode =
        CASE
            WHEN name = 'Chocolate Classic ao Leite' THEN '8823999944221'
            WHEN unit = 'UNIT' THEN pg_temp.fn_ean13('789' || lpad((1000 + n * 7)::text, 4, '0') || lpad((n * 137 + 4000)::text, 5, '0'))
            ELSE pg_temp.fn_ean13('2' || lpad((n + 100)::text, 11, '0'))
        END;

    INSERT INTO product (uuid, category_id, barcode, name, description, unit_of_measure, created_at)
    SELECT md5('product:' || p.barcode)::uuid, c.id, p.barcode, p.name, p.descr, p.unit,
           (v_open - (20 + (p.n * 7) % 40))::timestamp AT TIME ZONE c_tz
      FROM tmp_prod p
      JOIN tmp_cat c ON c.idx = p.cat_idx
     WHERE NOT EXISTS (SELECT 1 FROM product x WHERE x.barcode = p.barcode);

    UPDATE tmp_prod p SET id = x.id FROM product x WHERE x.barcode = p.barcode;

    -- Produto criado pelo Config.java na hora (created_at = agora) ficaria depois das vendas.
    UPDATE product SET created_at = (v_open - (20 + (id * 7) % 40)::int)::timestamp AT TIME ZONE c_tz
     WHERE created_at > v_open::timestamp AT TIME ZONE c_tz
       AND id IN (SELECT id FROM tmp_prod);

    -- ==========================================================================================
    -- 3) Fornecedores (100): 2 por produto em media (principal + alternativo), todos usados.
    --    cat_idx | razao social | DDD. Quantidade por categoria = metade do numero de produtos.
    -- ==========================================================================================
    CREATE TEMP TABLE tmp_sup (
        n        int PRIMARY KEY,
        cat_idx  int NOT NULL,
        name     text NOT NULL,
        ddd      text NOT NULL,
        id       bigint,
        dow      int                     -- dia da semana da entrega (1=seg .. 6=sab)
    ) ON COMMIT DROP;

    INSERT INTO tmp_sup (n, cat_idx, name, ddd)
    SELECT row_number() OVER (), * FROM (VALUES
        -- 1. Hortifrúti (11)
        ( 1, 'Hortifruti Chapada Diamantina Ltda',          '75'),
        ( 1, 'Cooperativa Agricola Vale do Paraguaçu',      '75'),
        ( 1, 'Sítio Boa Esperança Frutas e Verduras ME',    '71'),
        ( 1, 'Frutas do Vale do São Francisco S.A.',        '74'),
        ( 1, 'Verde Campo Hortaliças Ltda',                 '71'),
        ( 1, 'Distribuidora Feira de Santana Hortifruti',   '75'),
        ( 1, 'Fazenda Três Irmãos Produtos Agrícolas',      '73'),
        ( 1, 'Raízes do Recôncavo Comércio de Tubérculos',  '75'),
        ( 1, 'Pomar Nordeste Frutas Tropicais Ltda',        '81'),
        ( 1, 'Central de Abastecimento Frutas e Legumes',   '71'),
        ( 1, 'Agrícola Sol Nascente Ltda',                  '77'),
        -- 2. Padaria e Confeitaria (4)
        ( 2, 'Panificadora Pão Quente Ltda',                '71'),
        ( 2, 'Confeitaria Doce Sabor Indústria e Comércio', '71'),
        ( 2, 'Massas e Pães Santo Antônio ME',              '71'),
        ( 2, 'Forno de Minas Salgados e Bolos Ltda',        '31'),
        -- 3. Laticínios e Ovos (6)
        ( 3, 'Laticínios Vale Verde S.A.',                  '75'),
        ( 3, 'Cooperativa de Leite do Sertão Baiano',       '74'),
        ( 3, 'Granja Ovos do Campo Ltda',                   '71'),
        ( 3, 'Laticínios Serra Azul Indústria e Comércio',  '31'),
        ( 3, 'Queijaria Boa Vista Ltda',                    '77'),
        ( 3, 'Avícola Santa Clara Ovos e Derivados',        '75'),
        -- 4. Carnes, Aves e Peixes (7)
        ( 4, 'Frigorífico Boi do Sertão S.A.',              '77'),
        ( 4, 'Aves do Nordeste Abatedouro Ltda',            '81'),
        ( 4, 'Pescados Baía de Todos os Santos Ltda',       '71'),
        ( 4, 'Frigorífico Cidade do Salvador Ltda',         '71'),
        ( 4, 'Suínos Rio Vermelho Carnes e Derivados',      '71'),
        ( 4, 'Carnes Nobres Planalto Distribuidora',        '62'),
        ( 4, 'Camarões do Litoral Norte Ltda',              '71'),
        -- 5. Mercearia e Grãos (7)
        ( 5, 'Armazém Grãos do Brasil Atacadista',          '71'),
        ( 5, 'Cerealista Dois Irmãos Ltda',                 '75'),
        ( 5, 'Distribuidora Mesa Farta de Alimentos',       '71'),
        ( 5, 'Torrefação Café da Serra Ltda',               '31'),
        ( 5, 'Moinho São Jorge Farinhas e Derivados',       '71'),
        ( 5, 'Usina Doçura do Recôncavo S.A.',              '75'),
        ( 5, 'Óleos e Azeites Mediterrâneo Importadora',    '11'),
        -- 6. Cereais e Matinais (4)
        ( 6, 'Cereais Bom Dia Indústria Alimentícia',       '11'),
        ( 6, 'Moinho Nutrigrão Ltda',                       '41'),
        ( 6, 'Tapiocas e Cuscuz Sabor da Terra ME',         '71'),
        ( 6, 'Alimentos Integrais Vida Natural Ltda',       '51'),
        -- 7. Alimentos Processados e Enlatados (4)
        ( 7, 'Conservas Campo Fértil S.A.',                 '11'),
        ( 7, 'Pescados Enlatados Mar Azul Ltda',            '47'),
        ( 7, 'Indústria de Doces e Compotas Casa Velha',    '35'),
        ( 7, 'Tomate Forte Agroindustrial Ltda',            '62'),
        -- 8. Congelados (4)
        ( 8, 'Congelados Polar Indústria de Alimentos',     '71'),
        ( 8, 'Sorvetes Gelato Bahia Ltda',                  '71'),
        ( 8, 'Polpas de Frutas Tropicais do Nordeste',      '73'),
        ( 8, 'Alimentos Prontos Saborear Congelados S.A.',  '11'),
        -- 9. Bebidas (7)
        ( 9, 'Distribuidora de Bebidas Litoral Ltda',       '71'),
        ( 9, 'Engarrafadora Fonte Cristalina S.A.',         '75'),
        ( 9, 'Refrigerantes Nordeste Indústria e Comércio', '81'),
        ( 9, 'Sucos e Néctares Pomar Real Ltda',           '75'),
        ( 9, 'Águas Minerais Serra da Mantiqueira',         '12'),
        ( 9, 'Atacado de Bebidas Pelourinho Ltda',          '71'),
        ( 9, 'Energéticos e Isotônicos Vitalis S.A.',       '11'),
        -- 10. Doces e Salgadinhos (5)
        (10, 'Chocolates Cacau do Sul da Bahia Ltda',       '73'),
        (10, 'Salgadinhos Crocantes Indústria de Snacks',   '11'),
        (10, 'Confeitos e Balas Doce Vida S.A.',            '41'),
        (10, 'Amendoins e Paçocas Zé do Campo ME',          '71'),
        (10, 'Distribuidora de Doces Sabor Brasil',         '71'),
        -- 11. Materiais de Limpeza (6)
        (11, 'Limpeza Total Produtos Químicos Ltda',        '71'),
        (11, 'Química Brilho Forte Indústria',              '11'),
        (11, 'Detergentes e Saneantes Aqua Pura',           '19'),
        (11, 'Distribuidora Casa Limpa Atacado',            '71'),
        (11, 'Descartáveis e Limpeza Nordeste Ltda',        '81'),
        (11, 'Sabões Dona Maria Indústria e Comércio',      '75'),
        -- 12. Higiene e Cuidados Pessoais (7)
        (12, 'Higiene Pessoal Bem-Estar S.A.',              '11'),
        (12, 'Cosméticos Pele Macia Indústria',             '21'),
        (12, 'Papéis Higiênicos Folha Suave Ltda',          '41'),
        (12, 'Perfumaria e Higiene Flor de Lis',            '71'),
        (12, 'Distribuidora Cuidar Saúde e Higiene',        '71'),
        (12, 'Odontológicos Sorriso Brilhante Ltda',        '31'),
        (12, 'Laboratório Dermacare Cosméticos S.A.',       '19'),
        -- 13. Artigos de Descarte e Papelaria (3)
        (13, 'Descartáveis Festa e Cia Ltda',               '71'),
        (13, 'Papelaria Central Atacado',                   '71'),
        (13, 'Embalagens Prático Alumínio e Papel',         '11'),
        -- 14. Cuidados com o Bebê (2)
        (14, 'Baby Care Distribuidora de Fraldas Ltda',     '71'),
        (14, 'Nutrição Infantil Primeiros Passos S.A.',     '11'),
        -- 15. Pet Shop (3)
        (15, 'Pet Food Nordeste Indústria Ltda',            '81'),
        (15, 'Distribuidora Amigo Fiel Pet Shop',           '71'),
        (15, 'Rações Premium Bicho Feliz S.A.',             '14'),
        -- 16. Frios e Embutidos (4)
        (16, 'Frios Italianos Delícia Fatiados Ltda',       '71'),
        (16, 'Embutidos Serra Gaúcha S.A.',                 '54'),
        (16, 'Laticínios Fatiados Boa Mesa Ltda',           '31'),
        (16, 'Charcutaria Dom Carlos Distribuidora',        '71'),
        -- 17. Molhos, Temperos e Condimentos (5)
        (17, 'Temperos e Especiarias Sabor do Mundo',       '11'),
        (17, 'Molhos Casa Nobre Indústria Alimentícia',     '16'),
        (17, 'Dendê e Azeites Tradição Baiana Ltda',        '71'),
        (17, 'Especiarias do Recôncavo Comércio ME',        '75'),
        (17, 'Castanhas e Méis Doce Natureza Ltda',         '85'),
        -- 18. Bebidas Alcoólicas (4)
        (18, 'Cervejaria Praia Dourada S.A.',               '71'),
        (18, 'Adega Vale dos Vinhedos Importadora',         '54'),
        (18, 'Engenho Cachaça Reserva Ltda',                '75'),
        (18, 'Distribuidora de Destilados Porto Seguro',    '73'),
        -- 19. Utilidades Domésticas (2)
        (19, 'Utilidades Casa e Lar Atacadista',            '71'),
        (19, 'Iluminação e Pilhas Energia Forte Ltda',      '11'),
        -- 20. Massas e Biscoitos (5)
        (20, 'Massas Nonna Rosa Indústria Alimentícia',     '11'),
        (20, 'Biscoitos Crocantes do Nordeste Ltda',        '81'),
        (20, 'Moinho e Macarrão Dois Leões S.A.',           '71'),
        (20, 'Biscoitaria Vovó Ana Ltda',                   '31'),
        (20, 'Farofas e Massas Mesa Baiana ME',             '71')
    ) AS s(cat_idx, name, ddd);

    SELECT count(*) INTO v_n FROM tmp_sup;
    IF v_n <> 100 THEN
        RAISE EXCEPTION 'lista de fornecedores deveria ter 100 itens, tem %', v_n;
    END IF;

    INSERT INTO supplier (uuid, tax_number, name, email, phone, created_at, updated_at)
    SELECT md5('supplier:' || s.n)::uuid,
           pg_temp.fn_cnpj((s.n::bigint * 73856093 + 19349663) % 100000000),
           s.name,
           'comercial@' || pg_temp.fn_slug(s.name) || '.com.br',
           '(' || s.ddd || ') ' ||
               CASE WHEN s.n % 4 = 0 THEN '9' || lpad((8000 + (s.n * 131) % 2000)::text, 4, '0')
                    ELSE '3' || lpad((200 + (s.n * 37) % 700)::text, 3, '0') END
               || '-' || lpad(((s.n * 7919) % 10000)::text, 4, '0'),
           (v_open - (30 + (s.n * 13) % 700))::timestamp AT TIME ZONE c_tz,
           (v_open - (30 + (s.n * 13) % 700))::timestamp AT TIME ZONE c_tz
             + ((s.n * 11) % 25) * interval '1 day'
      FROM tmp_sup s
     ORDER BY s.n;

    UPDATE tmp_sup s SET id = x.id,
           dow = 1 + (s.n * 5 + s.cat_idx) % 6
      FROM supplier x
     WHERE x.uuid = md5('supplier:' || s.n)::uuid;

    -- Liga cada produto a um fornecedor principal e a um alternativo, dentro da propria categoria
    -- (rodizio entre os fornecedores da categoria, entao todos os 100 fornecedores sao usados).
    WITH pc AS (
        SELECT p.n, p.cat_idx,
               row_number() OVER (PARTITION BY p.cat_idx ORDER BY p.n) - 1 AS j
          FROM tmp_prod p
    ), sc AS (
        SELECT s.id, s.cat_idx,
               row_number() OVER (PARTITION BY s.cat_idx ORDER BY s.n) - 1 AS k,
               count(*)     OVER (PARTITION BY s.cat_idx)                  AS cnt
          FROM tmp_sup s
    )
    UPDATE tmp_prod p
       SET sup_main = m.id,
           sup_alt  = a.id,
           review_dow = (SELECT s.dow FROM tmp_sup s WHERE s.id = m.id)
      FROM pc
      JOIN sc m ON m.cat_idx = pc.cat_idx AND m.k = pc.j % m.cnt
      JOIN sc a ON a.cat_idx = pc.cat_idx AND a.k = (pc.j + 1) % a.cnt
     WHERE p.n = pc.n;

    -- ==========================================================================================
    -- 4) Lojas (3) e funcionarios (16)
    --    share e a fatia das vendas da rede que cada loja recebe. Cada loja e cadastrada antes de
    --    contratar o primeiro funcionario; o gerente e definido depois, porque precisa trabalhar nela.
    -- ==========================================================================================
    CREATE TEMP TABLE tmp_store (
        n          int PRIMARY KEY,
        name       text NOT NULL,
        street     text NOT NULL,
        number     text NOT NULL,
        district   text NOT NULL,
        zip        text NOT NULL,
        share      numeric NOT NULL,
        opened_at  date NOT NULL,
        manager_n  int NOT NULL,
        id         bigint
    ) ON COMMIT DROP;

    INSERT INTO tmp_store (n, name, street, number, district, zip, share, opened_at, manager_n) VALUES
        (1, 'Mercado Central - Pituba',  'Av. Manoel Dias da Silva', '1200', 'Pituba', '41830000', 0.45, v_start - 820,  2),
        (2, 'Mercado Central - Barra',   'Av. Sete de Setembro',     '3450', 'Barra',  '40140000', 0.33, v_start - 420,  3),
        (3, 'Mercado Central - Itapuã',  'Rua da Música',            '85',   'Itapuã', '41610000', 0.22, v_start - 260, 16);

    INSERT INTO store (uuid, name, street, number, district, city, state, zip_code, created_at, updated_at)
    SELECT md5('store:' || s.n)::uuid, s.name, s.street, s.number, s.district, 'Salvador', 'BA', s.zip,
           (s.opened_at + time '08:00')::timestamp AT TIME ZONE c_tz,
           (s.opened_at + time '08:00')::timestamp AT TIME ZONE c_tz
      FROM tmp_store s
     ORDER BY s.n;

    UPDATE tmp_store s SET id = x.id FROM store x WHERE x.uuid = md5('store:' || s.n)::uuid;

    -- store_n nulo so para o ADMIN, que e da rede (o CHECK ck_staff_store_by_role confere).
    CREATE TEMP TABLE tmp_staff (
        n          int PRIMARY KEY,
        name       text NOT NULL,
        role       text NOT NULL,
        store_n    int,
        hired_at   date NOT NULL,
        left_at    date,
        id         bigint
    ) ON COMMIT DROP;

    INSERT INTO tmp_staff (n, name, role, store_n, hired_at, left_at) VALUES
        ( 1, 'Roberto Almeida Santos',     'ADMIN',    NULL, v_start - 900, NULL),
        ( 2, 'Fernanda Costa Menezes',     'MANAGER',  1,    v_start - 700, NULL),
        ( 3, 'Marcelo Queiroz Barreto',    'MANAGER',  2,    v_start - 400, NULL),
        ( 4, 'Juliana Souza Ribeiro',      'CASHIER',  1,    v_start - 600, NULL),
        ( 5, 'Carlos Eduardo Nascimento',  'CASHIER',  1,    v_start - 500, NULL),
        ( 6, 'Patrícia Oliveira Lima',     'CASHIER',  1,    v_start - 450, NULL),
        ( 7, 'Anderson Ferreira Pinto',    'CASHIER',  2,    v_start - 300, NULL),
        ( 8, 'Larissa Gomes Cardoso',      'CASHIER',  3,    v_start - 200, NULL),
        ( 9, 'Tiago Lopes Moreira',        'CASHIER',  2,    v_start - 120, v_end - 330),   -- pediu demissao
        (10, 'Viviane Rocha Teixeira',     'CASHIER',  1,    v_start + 150, NULL),          -- contratada no meio
        (11, 'Gabriel Santana Freitas',    'CASHIER',  2,    v_start + 400, NULL),
        (12, 'Edson Pereira Duarte',       'STOCKIST', 1,    v_start - 800, NULL),
        (13, 'Mônica Araújo Batista',      'STOCKIST', 2,    v_start - 350, NULL),
        (14, 'Rafael Mendes Carvalho',     'STOCKIST', 3,    v_start - 100, NULL),
        (15, 'Isabela Correia Andrade',    'STOCKIST', 1,    v_start + 200, NULL),
        (16, 'Luciana Prado Matos',        'MANAGER',  3,    v_start - 250, NULL);

-- Herança JOINED: dados comuns em person; o cargo em staff, com o mesmo id
INSERT INTO person (uuid, tax_number, name, created_at, updated_at, deleted_at)
SELECT md5('staff:' || t.n)::uuid,
        pg_temp.fn_cpf((t.n::bigint * 27644437 + 41297761) % 1000000000),
       t.name,
       t.hired_at::timestamp AT TIME ZONE c_tz,
        (COALESCE(t.left_at, t.hired_at)::timestamp AT TIME ZONE c_tz) + interval '9 hours',
    (t.left_at::timestamp AT TIME ZONE c_tz) + interval '9 hours'
FROM tmp_staff t
ORDER BY t.n;

UPDATE tmp_staff t SET id = p.id FROM person p WHERE p.uuid = md5('staff:' || t.n)::uuid;

INSERT INTO staff (id, role, store_id)
SELECT t.id, t.role, s.id
FROM tmp_staff t
LEFT JOIN tmp_store s ON s.n = t.store_n
ORDER BY t.n;

    -- Gerente de cada loja: funcionario dela (a FK composta fk_store_manager_same_store confere).
    UPDATE store x
       SET manager_id = t.id,
           updated_at = greatest(x.created_at, (t.hired_at + time '09:00')::timestamp AT TIME ZONE c_tz)
      FROM tmp_store s
      JOIN tmp_staff t ON t.n = s.manager_n
     WHERE x.id = s.id;

    -- ==========================================================================================
    -- 5) Historico de precos (price)
    --    Cada produto termina no preco atual da lista; retrocedemos 0-3 reajustes (maioria
    --    aumentos de 2% a 9%, alguns recuos de promocao) em datas aleatorias dentro do periodo.
    -- ==========================================================================================
    CREATE TEMP TABLE tmp_price (
        product_id  bigint NOT NULL,
        cents       bigint NOT NULL,
        valid_from  timestamptz NOT NULL,
        valid_to    timestamptz,             -- NULL = vigente
        PRIMARY KEY (product_id, valid_from)
    ) ON COMMIT DROP;

    FOR r IN SELECT p.id, p.price_now, p.unit FROM tmp_prod p ORDER BY p.n LOOP
        v_changes := floor(random() * 4)::int;               -- 0..3 reajustes no periodo
        v_cur     := r.price_now;
        v_to      := NULL;

        -- datas dos reajustes: distintas, da mais recente para a mais antiga
        SELECT array_agg(d ORDER BY d DESC) INTO v_dates
          FROM (SELECT DISTINCT (v_start + 20 + floor(random() * ((v_end - v_start) - 40))::int) AS d
                  FROM generate_series(1, v_changes)) q;

        -- parte do preco atual e retrocede: cada reajuste vira uma versao anterior mais barata/cara
        FOR v_i IN 1..COALESCE(array_length(v_dates, 1), 0) LOOP
            v_from := (v_dates[v_i] + time '05:00')::timestamp AT TIME ZONE c_tz;
            INSERT INTO tmp_price VALUES (r.id, v_cur::bigint, v_from, v_to);
            v_factor := CASE WHEN random() < 0.82 THEN 1.02 + random() * 0.07
                             ELSE 0.93 + random() * 0.05 END;
            v_cur := CASE WHEN r.unit IN ('GRAM', 'MILLILITER')
                          THEN greatest(round(v_cur / v_factor), 1)
                          ELSE pg_temp.fn_retail_price(v_cur / v_factor) END;
            v_to  := v_from;
        END LOOP;
        -- versao mais antiga: vigora desde a abertura
        INSERT INTO tmp_price VALUES (r.id, v_cur::bigint, (v_open - 5)::timestamp AT TIME ZONE c_tz, v_to);
    END LOOP;

    INSERT INTO price (product_id, value_in_cents, created_at, deleted_at)
    SELECT product_id, cents, valid_from, valid_to
      FROM tmp_price
     ORDER BY valid_from, product_id;

    -- ==========================================================================================
    -- 6) Parametros de reposicao por produto
    -- ==========================================================================================
    -- Peso de popularidade (curva concentrada: poucos itens vendem muito) e demanda diaria
    -- esperada da rede, em unidade de medida. Itens por dia (media): ~2000 vendas * ~7 itens / dias do periodo.
    -- A demanda de uma loja e a da rede vezes a fatia dela (tmp_store.share).
    UPDATE tmp_prod p SET weight = (p.pop ^ 2.2)
                           * CASE WHEN p.unit IN ('GRAM', 'MILLILITER', 'LITER') THEN 0.5 ELSE 1 END;

    SELECT sum(weight) INTO v_total_w FROM tmp_prod;

    UPDATE tmp_prod p SET
        daily_units = (c_sales * 7.0 / (v_end - v_start + 1)) * (p.weight / v_total_w)
                      * CASE p.unit WHEN 'KG' THEN 0.9
                                    WHEN 'GRAM' THEN 250
                                    WHEN 'MILLILITER' THEN 400
                                    WHEN 'LITER' THEN 2.5
                                    ELSE 1 + c.qty_k END
      FROM tmp_cat c
     WHERE c.idx = p.cat_idx;

    -- Perecivel = validade curta (<= 35 dias): lote pequeno e duas entregas por semana.
    -- A demanda e baixa (~18 itens/dia na rede, dividida entre as lojas), entao o lote precisa ser pequeno para nao vencer.
    UPDATE tmp_prod p SET
        perishable = (c.shelf_max IS NOT NULL AND c.shelf_max <= 35),
        qty_k = c.qty_k,
        loss_prob = c.loss_prob,
        pack = CASE p.unit
                   WHEN 'KG' THEN 0.25
                   WHEN 'GRAM' THEN 500
                   WHEN 'MILLILITER' THEN 500
                   WHEN 'LITER' THEN 5
                   ELSE CASE WHEN p.cat_idx IN (9, 18) THEN 12            -- bebidas: fardo
                             WHEN c.shelf_max IS NOT NULL AND c.shelf_max <= 35 THEN 1
                             ELSE 6 END
               END
      FROM tmp_cat c
     WHERE c.idx = p.cat_idx;

    -- Segunda entrega semanal so para perecíveis (dow, dow+3).
    UPDATE tmp_prod p SET
        review_dow2 = CASE WHEN p.perishable THEN ((p.review_dow + 2) % 6) + 1 END;

    -- Estado de cada produto em cada loja: o lote de compra e a validade do lote mais recente (para a baixa
    -- por vencimento). A loja compra em lotes proporcionais ao movimento dela (lote da rede x fatia da loja):
    -- com o lote cheio, uma loja pequena levaria meses para girar o estoque minimo e perderia o lote vencido.
    CREATE TEMP TABLE tmp_store_prod (
        store_n      int NOT NULL,
        n            int NOT NULL,
        pack         numeric NOT NULL,
        batch_expiry date,
        PRIMARY KEY (store_n, n)
    ) ON COMMIT DROP;
    INSERT INTO tmp_store_prod (store_n, n, pack)
    SELECT s.n, p.n,
           CASE WHEN p.unit = 'UNIT' THEN greatest(1, round(p.pack * s.share)) ELSE round(p.pack * s.share, 3) END
      FROM tmp_store s CROSS JOIN tmp_prod p;

    -- Ruptura proposital: alguns itens de giro alto deixam de ser repostos nos ultimos 12 dias,
    -- para que existam produtos com estoque baixo/zerado na foto final do banco.
    UPDATE tmp_prod SET no_restock_after = v_end - 12
     WHERE n IN (SELECT n FROM (SELECT n FROM tmp_prod
                                 WHERE NOT perishable AND unit = 'UNIT'
                                 ORDER BY daily_units DESC, n LIMIT 30) t
                  ORDER BY random() LIMIT 8);

    -- Tabela de sorteio das cestas: criada ja ordenada por n e nunca atualizada. Os UPDATEs com JOIN acima
    -- reordenam fisicamente tmp_prod (conforme o plano), e random() e atribuido na ordem de leitura;
    -- sorteando daqui o resultado nao depende do plano de execucao.
    CREATE TEMP TABLE tmp_pick ON COMMIT DROP AS
        SELECT n, id, name, unit, cat_idx, qty_k, weight FROM tmp_prod ORDER BY n;

    -- ==========================================================================================
    -- 7) Carga inicial de estoque (compras de abertura dos nao perecíveis, uma por produto em cada loja)
    --    Cobre ~cover_days da demanda esperada da loja, minimo 2 lotes da loja. Os perecíveis entram pela
    --    primeira entrega semanal, so se houver demanda.
    -- ==========================================================================================
    FOR shop IN SELECT n, id, share FROM tmp_store ORDER BY n LOOP
        FOR r IN SELECT p.id, p.n, p.daily_units, p.sup_main, sp.pack, c.shelf_min, c.shelf_max, c.cost_ratio, c.cover_days
                   FROM tmp_prod p
                   JOIN tmp_cat c ON c.idx = p.cat_idx
                   JOIN tmp_store_prod sp ON sp.store_n = shop.n AND sp.n = p.n
                  WHERE NOT p.perishable ORDER BY p.n LOOP
            v_cost := greatest(round(
                (SELECT cents FROM tmp_price WHERE product_id = r.id ORDER BY valid_from LIMIT 1)
                * r.cost_ratio * (0.97 + random() * 0.06)), 1);
            v_expires := CASE WHEN r.shelf_min IS NULL THEN NULL
                              ELSE v_open + (r.shelf_min + floor(random() * (r.shelf_max - r.shelf_min + 1))::int) END;
            v_order_qty := greatest(r.pack * 2, ceil(r.daily_units * shop.share * r.cover_days * 1.5 / r.pack) * r.pack);
            INSERT INTO inventory_movement (store_id, product_id, supplier_id, type, quantity, unit_cost_in_cents,
                                            batch_expires_at, created_at)
            VALUES (shop.id, r.id, r.sup_main, 'PURCHASE', v_order_qty, v_cost, v_expires,
                    (v_open + time '06:00' + r.n * interval '2 minutes')::timestamp AT TIME ZONE c_tz);
            UPDATE tmp_store_prod SET batch_expiry = v_expires WHERE store_n = shop.n AND n = r.n;
            v_buy_count := v_buy_count + 1;
        END LOOP;
    END LOOP;

    -- ==========================================================================================
    -- 8) Pesos por dia (sazonalidade) e sorteio dos horarios das vendas
    --    Mais movimento: sexta/sabado, inicio de mes (salario), dezembro, verao e Sao Joao;
    --    menos: meio de semana, julho/agosto e feriados de fim de ano.
    -- ==========================================================================================
    CREATE TEMP TABLE tmp_day (
        d      date PRIMARY KEY,
        w      double precision NOT NULL,
        cum    double precision
    ) ON COMMIT DROP;

    INSERT INTO tmp_day (d, w)
    SELECT g::date,
           (CASE extract(isodow FROM g)::int
                 WHEN 1 THEN 0.85 WHEN 2 THEN 0.80 WHEN 3 THEN 0.85 WHEN 4 THEN 0.95
                 WHEN 5 THEN 1.25 WHEN 6 THEN 1.55 ELSE 1.00 END)
           * (CASE WHEN extract(day FROM g) BETWEEN 5 AND 10 THEN 1.30
                   WHEN extract(day FROM g) BETWEEN 28 AND 31 THEN 1.10 ELSE 1.00 END)
           * (CASE extract(month FROM g)::int
                 WHEN 12 THEN 1.45 WHEN 1 THEN 1.05 WHEN 2 THEN 1.15 WHEN 6 THEN 1.20
                 WHEN 7 THEN 0.95 WHEN 8 THEN 0.90 ELSE 1.00 END)
           * (CASE WHEN extract(month FROM g) = 12 AND extract(day FROM g) BETWEEN 22 AND 24 THEN 1.8
                   WHEN extract(month FROM g) = 12 AND extract(day FROM g) = 25 THEN 0.1
                   WHEN extract(month FROM g) = 1  AND extract(day FROM g) = 1  THEN 0.1
                   ELSE 1.0 END)
           * (0.9 + random() * 0.2)
      FROM generate_series(v_start::timestamp, v_end::timestamp, interval '1 day') g;

    UPDATE tmp_day t SET cum = x.cum
      FROM (SELECT d, sum(w) OVER (ORDER BY d) AS cum FROM tmp_day) x
     WHERE x.d = t.d;
    SELECT max(cum) INTO v_total_w FROM tmp_day;

    -- Cada venda ganha um dia (sorteio ponderado) e um horario (picos de manha, almoco e fim de tarde).
    CREATE TEMP TABLE tmp_slot (
        n        int PRIMARY KEY,
        ts       timestamptz NOT NULL
    ) ON COMMIT DROP;

    FOR v_i IN 1..(c_sales - c_open) LOOP
        v_u := random() * v_total_w;
        SELECT d INTO v_day FROM tmp_day WHERE cum >= v_u ORDER BY cum LIMIT 1;
        -- horario de funcionamento: 07h as 22h
        v_u := random();
        v_ts := (v_day + time '00:00'
                 + make_interval(secs => (CASE
                       WHEN v_u < 0.25 THEN least(greatest(pg_temp.fn_normal() * 1.1 + 9.5, 7.0), 21.9)
                       WHEN v_u < 0.55 THEN least(greatest(pg_temp.fn_normal() * 1.2 + 12.5, 7.0), 21.9)
                       ELSE                 least(greatest(pg_temp.fn_normal() * 1.8 + 18.0, 7.0), 21.9)
                  END) * 3600))::timestamp AT TIME ZONE c_tz;
        INSERT INTO tmp_slot (n, ts) VALUES (v_i, date_trunc('second', v_ts));
    END LOOP;

    -- ==========================================================================================
    -- 9) Planejamento das vendas: status, cliente e cesta de cada uma (independe do estoque).
    --    A reposicao (secao 10) olha essas cestas para comprar so o que vai ser vendido.
    -- ==========================================================================================
    -- Pool de clientes recorrentes (CPF na nota) e de empresas (CNPJ).
    CREATE TEMP TABLE tmp_client (n int PRIMARY KEY, doc text NOT NULL) ON COMMIT DROP;
    INSERT INTO tmp_client
    SELECT g, pg_temp.fn_cpf((g::bigint * 48271 + 123456789) % 1000000000) FROM generate_series(1, 350) g;
    INSERT INTO tmp_client
    SELECT 350 + g, pg_temp.fn_cnpj((g::bigint * 91238177 + 55512345) % 100000000) FROM generate_series(1, 12) g;

    CREATE TEMP TABLE tmp_plan (
        n        int PRIMARY KEY,
        ts       timestamptz NOT NULL,
        store_n  int NOT NULL,
        status   text NOT NULL,
        client   text
    ) ON COMMIT DROP;

    CREATE TEMP TABLE tmp_basket (
        sale_n     int NOT NULL,
        store_n    int NOT NULL,
        product_id bigint NOT NULL,
        sale_ts    timestamptz NOT NULL,
        completed  boolean NOT NULL,
        qty        numeric(12,3) NOT NULL,
        unit_price bigint NOT NULL,
        PRIMARY KEY (sale_n, product_id)
    ) ON COMMIT DROP;
    CREATE INDEX ix_tmp_basket_demand ON tmp_basket (store_n, product_id, sale_ts) WHERE completed;

    FOR slot IN SELECT n, ts FROM tmp_slot ORDER BY n LOOP
        -- loja: sorteio ponderado pela fatia de cada uma (as fatias somam 1)
        v_u := random();
        SELECT x.n INTO v_store_n
          FROM (SELECT n, sum(share) OVER (ORDER BY n) AS cum FROM tmp_store) x
         WHERE x.cum > v_u
         ORDER BY x.n LIMIT 1;

        -- status: a maioria concluida; algumas canceladas
        v_status := CASE WHEN random() < 0.955 THEN 'COMPLETED' ELSE 'CANCELED' END;

        -- cliente com CPF/CNPJ na nota (~30% das vendas; clientes frequentes repetem)
        v_client := NULL;
        IF random() < 0.30 THEN
            -- sorteia o cliente ANTES da consulta (random() dentro do WHERE seria reavaliado por linha)
            v_n := CASE WHEN random() < 0.04 THEN 351 + floor(random() * 12)::int
                        ELSE 1 + floor(power(random(), 1.7) * 350)::int END;
            SELECT doc INTO v_client FROM tmp_client WHERE n = v_n;
        END IF;
        INSERT INTO tmp_plan (n, ts, store_n, status, client) VALUES (slot.n, slot.ts, v_store_n, v_status, v_client);

        -- numero de itens distintos: lognormal (mediana ~5, cauda ate ~30)
        v_nitems := greatest(1, least(30, round(exp(1.65 + 0.65 * pg_temp.fn_normal()))::int));

        FOR r IN
            SELECT p.id, p.unit, p.cat_idx, p.qty_k
              FROM tmp_pick p
             ORDER BY -ln(1 - random()) / (p.weight
                      * CASE WHEN extract(month FROM slot.ts AT TIME ZONE c_tz) IN (12, 1, 2) AND p.cat_idx IN (9, 18, 8) THEN 1.8
                             WHEN extract(month FROM slot.ts AT TIME ZONE c_tz) = 6 AND p.name IN ('Milho de Pipoca 500g', 'Fubá Mimoso 500g', 'Paçoca Rolha 20un', 'Amendoim Japonês 500g') THEN 3.0
                             WHEN extract(month FROM slot.ts AT TIME ZONE c_tz) = 12 AND p.cat_idx IN (10, 18) THEN 1.8
                             ELSE 1 END)
             LIMIT v_nitems
        LOOP
            v_qty := CASE r.unit
                WHEN 'UNIT' THEN 1 + floor(-ln(1 - random()) * r.qty_k)
                WHEN 'KG' THEN round((least(0.12 - ln(1 - random()) * 0.65, 4.0))::numeric, 3)
                WHEN 'LITER' THEN round((1 + random() * 4)::numeric, 3)
                WHEN 'GRAM' THEN round((100 + random() * 400)::numeric, 0)
                ELSE round((100 + random() * 900)::numeric, 0)
            END;
            IF r.unit = 'UNIT' AND r.cat_idx IN (9, 18) AND random() < 0.12 THEN
                v_qty := v_qty + 5;                         -- fardo / caixa de bebida
            END IF;
            -- preco vigente no momento da venda
            v_price := (SELECT cents FROM tmp_price
                         WHERE product_id = r.id AND valid_from <= slot.ts
                         ORDER BY valid_from DESC LIMIT 1);
            INSERT INTO tmp_basket (sale_n, store_n, product_id, sale_ts, completed, qty, unit_price)
            VALUES (slot.n, v_store_n, r.id, slot.ts, v_status = 'COMPLETED', v_qty, v_price);
        END LOOP;
    END LOOP;

    -- ==========================================================================================
    -- 10) Simulacao cronologica: entregas, vendas e perdas (tudo em ordem de data/hora)
    --     Ordem dentro do dia (ids crescem com o horario): entregas e baixas 05h, vendas 07h-22h,
    --     quebras/avarias depois do fechamento.
    -- ==========================================================================================
    FOR v_day IN SELECT g::date FROM generate_series((v_open + 1)::timestamp, v_end::timestamp, interval '1 day') g LOOP

        -- 10.1) Entregas do dia (seg-sab), loja por loja: para cada produto cujo fornecedor entrega hoje,
        --       compra o que as vendas da loja na janela seguinte vao consumir (+10%), descontando o saldo
        --       dela. Perecíveis tem duas entregas por semana (janela de 4 dias), os demais uma (janela de 7 dias).
        --       Antes, baixa por vencimento do que sobrou de um lote vencido.
        v_k := 0;
        IF extract(isodow FROM v_day)::int BETWEEN 1 AND 6 THEN
          FOR shop IN SELECT n, id, share FROM tmp_store ORDER BY n LOOP
            FOR r IN
                SELECT p.id, p.n, sp.pack, p.sup_main, p.sup_alt, sp.batch_expiry, p.no_restock_after,
                       c.shelf_min, c.shelf_max, c.cost_ratio,
                       COALESCE(i.total_quantity, 0) AS stock,
                       (SELECT COALESCE(sum(b.qty), 0)
                          FROM tmp_basket b
                         WHERE b.store_n = shop.n AND b.product_id = p.id AND b.completed
                           AND b.sale_ts >= v_day::timestamp AT TIME ZONE c_tz
                           AND b.sale_ts <  (v_day + CASE WHEN p.perishable THEN 4 ELSE 7 END)::timestamp AT TIME ZONE c_tz
                       )
                       -- parte da janela que cai depois de "ontem": nao ha venda planejada, usa a demanda
                       -- esperada da loja (senao o estoque ficaria zerado justamente na foto final do banco)
                       + p.daily_units * shop.share
                         * greatest(0, (v_day + CASE WHEN p.perishable THEN 4 ELSE 7 END) - (v_end + 1))
                       AS need
                  FROM tmp_prod p
                  JOIN tmp_cat c ON c.idx = p.cat_idx
                  JOIN tmp_store_prod sp ON sp.store_n = shop.n AND sp.n = p.n
                  LEFT JOIN inventory i ON i.store_id = shop.id AND i.product_id = p.id
                 WHERE (p.review_dow = extract(isodow FROM v_day)::int
                        OR p.review_dow2 = extract(isodow FROM v_day)::int)
                   AND (p.no_restock_after IS NULL OR v_day <= p.no_restock_after)
                 ORDER BY p.n
            LOOP
                v_stock := r.stock;
                IF r.batch_expiry < v_day AND v_stock > 0 THEN
                    v_k := v_k + 1;
                    INSERT INTO inventory_movement (store_id, product_id, type, quantity, created_at)
                    VALUES (shop.id, r.id, 'LOSS', v_stock,
                            (v_day + time '05:00' + v_k * interval '5 seconds')::timestamp AT TIME ZONE c_tz);
                    v_loss_count := v_loss_count + 1;
                    v_stock := 0;
                    UPDATE tmp_store_prod SET batch_expiry = NULL WHERE store_n = shop.n AND n = r.n;
                END IF;

                CONTINUE WHEN r.need <= v_stock;
                v_order_qty := ceil((r.need * 1.10 - v_stock) / r.pack) * r.pack;

                v_supplier := CASE WHEN random() < 0.8 THEN r.sup_main ELSE r.sup_alt END;
                v_price := (SELECT cents FROM tmp_price
                             WHERE product_id = r.id
                               AND valid_from <= (v_day + time '12:00')::timestamp AT TIME ZONE c_tz
                             ORDER BY valid_from DESC LIMIT 1);
                v_cost := greatest(round(v_price * r.cost_ratio * (0.95 + random() * 0.10)), 1);
                v_expires := CASE WHEN r.shelf_min IS NULL THEN NULL
                                  ELSE v_day + (r.shelf_min + floor(random() * (r.shelf_max - r.shelf_min + 1))::int) END;
                v_k := v_k + 1;
                INSERT INTO inventory_movement (store_id, product_id, supplier_id, type, quantity, unit_cost_in_cents,
                                                batch_expires_at, created_at)
                VALUES (shop.id, r.id, v_supplier, 'PURCHASE', v_order_qty, v_cost, v_expires,
                        (v_day + time '05:00' + v_k * interval '5 seconds')::timestamp AT TIME ZONE c_tz);
                UPDATE tmp_store_prod SET batch_expiry = v_expires WHERE store_n = shop.n AND n = r.n;
                v_buy_count := v_buy_count + 1;
            END LOOP;
          END LOOP;
        END IF;

        -- 10.2) Vendas do dia de todas as lojas, em ordem de horario (os ids da rede seguem o tempo)
        FOR slot IN SELECT pl.n, pl.ts, pl.status, pl.client, pl.store_n, s.id AS store_id
                      FROM tmp_plan pl
                      JOIN tmp_store s ON s.n = pl.store_n
                     WHERE pl.ts >= v_day::timestamp AT TIME ZONE c_tz
                       AND pl.ts <  (v_day + 1)::timestamp AT TIME ZONE c_tz
                     ORDER BY pl.ts, pl.n
        LOOP
            v_status := slot.status;

            -- vendedor: um dos caixas da loja em atividade na data (o trigger da venda confere a loja)
            SELECT array_agg(id ORDER BY n) INTO v_sellers
              FROM tmp_staff
             WHERE store_n = slot.store_n AND role = 'CASHIER'
               AND hired_at <= v_day AND (left_at IS NULL OR left_at > v_day);
            v_seller := v_sellers[1 + floor(random() * array_length(v_sellers, 1))::int];

            INSERT INTO sale (uuid, store_id, seller_id, status, total_amount_in_cents, client_tax_number,
                              created_at, updated_at)
            VALUES (md5('sale:' || slot.n)::uuid, slot.store_id, v_seller, v_status, 0, slot.client, slot.ts, slot.ts)
            RETURNING id INTO v_sale_id;

            v_total := 0;

            FOR r IN
                SELECT b.product_id, b.qty, b.unit_price, p.unit, COALESCE(i.total_quantity, 0) AS stock
                  FROM tmp_basket b
                  JOIN tmp_prod p ON p.id = b.product_id
                  LEFT JOIN inventory i ON i.store_id = slot.store_id AND i.product_id = b.product_id
                 WHERE b.sale_n = slot.n
                 ORDER BY p.n
            LOOP
                v_qty := r.qty;
                -- rede de seguranca: nunca vende mais do que existe (o CHECK do estoque derrubaria o seed)
                IF v_status = 'COMPLETED' THEN
                    IF v_qty > r.stock THEN
                        v_qty := CASE WHEN r.unit = 'UNIT' THEN floor(r.stock) ELSE r.stock END;
                    END IF;
                    CONTINUE WHEN v_qty < CASE r.unit WHEN 'UNIT' THEN 1 WHEN 'KG' THEN 0.1
                                                       WHEN 'LITER' THEN 0.5 ELSE 50 END;
                END IF;

                INSERT INTO sale_items (product_id, sale_id, quantity, unit_price_in_cents)
                VALUES (r.product_id, v_sale_id, v_qty, r.unit_price);
                v_total := v_total + round(v_qty * r.unit_price);

                IF v_status = 'COMPLETED' THEN
                    INSERT INTO inventory_movement (store_id, product_id, sale_id, type, quantity, created_at)
                    VALUES (slot.store_id, r.product_id, v_sale_id, 'SALE', v_qty, slot.ts);
                END IF;
            END LOOP;

            -- venda sem nenhum item vendavel (tudo em falta): vira cancelada com um item simbolico
            IF v_total = 0 THEN
                v_status := 'CANCELED';
                SELECT p.id INTO v_pid FROM tmp_prod p ORDER BY p.weight DESC LIMIT 1;
                v_price := (SELECT cents FROM tmp_price WHERE product_id = v_pid AND valid_from <= slot.ts
                             ORDER BY valid_from DESC LIMIT 1);
                INSERT INTO sale_items (product_id, sale_id, quantity, unit_price_in_cents)
                VALUES (v_pid, v_sale_id, 1, v_price);
                v_total := v_price;
            END IF;

            -- pagamentos ---------------------------------------------------------------------
            v_pay_ts := slot.ts + (60 + floor(random() * 240)) * interval '1 second';
            -- meio de pagamento depende do valor do cupom (em centavos)
            v_u := random();
            v_method := CASE
                WHEN v_total < 4000  THEN CASE WHEN v_u < 0.30 THEN 'CASH' WHEN v_u < 0.62 THEN 'PIX'
                                                 WHEN v_u < 0.85 THEN 'DEBIT_CARD' ELSE 'CREDIT_CARD' END
                WHEN v_total < 20000 THEN CASE WHEN v_u < 0.13 THEN 'CASH' WHEN v_u < 0.45 THEN 'PIX'
                                                 WHEN v_u < 0.72 THEN 'DEBIT_CARD' ELSE 'CREDIT_CARD' END
                ELSE                      CASE WHEN v_u < 0.05 THEN 'CASH' WHEN v_u < 0.30 THEN 'PIX'
                                                 WHEN v_u < 0.48 THEN 'DEBIT_CARD' ELSE 'CREDIT_CARD' END
            END;

            IF v_status = 'COMPLETED' THEN
                -- ~2%: cartao recusado antes de pagar por outro meio (FAILED + PAID)
                IF v_method IN ('DEBIT_CARD', 'CREDIT_CARD') AND random() < 0.06 THEN
                    INSERT INTO payment (uuid, sale_id, method, amount_in_cents, status, paid_at, created_at, updated_at)
                    VALUES (md5('payment:' || slot.n || ':f')::uuid, v_sale_id, v_method, v_total, 'FAILED',
                            NULL, slot.ts + interval '40 seconds', slot.ts + interval '45 seconds');
                    v_method := CASE WHEN random() < 0.6 THEN 'PIX' ELSE 'CASH' END;
                    v_pay_ts := v_pay_ts + interval '90 seconds';
                END IF;

                -- ~10% dos cupons acima de R$ 60: pagamento dividido (dinheiro/pix + cartao)
                IF v_total >= 6000 AND random() < 0.10 THEN
                    v_split  := 0.25 + random() * 0.5;
                    v_part   := round(v_total * v_split);
                    v_method2 := CASE WHEN v_method IN ('CASH', 'PIX') THEN 'CREDIT_CARD' ELSE 'CASH' END;
                    INSERT INTO payment (uuid, sale_id, method, amount_in_cents, status, paid_at, created_at, updated_at)
                    VALUES (md5('payment:' || slot.n || ':a')::uuid, v_sale_id, v_method, v_part, 'PAID',
                            v_pay_ts - interval '30 seconds', slot.ts + interval '30 seconds', v_pay_ts - interval '30 seconds'),
                           (md5('payment:' || slot.n || ':b')::uuid, v_sale_id, v_method2, v_total - v_part, 'PAID',
                            v_pay_ts, slot.ts + interval '30 seconds', v_pay_ts);
                ELSE
                    INSERT INTO payment (uuid, sale_id, method, amount_in_cents, status, paid_at, created_at, updated_at)
                    VALUES (md5('payment:' || slot.n || ':a')::uuid, v_sale_id, v_method, v_total, 'PAID',
                            v_pay_ts, slot.ts + interval '30 seconds', v_pay_ts);
                END IF;

                UPDATE sale SET total_amount_in_cents = v_total, updated_at = v_pay_ts WHERE id = v_sale_id;
            ELSE
                -- Cancelada: metade foi paga e estornada; o resto nao chegou a ser paga
                -- (cartao recusado ou nenhum pagamento registrado).
                v_u := random();
                IF v_u < 0.50 THEN
                    INSERT INTO payment (uuid, sale_id, method, amount_in_cents, status, paid_at, created_at, updated_at)
                    VALUES (md5('payment:' || slot.n || ':a')::uuid, v_sale_id, v_method, v_total, 'REFUNDED',
                            v_pay_ts, slot.ts + interval '30 seconds', v_pay_ts + interval '12 minutes');
                ELSIF v_u < 0.75 THEN
                    INSERT INTO payment (uuid, sale_id, method, amount_in_cents, status, paid_at, created_at, updated_at)
                    VALUES (md5('payment:' || slot.n || ':a')::uuid, v_sale_id,
                            CASE WHEN v_method = 'CASH' THEN 'DEBIT_CARD' ELSE v_method END,
                            v_total, 'FAILED', NULL, slot.ts + interval '30 seconds', slot.ts + interval '50 seconds');
                END IF;
                UPDATE sale SET total_amount_in_cents = v_total, status = 'CANCELED',
                                updated_at = v_pay_ts + interval '12 minutes'
                 WHERE id = v_sale_id;
            END IF;

            v_sale_count := v_sale_count + 1;
        END LOOP;

        -- 10.3) Quebras e avarias (depois do fechamento): chance diaria por produto em cada loja, no maximo uma
        v_k := 0;
        FOR shop IN SELECT n, id FROM tmp_store ORDER BY n LOOP
            FOR r IN SELECT p.id, p.unit, p.loss_prob FROM tmp_prod p ORDER BY p.n LOOP
                -- o sorteio fica no laco (ordem fixa por loja e produto): random() dentro de um JOIN/WHERE seria
                -- avaliado na ordem que o otimizador escolher, e o seed deixaria de ser reproduzivel
                CONTINUE WHEN random() >= r.loss_prob;
                SELECT total_quantity INTO v_stock FROM inventory WHERE store_id = shop.id AND product_id = r.id;
                CONTINUE WHEN v_stock IS NULL OR v_stock < 1;
                v_qty := CASE WHEN r.unit = 'UNIT'  THEN least(v_stock, 1 + floor(random() * 2))
                              WHEN r.unit IN ('KG', 'LITER') THEN least(v_stock, round((0.2 + random() * 0.8)::numeric, 3))
                              ELSE least(v_stock, round((50 + random() * 250)::numeric, 3)) END;
                CONTINUE WHEN v_qty <= 0;
                v_k := v_k + 1;
                INSERT INTO inventory_movement (store_id, product_id, type, quantity, created_at)
                VALUES (shop.id, r.id, 'LOSS', v_qty,
                        (v_day + time '22:05' + v_k * interval '15 seconds')::timestamp AT TIME ZONE c_tz);
                v_loss_count := v_loss_count + 1;
            END LOOP;
        END LOOP;
    END LOOP;

    -- ==========================================================================================
    -- 11) Vendas em andamento (OPEN): carrinhos abertos agora, sem pagamento nem baixa de estoque.
    --     As lojas se revezam (com 3 vendas e 3 lojas, uma em cada).
    -- ==========================================================================================
    FOR v_i IN REVERSE c_open..1 LOOP                   -- do mais antigo para o mais recente (ids em ordem do tempo)
        v_store_n := 1 + (v_i - 1) % (SELECT count(*) FROM tmp_store)::int;
        SELECT id INTO v_store_id FROM tmp_store WHERE n = v_store_n;
        SELECT array_agg(id ORDER BY n) INTO v_sellers
          FROM tmp_staff
         WHERE store_n = v_store_n AND role = 'CASHIER' AND left_at IS NULL AND hired_at <= current_date;
        v_seller := v_sellers[1 + floor(random() * array_length(v_sellers, 1))::int];
        v_ts := now() - (v_i * 7 + floor(random() * 6)) * interval '1 minute';
        INSERT INTO sale (uuid, store_id, seller_id, status, total_amount_in_cents, client_tax_number, created_at, updated_at)
        VALUES (md5('sale:open:' || v_i)::uuid, v_store_id, v_seller, 'OPEN', 0, NULL, v_ts, v_ts)
        RETURNING id INTO v_sale_id;
        v_total := 0;
        FOR r IN SELECT p.id, p.unit FROM tmp_pick p
                  ORDER BY -ln(1 - random()) / p.weight LIMIT (2 + floor(random() * 5))::int
        LOOP
            v_qty := CASE WHEN r.unit = 'UNIT' THEN 1 + floor(random() * 2)
                          WHEN r.unit = 'KG' THEN round((0.3 + random() * 1.2)::numeric, 3)
                          WHEN r.unit = 'LITER' THEN 2
                          ELSE 200 END;
            v_price := (SELECT cents FROM tmp_price WHERE product_id = r.id AND valid_to IS NULL);
            INSERT INTO sale_items (product_id, sale_id, quantity, unit_price_in_cents)
            VALUES (r.id, v_sale_id, v_qty, v_price);
            v_total := v_total + round(v_qty * v_price);
        END LOOP;
        UPDATE sale SET total_amount_in_cents = v_total WHERE id = v_sale_id;
        v_sale_count := v_sale_count + 1;
    END LOOP;

    -- ==========================================================================================
    -- 12) Resumo
    -- ==========================================================================================
    RAISE NOTICE 'seed concluido:';
    RAISE NOTICE '  categorias=% produtos=% fornecedores=% lojas=% funcionarios=%',
        (SELECT count(*) FROM category), (SELECT count(*) FROM product),
        (SELECT count(*) FROM supplier), (SELECT count(*) FROM store), (SELECT count(*) FROM staff);
    RAISE NOTICE '  vendas=% itens=% pagamentos=% precos=%',
        (SELECT count(*) FROM sale), (SELECT count(*) FROM sale_items),
        (SELECT count(*) FROM payment), (SELECT count(*) FROM price);
    RAISE NOTICE '  vendas por loja: %',
        (SELECT string_agg(st.name || '=' || x.total, ', ' ORDER BY st.id)
           FROM (SELECT store_id, count(*) AS total FROM sale GROUP BY store_id) x
           JOIN store st ON st.id = x.store_id);
    RAISE NOTICE '  movimentacoes: compras=% perdas=% vendas=% | linhas em inventory=%',
        (SELECT count(*) FROM inventory_movement WHERE type = 'PURCHASE'),
        (SELECT count(*) FROM inventory_movement WHERE type = 'LOSS'),
        (SELECT count(*) FROM inventory_movement WHERE type = 'SALE'),
        (SELECT count(*) FROM inventory);
END
$seed$;;
