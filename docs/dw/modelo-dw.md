# Data Warehouse: modelos estrela e floco de neve

Scripts: `src/main/resources/db/dw/01_estrutura.sql` (tabelas) e `02_etl.sql` (ETL). Disparo: `POST /api/etl/run`.

## Decisões (plano da equipe, seção 4)

| Tema | Decisão |
|---|---|
| Granularidade | Uma linha por item vendido (`sale_items`) de venda concluída (`COMPLETED`) |
| Medidas | `quantidade`, `valor_bruto` (quantidade × preço), `desconto`, `valor_liquido` (bruto − desconto), em reais |
| Dimensões | Tempo, Produto, Cliente, Loja, Funcionário (vendedor) |
| Dimensão degenerada | `venda_uuid` (o número da venda fica no próprio fato) |
| Cliente não identificado | Membro fixo `sk_cliente = 0`: o fato nunca tem cliente nulo |
| Histórico | SCD tipo 2 na cidade/UF do cliente; tipo 1 (sobrescreve) nas demais dimensões |
| Pagamento | Fora do fato: uma venda pode ter vários pagamentos, o que quebraria a granularidade de item |
| Desconto | Sempre 0 por enquanto: o OLTP ainda não registra desconto no item |

## Modelo estrela (`dw_estrela`)

Cada dimensão é uma tabela só, ligada direto ao fato. As hierarquias ficam desnormalizadas dentro da dimensão:
a categoria está em `dim_produto`, e cidade, UF, estado e região estão em `dim_loja` e `dim_cliente`.

```mermaid
erDiagram
    fato_venda }o--|| dim_tempo : sk_tempo
    fato_venda }o--|| dim_produto : sk_produto
    fato_venda }o--|| dim_cliente : sk_cliente
    fato_venda }o--|| dim_loja : sk_loja
    fato_venda }o--|| dim_funcionario : sk_funcionario

    fato_venda {
        uuid venda_uuid PK "dimensão degenerada"
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
    dim_tempo {
        int sk_tempo PK "AAAAMMDD"
        date data
        smallint dia
        smallint dia_semana
        varchar nome_dia_semana
        boolean fim_de_semana
        smallint semana_ano
        smallint mes
        varchar nome_mes
        smallint trimestre
        smallint semestre
        smallint ano
    }
    dim_produto {
        int sk_produto PK
        uuid produto_uuid "chave natural"
        varchar codigo_barras
        varchar nome
        varchar unidade_medida
        varchar categoria
        boolean ativo
    }
    dim_cliente {
        int sk_cliente PK "0 = Não identificado"
        uuid cliente_uuid "chave natural"
        varchar nome
        varchar cidade
        char uf
        varchar estado
        varchar regiao
        date data_inicio "SCD 2"
        date data_fim "SCD 2"
        boolean atual "SCD 2"
    }
    dim_loja {
        int sk_loja PK
        uuid loja_uuid "chave natural"
        varchar nome
        varchar cidade
        char uf
        varchar estado
        varchar regiao
    }
    dim_funcionario {
        int sk_funcionario PK
        uuid funcionario_uuid "chave natural"
        varchar nome
        varchar cargo
    }
```

## Floco de neve (`dw_floco`)

As hierarquias viram tabelas próprias: produto → categoria e cliente/loja → cidade → estado. Tempo e funcionário
ficam iguais aos da estrela. Produto, cliente, loja, funcionário e tempo usam as **mesmas chaves substitutas** da
estrela, e o fato é uma cópia do fato da estrela. Os dois modelos guardam exatamente os mesmos dados, o que permite
comparar o desempenho com justiça.

