-- Data Warehouse: schemas e tabelas dos modelos estrela e floco de neve (Ref. §4.3 item 1).
--
-- Executado a cada subida da aplicacao (spring.sql.init.*), depois que o Hibernate criou/atualizou as tabelas do
-- OLTP. Tudo aqui e idempotente: so cria o que falta, nunca apaga dados. A carga e feita pelo ETL (02_etl.sql),
-- disparado por POST /api/etl/run.
-- Os comandos sao separados por duas barras de ponto e virgula (spring.sql.init.separator).
--
-- Schemas:
--   public      OLTP (sistema de vendas, mapeado pelo Hibernate)
--   stg         staging: copia transformada do que o ETL extraiu do OLTP, refeita a cada execucao
--   etl         procedures do ETL, tabela de referencia das UFs e log das execucoes
--   dw_estrela  modelo estrela: cada dimensao e uma tabela so
--   dw_floco    floco de neve: produto -> categoria; cliente e loja -> cidade -> estado
--
-- Granularidade dos dois fatos: um item vendido (sale_items) de uma venda concluida (COMPLETED).
-- Medidas: quantidade, valor_bruto (quantidade x preco), desconto e valor_liquido (bruto - desconto), em reais.

CREATE SCHEMA IF NOT EXISTS stg;;
CREATE SCHEMA IF NOT EXISTS etl;;
CREATE SCHEMA IF NOT EXISTS dw_estrela;;
CREATE SCHEMA IF NOT EXISTS dw_floco;;

-- Referencia: UFs com nome e regiao ----------------------------------------------------------------------------
-- O OLTP so guarda a sigla. O ETL usa esta tabela para enriquecer as dimensoes (estado e regiao) e para validar a
-- sigla: UF vazia ou desconhecida vira NI (nao informado).

CREATE TABLE IF NOT EXISTS etl.uf (
    uf     char(2)     PRIMARY KEY,
    nome   varchar(40) NOT NULL,
    regiao varchar(20) NOT NULL
);;

INSERT INTO etl.uf (uf, nome, regiao) VALUES
    ('AC', 'Acre', 'Norte'), ('AL', 'Alagoas', 'Nordeste'), ('AP', 'Amapá', 'Norte'),
    ('AM', 'Amazonas', 'Norte'), ('BA', 'Bahia', 'Nordeste'), ('CE', 'Ceará', 'Nordeste'),
    ('DF', 'Distrito Federal', 'Centro-Oeste'), ('ES', 'Espírito Santo', 'Sudeste'), ('GO', 'Goiás', 'Centro-Oeste'),
    ('MA', 'Maranhão', 'Nordeste'), ('MT', 'Mato Grosso', 'Centro-Oeste'), ('MS', 'Mato Grosso do Sul', 'Centro-Oeste'),
    ('MG', 'Minas Gerais', 'Sudeste'), ('PA', 'Pará', 'Norte'), ('PB', 'Paraíba', 'Nordeste'),
    ('PR', 'Paraná', 'Sul'), ('PE', 'Pernambuco', 'Nordeste'), ('PI', 'Piauí', 'Nordeste'),
    ('RJ', 'Rio de Janeiro', 'Sudeste'), ('RN', 'Rio Grande do Norte', 'Nordeste'), ('RS', 'Rio Grande do Sul', 'Sul'),
    ('RO', 'Rondônia', 'Norte'), ('RR', 'Roraima', 'Norte'), ('SC', 'Santa Catarina', 'Sul'),
    ('SP', 'São Paulo', 'Sudeste'), ('SE', 'Sergipe', 'Nordeste'), ('TO', 'Tocantins', 'Norte'),
    ('NI', 'Não informado', 'Não informado')
ON CONFLICT (uf) DO NOTHING;;

-- Log das execucoes do ETL -------------------------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS etl.execucao (
    id                      bigint      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tipo                    varchar(11) NOT NULL CHECK (tipo IN ('INCREMENTAL', 'COMPLETA')),
    inicio                  timestamptz NOT NULL DEFAULT clock_timestamp(),
    fim                     timestamptz,
    itens_extraidos         integer,
    fatos_inseridos         integer,
    clientes_novas_versoes  integer
);;

