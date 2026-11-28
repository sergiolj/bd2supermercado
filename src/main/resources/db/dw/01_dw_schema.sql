-- ============================================================================
-- DATA WAREHOUSE - SUPERMERCADO (BD2)
-- Arquivo: 01_dw_schema.sql
-- Descrição: Cria os schemas stg, etl, dw_estrela (Star Schema) e dw_floco (Snowflake)
-- ============================================================================

CREATE SCHEMA IF NOT EXISTS stg;;
CREATE SCHEMA IF NOT EXISTS etl;;
CREATE SCHEMA IF NOT EXISTS dw_estrela;;
CREATE SCHEMA IF NOT EXISTS dw_floco;;

-- ============================================================================
-- 1. CONTROLE DE EXECUÇÃO DO ETL
-- ============================================================================
CREATE TABLE IF NOT EXISTS etl.log_execucao (
                                                id_execucao     SERIAL PRIMARY KEY,
                                                data_inicio     TIMESTAMP NOT NULL DEFAULT NOW(),
    data_fim        TIMESTAMP,
    modo_completo   BOOLEAN NOT NULL,
    fatos_inseridos INTEGER DEFAULT 0,
    status          VARCHAR(20) NOT NULL DEFAULT 'EM_ANDAMENTO',
    mensagem        TEXT
    );;

-- ============================================================================
-- 2. ÁREA DE STAGING (Extraída e padronizada do schema public)
-- ============================================================================
DROP TABLE IF EXISTS stg.venda_item_stg;;
CREATE TABLE stg.venda_item_stg (
                                    sale_id              BIGINT NOT NULL,
                                    sale_uuid            UUID NOT NULL,
                                    sale_item_id         BIGINT NOT NULL,
                                    data_venda           DATE NOT NULL,
                                    product_uuid         UUID NOT NULL,
                                    product_barcode      VARCHAR(100),
                                    product_name         VARCHAR(255),
                                    product_unit         VARCHAR(50),
                                    category_uuid        UUID NOT NULL,
                                    category_name        VARCHAR(255),
                                    customer_uuid        UUID,
                                    customer_name        VARCHAR(255),
                                    customer_city        VARCHAR(120),
                                    customer_state       CHAR(2),
                                    store_uuid           UUID NOT NULL,
                                    store_name           VARCHAR(255),
                                    store_city           VARCHAR(120),
                                    store_state          CHAR(2),
                                    seller_uuid          UUID NOT NULL,
                                    seller_name          VARCHAR(255),
                                    seller_role          VARCHAR(50),
                                    quantidade           NUMERIC(12,3) NOT NULL,
                                    preco_unitario       NUMERIC(12,2) NOT NULL,
                                    valor_bruto          NUMERIC(12,2) NOT NULL,
                                    desconto             NUMERIC(12,2) NOT NULL,
                                    valor_liquido        NUMERIC(12,2) NOT NULL
);;

-- ============================================================================
-- 3. MODELO ESTRELA (dw_estrela)
-- Granularidade: 1 linha por item vendido (sale_items) de venda concluída (COMPLETED)
-- ============================================================================

CREATE TABLE IF NOT EXISTS dw_estrela.dim_tempo (
                                                    sk_tempo        INTEGER PRIMARY KEY, -- Formato AAAAMMDD (ex: 20261127)
                                                    data_completa   DATE NOT NULL UNIQUE,
                                                    dia             SMALLINT NOT NULL,
                                                    mes             SMALLINT NOT NULL,
                                                    nome_mes        VARCHAR(20) NOT NULL,
    trimestre       SMALLINT NOT NULL,
    semestre        SMALLINT NOT NULL,
    ano             SMALLINT NOT NULL,
    dia_semana      SMALLINT NOT NULL,
    nome_dia_semana VARCHAR(20) NOT NULL,
    fim_de_semana   BOOLEAN NOT NULL
    );;

CREATE TABLE IF NOT EXISTS dw_estrela.dim_produto (
                                                      sk_produto      SERIAL PRIMARY KEY,
                                                      produto_uuid    UUID NOT NULL UNIQUE,
                                                      codigo_barras   VARCHAR(100) NOT NULL,
    nome_produto    VARCHAR(255) NOT NULL,
    unidade_medida  VARCHAR(50) NOT NULL,
    categoria       VARCHAR(255) NOT NULL
    );;

