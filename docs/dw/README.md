# Data Warehouse - Supermercado (BD2)

## 1. Arquitetura e Granularidade
- **Schemas:** `stg` (Staging), `etl` (controle e auditoria), `dw_estrela` (Star Schema) e `dw_floco` (Snowflake Schema).
- **Granularidade da Fato:** 1 linha por item vendido (`public.sale_items`) pertencente a vendas concluidas (`status = 'COMPLETED'`).
- **Medidas:** `quantidade`, `preco_unitario`, `valor_bruto`, `desconto` e `valor_liquido`.
- **SCD Tipo 2:** Aplicado na dimensao `dim_cliente` para rastrear historico de mudanca de `cidade` e `uf` (`data_inicio`, `data_fim`, `atual`).
- **Tratamento de Nulos:** Vendas sem cliente identificado sao associadas ao registro fixo `sk_cliente = 0` (`Cliente Nao Identificado`).

## 2. Diagramas Dimensionais
- **Modelo Estrela (`dw_estrela`):** `docs/dw/modelo-estrela.png`
- **Modelo Floco de Neve (`dw_floco`):** `docs/dw/modelo-floco-de-neve.png`

```mermaid
erDiagram
    FATO_VENDA {
        uuid venda_uuid PK
        bigint id_item_origem PK
        int sk_tempo FK
        int sk_produto FK
        int sk_cliente FK
        int sk_loja FK
        int sk_funcionario FK
        numeric quantidade
        numeric preco_unitario
        numeric valor_bruto
        numeric desconto
        numeric valor_liquido
    }
    DIM_TEMPO {
        int sk_tempo PK
        date data_completa
        smallint dia
        smallint mes
        varchar nome_mes
        smallint trimestre
        smallint semestre
        smallint ano
        smallint dia_semana
        varchar nome_dia_semana
        boolean fim_de_semana
    }
    DIM_PRODUTO {
        int sk_produto PK
        uuid produto_uuid
        varchar codigo_barras
        varchar nome_produto
        varchar unidade_medida
        varchar categoria
    }
    DIM_CLIENTE {
        int sk_cliente PK
        uuid cliente_uuid
        varchar nome_cliente
        varchar cidade
        char uf
        date data_inicio
        date data_fim
        boolean atual
    }
    DIM_LOJA {
        int sk_loja PK
        uuid loja_uuid
        varchar nome_loja
        varchar cidade
        char uf
    }
    DIM_FUNCIONARIO {
        int sk_funcionario PK
        uuid funcionario_uuid
        varchar nome_funcionario
        varchar cargo
    }

    DIM_TEMPO ||--o{ FATO_VENDA : "sk_tempo"
    DIM_PRODUTO ||--o{ FATO_VENDA : "sk_produto"
    DIM_CLIENTE ||--o{ FATO_VENDA : "sk_cliente"
    DIM_LOJA ||--o{ FATO_VENDA : "sk_loja"
    DIM_FUNCIONARIO ||--o{ FATO_VENDA : "sk_funcionario"
```

```mermaid
erDiagram
    FATO_VENDA {
        uuid venda_uuid PK
        bigint id_item_origem PK
        int sk_tempo FK
        int sk_produto FK
        int sk_cliente FK
        int sk_loja FK
        int sk_funcionario FK
        numeric quantidade
        numeric preco_unitario
        numeric valor_bruto
        numeric desconto
        numeric valor_liquido
    }
    DIM_TEMPO {
        int sk_tempo PK
        date data_completa
        smallint dia
        smallint mes
        smallint trimestre
        smallint semestre
        smallint ano
        boolean fim_de_semana
    }
    DIM_PRODUTO {
        int sk_produto PK
        uuid produto_uuid
        varchar nome_produto
        varchar unidade_medida
        int sk_categoria FK
    }
    DIM_CATEGORIA {
        int sk_categoria PK
        varchar nome_categoria
    }
    DIM_CLIENTE {
        int sk_cliente PK
        uuid cliente_uuid
        varchar nome_cliente
        int sk_cidade FK
        date data_inicio
        date data_fim
        boolean atual
    }
    DIM_LOJA {
        int sk_loja PK
        uuid loja_uuid
        varchar nome_loja
        int sk_cidade FK
    }
    DIM_CIDADE {
        int sk_cidade PK
        varchar nome_cidade
        int sk_estado FK
    }
    DIM_ESTADO {
        int sk_estado PK
        char uf
    }
    DIM_FUNCIONARIO {
        int sk_funcionario PK
        uuid funcionario_uuid
        varchar nome_funcionario
        varchar cargo
    }

    DIM_TEMPO ||--o{ FATO_VENDA : "sk_tempo"
    DIM_PRODUTO ||--o{ FATO_VENDA : "sk_produto"
    DIM_CATEGORIA ||--o{ DIM_PRODUTO : "sk_categoria"
    DIM_CLIENTE ||--o{ FATO_VENDA : "sk_cliente"
    DIM_LOJA ||--o{ FATO_VENDA : "sk_loja"
    DIM_CIDADE ||--o{ DIM_CLIENTE : "sk_cidade"
    DIM_CIDADE ||--o{ DIM_LOJA : "sk_cidade"
    DIM_ESTADO ||--o{ DIM_CIDADE : "sk_estado"
    DIM_FUNCIONARIO ||--o{ FATO_VENDA : "sk_funcionario"
```

## 3. Execucao do ETL
```sql
-- Carga completa (recria dimensoes e fatos)
CALL etl.executar_carga(true);

-- Carga incremental (insere apenas novos itens de venda e aplica SCD Tipo 2)
CALL etl.executar_carga(false);

-- Auditoria das cargas
SELECT * FROM etl.log_execucao;
```

## 4. Comparativo de Desempenho (`EXPLAIN ANALYZE`)
Consulta analitica agregando faturamento por loja, categoria, ano e mes sobre 12.517 itens vendidos:

| Modelo | 1a Execucao | 2a Execucao (Cache) | Observacao Tecnica |
| :--- | :---: | :---: | :--- |
| **OLTP (`public`)** | 69,395 ms | 65,282 ms | Exige 4 JOINs transacionais, filtro de status, conversao de timezone e calculo aritmetico por linha |
| **Estrela (`dw_estrela`)** | 36,155 ms | 37,193 ms | Reducao de ~45% no tempo; leitura direta de chaves surrogate inteiras e medidas pre-calculadas |
| **Floco de Neve (`dw_floco`)** | 36,906 ms | 36,510 ms | Desempenho proximo ao Estrela neste volume, exigindo 1 JOIN adicional na hierarquia `dim_categoria` |