-- Staging ------------------------------------------------------------------------------------------------------
-- UNLOGGED: sao tabelas de trabalho, refeitas a cada execucao; nao precisam do log de transacoes (WAL).
-- Ja guardam os dados transformados: textos padronizados, nulos tratados, codigos traduzidos e medidas calculadas.

CREATE UNLOGGED TABLE IF NOT EXISTS stg.produto (
    produto_uuid    uuid         PRIMARY KEY,
    codigo_barras   varchar(50)  NOT NULL,
    nome            varchar(255) NOT NULL,
    unidade_medida  varchar(20)  NOT NULL,
    categoria_uuid  uuid         NOT NULL,
    categoria       varchar(255) NOT NULL,
    ativo           boolean      NOT NULL
);;

CREATE UNLOGGED TABLE IF NOT EXISTS stg.loja (
    loja_uuid  uuid         PRIMARY KEY,
    nome       varchar(255) NOT NULL,
    cidade     varchar(255) NOT NULL,
    uf         char(2)      NOT NULL
);;

CREATE UNLOGGED TABLE IF NOT EXISTS stg.funcionario (
    funcionario_uuid  uuid         PRIMARY KEY,
    nome              varchar(255) NOT NULL,
    cargo             varchar(20)  NOT NULL
);;

CREATE UNLOGGED TABLE IF NOT EXISTS stg.cliente (
    cliente_uuid  uuid         PRIMARY KEY,
    nome          varchar(255) NOT NULL,
    cidade        varchar(255) NOT NULL,
    uf            char(2)      NOT NULL
);;

CREATE UNLOGGED TABLE IF NOT EXISTS stg.venda_item (
    venda_uuid        uuid          NOT NULL,
    id_item_origem    bigint        NOT NULL,
    data_venda        date          NOT NULL,
    produto_uuid      uuid          NOT NULL,
    loja_uuid         uuid          NOT NULL,
    funcionario_uuid  uuid          NOT NULL,
    cliente_uuid      uuid,
    quantidade        numeric(12,3) NOT NULL,
    preco_unitario    numeric(12,2) NOT NULL,
    valor_bruto       numeric(14,2) NOT NULL,
    desconto          numeric(14,2) NOT NULL,
    valor_liquido     numeric(14,2) NOT NULL,
    PRIMARY KEY (venda_uuid, id_item_origem)
);;

-- Modelo estrela -----------------------------------------------------------------------------------------------
-- Chaves substitutas (sk_*) geradas no DW, independentes dos ids do OLTP. A chave natural de cada dimensao e o
-- uuid publico da entidade, que nao muda nem se repete se o OLTP for recriado.

CREATE TABLE IF NOT EXISTS dw_estrela.dim_tempo (
    sk_tempo         integer     PRIMARY KEY,           -- AAAAMMDD
    data             date        NOT NULL UNIQUE,
    dia              smallint    NOT NULL,
    dia_semana       smallint    NOT NULL,              -- 1 = segunda ... 7 = domingo (ISO)
    nome_dia_semana  varchar(15) NOT NULL,
    fim_de_semana    boolean     NOT NULL,
    semana_ano       smallint    NOT NULL,
    mes              smallint    NOT NULL,
    nome_mes         varchar(15) NOT NULL,
    trimestre        smallint    NOT NULL,
    semestre         smallint    NOT NULL,
    ano              smallint    NOT NULL
);;

CREATE TABLE IF NOT EXISTS dw_estrela.dim_produto (
    sk_produto      integer      GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    produto_uuid    uuid         NOT NULL UNIQUE,
    codigo_barras   varchar(50)  NOT NULL,
    nome            varchar(255) NOT NULL,
    unidade_medida  varchar(20)  NOT NULL,
    categoria       varchar(255) NOT NULL,              -- desnormalizada: e o que separa a estrela do floco
    ativo           boolean      NOT NULL
);;

CREATE TABLE IF NOT EXISTS dw_estrela.dim_loja (
    sk_loja    integer      GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    loja_uuid  uuid         NOT NULL UNIQUE,
    nome       varchar(255) NOT NULL,
    cidade     varchar(255) NOT NULL,
    uf         char(2)      NOT NULL,
    estado     varchar(40)  NOT NULL,
    regiao     varchar(20)  NOT NULL
);;