CREATE TABLE IF NOT EXISTS dw_estrela.dim_cliente (
                                                      sk_cliente      SERIAL PRIMARY KEY,
                                                      cliente_uuid    UUID, -- NULL apenas para o registro 0 (Não identificado)
                                                      nome_cliente    VARCHAR(255) NOT NULL,
    cidade          VARCHAR(120) NOT NULL,
    uf              CHAR(2) NOT NULL,
    -- Colunas de controle SCD Tipo 2 (histórico de endereço do cliente)
    data_inicio     DATE NOT NULL,
    data_fim        DATE,
    atual           BOOLEAN NOT NULL DEFAULT TRUE
    );;

-- Membro fixo sk_cliente = 0 para vendas anônimas (sem cliente vinculado)
INSERT INTO dw_estrela.dim_cliente (sk_cliente, cliente_uuid, nome_cliente, cidade, uf, data_inicio, data_fim, atual)
VALUES (0, NULL, 'Cliente Não Identificado', 'Não informado', 'NI', '1900-01-01', NULL, TRUE)
    ON CONFLICT (sk_cliente) DO NOTHING;;

CREATE TABLE IF NOT EXISTS dw_estrela.dim_loja (
                                                   sk_loja         SERIAL PRIMARY KEY,
                                                   loja_uuid       UUID NOT NULL UNIQUE,
                                                   nome_loja       VARCHAR(255) NOT NULL,
    cidade          VARCHAR(120) NOT NULL,
    uf              CHAR(2) NOT NULL
    );;

CREATE TABLE IF NOT EXISTS dw_estrela.dim_funcionario (
                                                          sk_funcionario   SERIAL PRIMARY KEY,
                                                          funcionario_uuid UUID NOT NULL UNIQUE,
                                                          nome_funcionario VARCHAR(255) NOT NULL,
    cargo            VARCHAR(50) NOT NULL
    );;

CREATE TABLE IF NOT EXISTS dw_estrela.fato_venda (
                                                     venda_uuid      UUID NOT NULL,          -- Dimensão degenerada (cupom/venda)
                                                     id_item_origem  BIGINT NOT NULL,        -- Chave natural do item para carga incremental
                                                     sk_tempo        INTEGER NOT NULL REFERENCES dw_estrela.dim_tempo(sk_tempo),
    sk_produto      INTEGER NOT NULL REFERENCES dw_estrela.dim_produto(sk_produto),
    sk_cliente      INTEGER NOT NULL REFERENCES dw_estrela.dim_cliente(sk_cliente),
    sk_loja         INTEGER NOT NULL REFERENCES dw_estrela.dim_loja(sk_loja),
    sk_funcionario  INTEGER NOT NULL REFERENCES dw_estrela.dim_funcionario(sk_funcionario),
    quantidade      NUMERIC(12,3) NOT NULL,
    preco_unitario  NUMERIC(12,2) NOT NULL,
    valor_bruto     NUMERIC(12,2) NOT NULL,
    desconto        NUMERIC(12,2) NOT NULL,
    valor_liquido   NUMERIC(12,2) NOT NULL,
    PRIMARY KEY (venda_uuid, id_item_origem)
    );;

CREATE INDEX IF NOT EXISTS idx_estrela_fato_tempo   ON dw_estrela.fato_venda(sk_tempo);;
CREATE INDEX IF NOT EXISTS idx_estrela_fato_produto ON dw_estrela.fato_venda(sk_produto);;
CREATE INDEX IF NOT EXISTS idx_estrela_fato_cliente ON dw_estrela.fato_venda(sk_cliente);;
CREATE INDEX IF NOT EXISTS idx_estrela_fato_loja    ON dw_estrela.fato_venda(sk_loja);;

-- ============================================================================
-- 4. MODELO FLOCO DE NEVE (dw_floco)
-- Normaliza hierarquias: Produto -> Categoria e Cliente/Loja -> Cidade -> Estado
-- ============================================================================