```mermaid
erDiagram
    fato_venda }o--|| dim_tempo : sk_tempo
    fato_venda }o--|| dim_produto : sk_produto
    fato_venda }o--|| dim_cliente : sk_cliente
    fato_venda }o--|| dim_loja : sk_loja
    fato_venda }o--|| dim_funcionario : sk_funcionario
    dim_produto }o--|| dim_categoria : sk_categoria
    dim_cliente }o--|| dim_cidade : sk_cidade
    dim_loja }o--|| dim_cidade : sk_cidade
    dim_cidade }o--|| dim_estado : sk_estado

    fato_venda {
        uuid venda_uuid PK
        bigint id_item_origem PK
        int sk_tempo FK
        int sk_produto FK
        int sk_cliente FK
        int sk_loja FK
        int sk_funcionario FK
        numeric quantidade
        numeric valor_bruto
        numeric desconto
        numeric valor_liquido
    }
    dim_produto {
        int sk_produto PK
        uuid produto_uuid
        varchar nome
        varchar unidade_medida
        int sk_categoria FK
    }
    dim_categoria {
        int sk_categoria PK
        uuid categoria_uuid
        varchar nome
    }
    dim_cliente {
        int sk_cliente PK
        uuid cliente_uuid
        varchar nome
        int sk_cidade FK
        date data_inicio
        date data_fim
        boolean atual
    }
    dim_loja {
        int sk_loja PK
        uuid loja_uuid
        varchar nome
        int sk_cidade FK
    }
    dim_cidade {
        int sk_cidade PK
        varchar nome
        int sk_estado FK
    }
    dim_estado {
        int sk_estado PK
        char uf
        varchar nome
        varchar regiao
    }
    dim_tempo {
        int sk_tempo PK
        date data
        smallint mes
        smallint ano
    }
    dim_funcionario {
        int sk_funcionario PK
        varchar nome
        varchar cargo
    }
```

## Fluxo do ETL (`CALL etl.executar(completa)`)

```mermaid
flowchart LR
    OLTP[(public<br/>OLTP)] -->|1. extrair<br/>e transformar| STG[(stg<br/>staging)]
    STG -->|2. dim_tempo<br/>3. dimensões<br/>4. fato| ESTRELA[(dw_estrela)]
    ESTRELA -->|5. normalizar<br/>e copiar| FLOCO[(dw_floco)]
```

| Passo | Procedure | O que faz |
|---|---|---|
| 1 | `etl.extrair` | Esvazia o staging e copia do OLTP. Dimensões por inteiro; itens de venda **só os que faltam no DW** (carga incremental). Padroniza textos (espaços, UF), troca nulos por "Não informado", traduz códigos (`CASHIER` → "Caixa", `KG` → "Quilograma") e calcula as medidas (centavos → reais) |
| 2 | `etl.carregar_dim_tempo` | Gera todos os dias dos anos que as vendas cobrem, com nomes em português |
| 3 | `etl.carregar_dimensoes_tipo1` / `etl.carregar_dim_cliente` | Upsert de produto, loja e funcionário (SCD 1). Cliente com SCD 2: cidade/UF mudou → fecha a versão atual e abre outra a partir de hoje |
| 4 | `etl.carregar_fato_estrela` | Troca os uuids pelas chaves substitutas (o cliente na versão válida no dia da venda) e insere. Se algum item não virar linha do fato, desfaz a carga inteira |
| 5 | `etl.carregar_floco` | Monta estado, cidade e categoria, copia as dimensões com as mesmas chaves e copia as linhas novas do fato |

Garantias:
- **Tudo ou nada.** A execução é um único `CALL`: se qualquer passo falhar, nada muda no DW.
- **Uma execução por vez.** Um *advisory lock* serializa execuções simultâneas.
- **Idempotência.** Rodar duas vezes seguidas não duplica nada: a segunda execução insere 0 linhas.
- **Histórico.** Cada execução fica registrada em `etl.execucao`.

## Conferência

```sql
-- Os três modelos devem bater (contagem e total)
SELECT 'oltp', count(*), sum(round(i.quantity * i.unit_price_in_cents) / 100.0)
  FROM sale_items i JOIN sale s ON s.id = i.sale_id WHERE s.status = 'COMPLETED'
UNION ALL SELECT 'estrela', count(*), sum(valor_liquido) FROM dw_estrela.fato_venda
UNION ALL SELECT 'floco',   count(*), sum(valor_liquido) FROM dw_floco.fato_venda;
```

Com o seed atual: 12.467 itens e R$ 164.751,70 nos três.