CREATE TABLE IF NOT EXISTS dw_estrela.dim_funcionario (
    sk_funcionario    integer      GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    funcionario_uuid  uuid         NOT NULL UNIQUE,
    nome              varchar(255) NOT NULL,
    cargo             varchar(20)  NOT NULL
);;

-- SCD tipo 2 na cidade/UF: cada mudanca de endereco cria uma versao nova; a antiga fica com data_fim e atual = false.
-- A versao vale para as vendas com data_inicio <= data_venda < data_fim.
-- sk_cliente = 0 e o membro fixo "Não identificado", usado nas vendas sem cliente (evita nulo no fato).
CREATE TABLE IF NOT EXISTS dw_estrela.dim_cliente (
    sk_cliente    integer      GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    cliente_uuid  uuid,                                 -- nulo so no membro "Não identificado"
    nome          varchar(255) NOT NULL,
    cidade        varchar(255) NOT NULL,
    uf            char(2)      NOT NULL,
    estado        varchar(40)  NOT NULL,
    regiao        varchar(20)  NOT NULL,
    data_inicio   date         NOT NULL,
    data_fim      date         NOT NULL DEFAULT DATE '9999-12-31',
    atual         boolean      NOT NULL
);;

-- No maximo uma versao atual por cliente.
CREATE UNIQUE INDEX IF NOT EXISTS uk_estrela_dim_cliente_atual
    ON dw_estrela.dim_cliente (cliente_uuid) WHERE atual;;

CREATE TABLE IF NOT EXISTS dw_estrela.fato_venda (
    sk_tempo         integer       NOT NULL REFERENCES dw_estrela.dim_tempo,
    sk_produto       integer       NOT NULL REFERENCES dw_estrela.dim_produto,
    sk_cliente       integer       NOT NULL REFERENCES dw_estrela.dim_cliente,
    sk_loja          integer       NOT NULL REFERENCES dw_estrela.dim_loja,
    sk_funcionario   integer       NOT NULL REFERENCES dw_estrela.dim_funcionario,
    venda_uuid       uuid          NOT NULL,            -- dimensao degenerada: o numero da venda
    id_item_origem   bigint        NOT NULL,            -- id do item no OLTP: garante a carga incremental sem repetir
    quantidade       numeric(12,3) NOT NULL,
    preco_unitario   numeric(12,2) NOT NULL,
    valor_bruto      numeric(14,2) NOT NULL,
    desconto         numeric(14,2) NOT NULL,
    valor_liquido    numeric(14,2) NOT NULL,
    carregado_em     timestamptz   NOT NULL DEFAULT now(),
    PRIMARY KEY (venda_uuid, id_item_origem)
);;

-- Indices nas chaves estrangeiras do fato: as consultas OLAP filtram e juntam por elas.
CREATE INDEX IF NOT EXISTS ix_estrela_fato_tempo       ON dw_estrela.fato_venda (sk_tempo);;
CREATE INDEX IF NOT EXISTS ix_estrela_fato_produto     ON dw_estrela.fato_venda (sk_produto);;
CREATE INDEX IF NOT EXISTS ix_estrela_fato_cliente     ON dw_estrela.fato_venda (sk_cliente);;
CREATE INDEX IF NOT EXISTS ix_estrela_fato_loja        ON dw_estrela.fato_venda (sk_loja);;
CREATE INDEX IF NOT EXISTS ix_estrela_fato_funcionario ON dw_estrela.fato_venda (sk_funcionario);;

-- Floco de neve ------------------------------------------------------------------------------------------------
-- As hierarquias viram tabelas proprias: produto -> categoria e cliente/loja -> cidade -> estado.
-- Produto, loja, cliente, funcionario e tempo usam as MESMAS chaves substitutas da estrela (o ETL as copia), entao
-- os dois fatos tem exatamente as mesmas linhas e a comparacao de desempenho e justa.

CREATE TABLE IF NOT EXISTS dw_floco.dim_estado (
    sk_estado  integer     GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    uf         char(2)     NOT NULL UNIQUE,
    nome       varchar(40) NOT NULL,
    regiao     varchar(20) NOT NULL
);;