CREATE TABLE IF NOT EXISTS dw_floco.dim_tempo (
                                                  sk_tempo        INTEGER PRIMARY KEY,
                                                  data_completa   DATE NOT NULL UNIQUE,
                                                  dia             SMALLINT NOT NULL,
                                                  mes             SMALLINT NOT NULL,
                                                  nome_mes        VARCHAR(20) NOT NULL,
    trimestre       SMALLINT NOT NULL,
    semestre        SMALLINT NOT NULL,
    ano             SMALLINT NOT NULL,
    dia_semana      SMALLINT NOT NULL,
    nome_dia_semana VARCHAR(20) NOT NULL,
    fim_de_semana   BOOLEAN NOT NULL
    );;

CREATE TABLE IF NOT EXISTS dw_floco.dim_categoria (
                                                      sk_categoria    SERIAL PRIMARY KEY,
                                                      nome_categoria  VARCHAR(255) NOT NULL UNIQUE
    );;

CREATE TABLE IF NOT EXISTS dw_floco.dim_produto (
                                                    sk_produto      INTEGER PRIMARY KEY,
                                                    produto_uuid    UUID NOT NULL UNIQUE,
                                                    codigo_barras   VARCHAR(100) NOT NULL,
    nome_produto    VARCHAR(255) NOT NULL,
    unidade_medida  VARCHAR(50) NOT NULL,
    sk_categoria    INTEGER NOT NULL REFERENCES dw_floco.dim_categoria(sk_categoria)
    );;

CREATE TABLE IF NOT EXISTS dw_floco.dim_estado (
                                                   sk_estado       SERIAL PRIMARY KEY,
                                                   uf              CHAR(2) NOT NULL UNIQUE
    );;

CREATE TABLE IF NOT EXISTS dw_floco.dim_cidade (
                                                   sk_cidade       SERIAL PRIMARY KEY,
                                                   nome_cidade     VARCHAR(120) NOT NULL,
    sk_estado       INTEGER NOT NULL REFERENCES dw_floco.dim_estado(sk_estado),
    UNIQUE (nome_cidade, sk_estado)
    );;

CREATE TABLE IF NOT EXISTS dw_floco.dim_cliente (
                                                    sk_cliente      INTEGER PRIMARY KEY,
                                                    cliente_uuid    UUID,
                                                    nome_cliente    VARCHAR(255) NOT NULL,
    sk_cidade       INTEGER NOT NULL REFERENCES dw_floco.dim_cidade(sk_cidade),
    data_inicio     DATE NOT NULL,
    data_fim        DATE,
    atual           BOOLEAN NOT NULL DEFAULT TRUE
    );;

CREATE TABLE IF NOT EXISTS dw_floco.dim_loja (
                                                 sk_loja         INTEGER PRIMARY KEY,
                                                 loja_uuid       UUID NOT NULL UNIQUE,
                                                 nome_loja       VARCHAR(255) NOT NULL,
    sk_cidade       INTEGER NOT NULL REFERENCES dw_floco.dim_cidade(sk_cidade)
    );;

CREATE TABLE IF NOT EXISTS dw_floco.dim_funcionario (
                                                        sk_funcionario   INTEGER PRIMARY KEY,
                                                        funcionario_uuid UUID NOT NULL UNIQUE,
                                                        nome_funcionario VARCHAR(255) NOT NULL,
    cargo            VARCHAR(50) NOT NULL
    );;

CREATE TABLE IF NOT EXISTS dw_floco.fato_venda (
                                                   venda_uuid      UUID NOT NULL,
                                                   id_item_origem  BIGINT NOT NULL,
                                                   sk_tempo        INTEGER NOT NULL REFERENCES dw_floco.dim_tempo(sk_tempo),
    sk_produto      INTEGER NOT NULL REFERENCES dw_floco.dim_produto(sk_produto),
    sk_cliente      INTEGER NOT NULL REFERENCES dw_floco.dim_cliente(sk_cliente),
    sk_loja         INTEGER NOT NULL REFERENCES dw_floco.dim_loja(sk_loja),
    sk_funcionario  INTEGER NOT NULL REFERENCES dw_floco.dim_funcionario(sk_funcionario),
    quantidade      NUMERIC(12,3) NOT NULL,
    preco_unitario  NUMERIC(12,2) NOT NULL,
    valor_bruto     NUMERIC(12,2) NOT NULL,
    desconto        NUMERIC(12,2) NOT NULL,
    valor_liquido   NUMERIC(12,2) NOT NULL,
    PRIMARY KEY (venda_uuid, id_item_origem)
    );;