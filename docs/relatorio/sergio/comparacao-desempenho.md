# Comparação de desempenho: OLTP × estrela × floco de neve

> Base teórica e método da tarefa "comparação de tempo com EXPLAIN ANALYZE" (Ref. §4.3 item 3; questões 9 e 12)
> Responsável: Sérgio
> **Status:** método definido. Ainda **não há medições**: elas dependem do DW carregado pelo ETL. A tabela de
> resultados no fim fica para preencher.

## 1. A pergunta

A mesma pergunta de negócio, **faturamento por categoria e mês**, será respondida em três modelos:

| Modelo | Caminho da consulta | Junções |
|---|---|---|
| OLTP (`public`) | `sale_items → sale → product → category` | 3 |
| Estrela (`dw_estrela`) | `fato_venda → dim_tempo`, `fato_venda → dim_produto` (a categoria está dentro de `dim_produto`) | 2 |
| Floco de neve (`dw_floco`) | `fato_venda → dim_tempo`, `fato_venda → dim_produto → dim_categoria` | 3 |

Além do número de junções, o OLTP tem mais trabalho por linha:

- **filtra o status** (`sale.status = 'COMPLETED'`); no DW, o ETL já deixou de fora as vendas canceladas e abertas;
- **calcula o mês** de cada venda (`EXTRACT(MONTH FROM created_at)`); no DW, o mês está pronto em `dim_tempo`;
- **calcula o valor** (`quantidade × preço`); no DW, `valor_liquido` é uma medida já gravada no fato.

Essa é a tese do modelo dimensional (KIMBALL; ROSS, 2013): **transferir trabalho da consulta para a carga**.

## 2. Como o PostgreSQL executa uma consulta

Entre receber o SQL e devolver as linhas, o PostgreSQL passa por (POSTGRESQL GLOBAL DEVELOPMENT GROUP, 2024):

1. **análise sintática** (*parser*) e **reescrita** (*rewriter*, que expande visões e regras);
2. **planejamento** (*planner/optimizer*): gera planos alternativos e escolhe o de **menor custo estimado**, com
   base nas estatísticas das tabelas (número de linhas, distribuição de valores) coletadas pelo `ANALYZE`;
3. **execução** (*executor*): percorre a árvore do plano escolhido.

### 2.1 EXPLAIN × EXPLAIN ANALYZE

- `EXPLAIN consulta` mostra o plano escolhido e as **estimativas**. **Não executa** a consulta.
- `EXPLAIN ANALYZE consulta` **executa de verdade** e mostra, ao lado das estimativas, os **valores reais**:
  tempo, número de linhas e de repetições (*loops*) de cada nó.
- `EXPLAIN (ANALYZE, BUFFERS)` mostra também quantas páginas vieram da memória (*shared hit*) e quantas do disco
  (*read*). Isso ajuda a separar o efeito do cache do efeito do plano.

Como o `ANALYZE` executa, um `EXPLAIN ANALYZE` de `UPDATE` ou `DELETE` altera dados. Nesta comparação só há
`SELECT`, então não há risco.

### 2.2 Como ler um plano

```
HashAggregate  (cost=812.40..815.90 rows=280 width=48) (actual time=9.812..9.901 rows=520 loops=1)
  Group Key: c.name, (EXTRACT(month FROM s.created_at))
  ->  Hash Join  (cost=95.10..701.22 rows=14824 width=40) (actual time=1.103..6.540 rows=14310 loops=1)
        Hash Cond: (i.sale_id = s.id)
        ->  Seq Scan on sale_items i  (...)
        ->  Hash  (...)
              ->  Seq Scan on sale s  (...)
                    Filter: ((status)::text = 'COMPLETED'::text)
Planning Time: 0.420 ms
Execution Time: 10.112 ms
```
*(números ilustrativos, apenas para explicar a leitura)*

- **A árvore é lida de dentro para fora.** Os nós mais indentados (as leituras) alimentam os de cima (junção e
  agregação).
- **`cost=início..total`** é a estimativa em **unidades arbitrárias** do planejador, não em milissegundos. Serve
  para comparar planos entre si.
- **`rows`** é o número de linhas estimado; em `actual`, o real. Uma diferença grande entre os dois mostra
  estatística desatualizada e pode levar a um plano ruim.