CREATE TABLE IF NOT EXISTS dw_floco.dim_cidade (
    sk_cidade  integer      GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    nome       varchar(255) NOT NULL,
    sk_estado  integer      NOT NULL REFERENCES dw_floco.dim_estado,
    UNIQUE (nome, sk_estado)
);;

CREATE TABLE IF NOT EXISTS dw_floco.dim_categoria (
    sk_categoria    integer      GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    categoria_uuid  uuid         NOT NULL UNIQUE,
    nome            varchar(255) NOT NULL
);;

CREATE TABLE IF NOT EXISTS dw_floco.dim_produto (
    sk_produto      integer      PRIMARY KEY,           -- mesma chave da estrela
    produto_uuid    uuid         NOT NULL UNIQUE,
    codigo_barras   varchar(50)  NOT NULL,
    nome            varchar(255) NOT NULL,
    unidade_medida  varchar(20)  NOT NULL,
    ativo           boolean      NOT NULL,
    sk_categoria    integer      NOT NULL REFERENCES dw_floco.dim_categoria
);;

CREATE TABLE IF NOT EXISTS dw_floco.dim_loja (
    sk_loja    integer      PRIMARY KEY,
    loja_uuid  uuid         NOT NULL UNIQUE,
    nome       varchar(255) NOT NULL,
    sk_cidade  integer      NOT NULL REFERENCES dw_floco.dim_cidade
);;

CREATE TABLE IF NOT EXISTS dw_floco.dim_cliente (
    sk_cliente    integer      PRIMARY KEY,
    cliente_uuid  uuid,
    nome          varchar(255) NOT NULL,
    sk_cidade     integer      NOT NULL REFERENCES dw_floco.dim_cidade,
    data_inicio   date         NOT NULL,
    data_fim      date         NOT NULL,
    atual         boolean      NOT NULL
);;

CREATE TABLE IF NOT EXISTS dw_floco.dim_funcionario (
    sk_funcionario    integer      PRIMARY KEY,
    funcionario_uuid  uuid         NOT NULL UNIQUE,
    nome              varchar(255) NOT NULL,
    cargo             varchar(20)  NOT NULL
);;

CREATE TABLE IF NOT EXISTS dw_floco.dim_tempo (LIKE dw_estrela.dim_tempo INCLUDING ALL);;

CREATE TABLE IF NOT EXISTS dw_floco.fato_venda (
    sk_tempo         integer       NOT NULL REFERENCES dw_floco.dim_tempo,
    sk_produto       integer       NOT NULL REFERENCES dw_floco.dim_produto,
    sk_cliente       integer       NOT NULL REFERENCES dw_floco.dim_cliente,
    sk_loja          integer       NOT NULL REFERENCES dw_floco.dim_loja,
    sk_funcionario   integer       NOT NULL REFERENCES dw_floco.dim_funcionario,
    venda_uuid       uuid          NOT NULL,
    id_item_origem   bigint        NOT NULL,
    quantidade       numeric(12,3) NOT NULL,
    preco_unitario   numeric(12,2) NOT NULL,
    valor_bruto      numeric(14,2) NOT NULL,
    desconto         numeric(14,2) NOT NULL,
    valor_liquido    numeric(14,2) NOT NULL,
    carregado_em     timestamptz   NOT NULL DEFAULT now(),
    PRIMARY KEY (venda_uuid, id_item_origem)
);;

CREATE INDEX IF NOT EXISTS ix_floco_fato_tempo       ON dw_floco.fato_venda (sk_tempo);;
CREATE INDEX IF NOT EXISTS ix_floco_fato_produto     ON dw_floco.fato_venda (sk_produto);;
CREATE INDEX IF NOT EXISTS ix_floco_fato_cliente     ON dw_floco.fato_venda (sk_cliente);;
CREATE INDEX IF NOT EXISTS ix_floco_fato_loja        ON dw_floco.fato_venda (sk_loja);;
CREATE INDEX IF NOT EXISTS ix_floco_fato_funcionario ON dw_floco.fato_venda (sk_funcionario);;
CREATE INDEX IF NOT EXISTS ix_floco_produto_categoria ON dw_floco.dim_produto (sk_categoria);;
CREATE INDEX IF NOT EXISTS ix_floco_cidade_estado     ON dw_floco.dim_cidade (sk_estado);;