- **`actual time`** é o tempo real em ms, do início (primeira linha) ao fim (última linha). Ele é **por repetição**:
  multiplique por `loops`.
- **`Planning Time`** e **`Execution Time`** são os totais. A comparação usa principalmente o `Execution Time`.

Nós mais comuns:

| Nó | Significado |
|---|---|
| `Seq Scan` | Lê a tabela inteira. Normal em consultas analíticas que usam quase todas as linhas |
| `Index Scan` / `Index Only Scan` | Usa um índice. Bom para poucas linhas |
| `Bitmap Heap Scan` | Índice para achar as páginas, depois lê as páginas em ordem. Meio-termo |
| `Hash Join` | Monta uma tabela hash com o lado menor e varre o maior. Típico de fato × dimensão |
| `Nested Loop` | Para cada linha de um lado, procura no outro. Bom quando um lado é pequeno e há índice |
| `Merge Join` | Junta duas entradas já ordenadas |
| `HashAggregate` / `GroupAggregate` | Executa o `GROUP BY` (por hash ou sobre dados ordenados) |
| `MixedAggregate` | Aparece com `ROLLUP`/`CUBE`/`GROUPING SETS` |
| `Gather` | Junta o trabalho de processos paralelos |

## 3. Método da medição

Para a comparação ser justa:

1. **Mesmos dados.** Os três modelos precisam representar as mesmas vendas: rodar o ETL completo e conferir que
   o total geral bate nos três.
2. **Estatísticas atualizadas.** Rodar `VACUUM ANALYZE` nas tabelas envolvidas antes de medir.
3. **Mesmo resultado.** As três consultas devem devolver as mesmas linhas (categoria, mês, valor). Se não
   devolverem, a comparação não vale.
4. **Cache.** A primeira execução lê do disco e as seguintes da memória. Descartar a primeira e usar a
   **mediana de 5 ou mais execuções**, registrando com `BUFFERS` se houve leitura de disco.
5. **Paralelismo.** O PostgreSQL pode usar vários processos (`Gather`) num modelo e não em outro. Para comparar só
   o efeito do modelo, medir também com `SET max_parallel_workers_per_gather = 0`.
6. **Índices declarados.** Registrar quais índices existem: as chaves estrangeiras do fato e as chaves primárias
   das dimensões. O plano depende deles.
7. **Ambiente.** Anotar a versão do PostgreSQL (17 no `docker-compose.yml`), a máquina e o volume de linhas de
   cada tabela.

## 4. O que se espera (hipóteses)

- **Estrela mais rápida que OLTP.** Menos junções, sem filtro de status, sem cálculo de mês e valor por linha, e
  dimensões pequenas, que cabem inteiras numa tabela hash.
- **Floco próximo da estrela.** A junção a mais (`dim_produto → dim_categoria`) envolve tabelas pequenas (200
  produtos, 20 categorias), então o custo extra deve ser pequeno. A vantagem do floco é normalizar e economizar
  espaço nas dimensões, não velocidade.
- **Diferenças pequenas em números absolutos.** Com 2.000 vendas (cerca de 13 mil itens no seed atual), tudo roda em poucos
  milissegundos. O que importa é a **proporção** entre os modelos e **o tipo de plano**: a vantagem do modelo
  dimensional cresce com o volume. Vale discutir no relatório como cada plano escalaria para milhões de linhas.

Se as medições contrariarem alguma hipótese, isso deve ser **relatado e explicado** pelo plano, não escondido. É
um resultado tão válido quanto o esperado.

## 5. Ameaças à validade

- O volume pequeno aproxima os tempos e aumenta o peso do ruído.
- Dados sintéticos (gerados com IA) podem ter uma distribuição diferente da real.
- O cache do sistema operacional fica fora do controle do PostgreSQL.
- A máquina de medição é um notebook de desenvolvimento, não um servidor.

## 6. Resultados (a preencher)

| Modelo | Junções | Plano principal (nós) | Planning (ms) | Execution, mediana (ms) | Buffers hit/read |
|---|---|---|---|---|---|
| OLTP | 3 | | | | |
| Estrela | 2 | | | | |
| Floco | 3 | | | | |

**Análise:** *(a escrever depois das medições, comparando os planos e respondendo às questões 9 e 12)*
