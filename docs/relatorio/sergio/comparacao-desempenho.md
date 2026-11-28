# Comparação de desempenho: OLTP × estrela × floco de neve

> Ref. §4.3 item 3; base para as questões 9 e 12 · Responsável: Sérgio
> Consultas na seção 7 de `src/main/resources/db/dw/03_consultas_olap.sql` (escritas pelo Pedro, PR #17).
> Medições refeitas com o método abaixo em 28/11/2026.

## 1. A pergunta

A mesma pergunta de negócio, **faturamento por loja, categoria, ano e mês**, foi respondida nos três modelos. As
três consultas devolvem as mesmas 2.332 linhas e os mesmos valores.

| Modelo | Tabelas | Junções | Trabalho por linha |
|---|---|---|---|
| OLTP (`public`) | `sale_items`, `sale`, `product`, `category`, `store` | 4 | Filtra `status = 'COMPLETED'`, converte o fuso de `created_at` e extrai ano e mês, calcula `quantidade × preço` |
| Estrela (`dw_estrela`) | `fato_venda`, `dim_loja`, `dim_produto`, `dim_tempo` | 3 | Nenhum: ano, mês e valor já estão gravados |
| Floco (`dw_floco`) | `fato_venda`, `dim_loja`, `dim_produto`, `dim_categoria`, `dim_tempo` | 4 | Nenhum, mas a categoria exige uma junção a mais (`dim_produto → dim_categoria`) |

O floco tem o mesmo número de junções do OLTP, e ainda assim é mais rápido. Isso mostra que **o número de junções
não é o único fator**: pesam também o tamanho das tabelas juntadas, o trabalho feito em cada linha e o tipo de
agregação que o planejador consegue usar.

## 2. Como o PostgreSQL executa uma consulta

Entre receber o SQL e devolver as linhas, o PostgreSQL passa por (POSTGRESQL GLOBAL DEVELOPMENT GROUP, 2024):

1. **análise sintática** (*parser*) e **reescrita** (*rewriter*, que expande visões e regras);
2. **planejamento** (*planner*): gera planos alternativos e escolhe o de **menor custo estimado**, com base nas
   estatísticas das tabelas, coletadas pelo `ANALYZE`;
3. **execução** (*executor*): percorre a árvore do plano escolhido.

`EXPLAIN` mostra só o plano e as estimativas. `EXPLAIN ANALYZE` **executa** a consulta e mostra os valores reais
de tempo e linhas de cada nó. `BUFFERS` acrescenta quantas páginas vieram da memória (*shared hit*) e quantas do
disco (*read*).

Como ler um plano:
- **A árvore é lida de dentro para fora.** As leituras (`Seq Scan`) alimentam as junções (`Hash Join`), que
  alimentam a agregação e a ordenação.
- **`cost`** é uma estimativa em unidades arbitrárias, não em milissegundos. **`actual time`** é o tempo real em
  ms, por repetição: multiplique por `loops`.
- **`Execution Time`** é o total da execução, usado na comparação.

## 3. Método

1. **Mesmos dados:** carga completa do ETL; contagem e total conferidos nos três modelos (12.467 itens,
   R$ 164.751,70).
2. **Estatísticas atualizadas:** `VACUUM ANALYZE` em todas as tabelas envolvidas antes de medir.
3. **Repetição:** 16 execuções de cada consulta, descartando a primeira (cache frio). Mediana das 15 restantes.
4. **Escala:** o volume do seed é pequeno. Para ver a tendência, o banco foi copiado e o número de itens
   multiplicado por 20 e por 80, nos dois lados (OLTP e DW), com as mesmas proporções.
5. **Ambiente:** PostgreSQL 16 num container Linux, `work_mem` padrão de 4 MB e paralelismo padrão (até 2
   *workers*). O `docker-compose.yml` do projeto usa o PostgreSQL 17: os tempos absolutos podem variar, mas as
   estratégias de plano discutidas abaixo existem nas duas versões.

## 4. Resultados

### 4.1 Volume do seed (12.467 itens)

| Modelo | Junções | Planning (ms) | Execution, mediana (ms) | Em relação ao OLTP |
|---|---|---|---|---|
| OLTP | 4 | 0,73 | 23,4 | — |
| Estrela | 3 | 0,68 | 11,7 | −50% |
| Floco | 4 | 0,73 | 13,3 | −43% (+14% sobre a estrela) |

### 4.2 Escala

| Itens no fato | OLTP (ms) | Estrela (ms) | Floco (ms) | OLTP ÷ estrela |
|---|---|---|---|---|
| 12.467 (seed) | 23,4 | 11,7 | 13,3 | 2,0× |
| 249.340 (×20) | 659,8 | 65,3 | 73,6 | 10,1× |
| 997.360 (×80) | 915,2 | 227,7 | 266,7 | 4,0× |

### 4.3 O efeito das estatísticas

Na primeira rodada, feita logo depois da carga do DW e antes do `VACUUM ANALYZE`, os três modelos ficaram
empatados, em torno de 23 ms. Com o fato recém-carregado, as estatísticas e o mapa de visibilidade das tabelas
ainda não estavam atualizados. Por isso o ETL deveria terminar com `ANALYZE` nos fatos (ver pendências).

## 5. Análise

**Por que a estrela é mais rápida no volume do seed.** Os planos são parecidos: leituras sequenciais, junções por
hash com as dimensões (todas pequenas, cabem em memória) e `HashAggregate`. A diferença está no trabalho por
linha. O OLTP precisa:
- calcular `EXTRACT(... FROM created_at AT TIME ZONE 'America/Bahia')` para cada uma das 12 mil linhas;
- multiplicar quantidade por preço;
- filtrar o status;
- agrupar por chaves de texto mais largas (nome da loja e da categoria vindos de tabelas diferentes).

Na estrela, ano, mês e valor já estão prontos e a junção com `dim_tempo` é por um inteiro. É o que Kimball e
Ross (2013) descrevem: o modelo dimensional **transfere trabalho da consulta para a carga**. O ETL calcula uma
vez o que a análise usaria em toda consulta.

**Por que a diferença salta para 10× com 250 mil itens.** No OLTP, o planejador trocou a agregação por hash por
uma agregação sobre dados ordenados (`GroupAggregate`). A ordenação não coube nos 4 MB de `work_mem` e foi feita
**em disco** (`Sort Method: external merge  Disk: 20376kB`), sem paralelismo. Na estrela, a agregação continuou por
hash, em memória e em paralelo (`Partial HashAggregate` em 3 processos). O modelo dimensional mantém as chaves de
agrupamento estreitas (inteiros e textos curtos de dimensões pequenas), e por isso escala melhor.

**Por que a diferença cai para 4× com 1 milhão.** No ×80, o OLTP passou a usar paralelismo (`Gather Merge` com 2
*workers*), dividindo a ordenação em disco entre três processos. Continua 4 vezes mais lento que a estrela. A
proporção não cresce em linha reta porque o planejador muda de estratégia conforme o volume. Isso confirma a
recomendação de medir com o volume real, e não extrapolar a partir de amostras pequenas.

**Estrela × floco.** O floco ficou entre 13% e 17% mais lento que a estrela em todos os volumes. A causa é a junção extra
`dim_produto → dim_categoria`, presente nos planos como um `Hash Join` a mais sobre os 12 mil (ou 1 milhão de)
resultados intermediários. Como a tabela de categorias tem 20 linhas, o custo é pequeno, mas é constante e
proporcional ao número de linhas do fato. O ganho do floco é outro: dimensões normalizadas, sem repetir o nome da
categoria em cada produto. Isso importa mais quando as dimensões são grandes e mudam com frequência.

**Comparação com o PR #17.** O PR registrou cerca de 65 ms no OLTP e 36 ms nos dois modelos dimensionais (−45%).
A proporção é parecida com a medida aqui no volume do seed (−50%). A diferença nos valores absolutos vem da
máquina e de as medições do PR não terem `VACUUM ANALYZE` antes (seção 4.3).

## 6. Ameaças à validade

- **Volume pequeno.** No seed, tudo roda em milissegundos e o ruído pesa. O teste de escala foi feito para
  compensar isso.
- **Dados replicados.** As cópias ×20 e ×80 repetem as mesmas linhas, então os grupos têm a mesma distribuição. Num
  volume real, com mais lojas, produtos e anos, o número de grupos cresceria e a agregação pesaria ainda mais.
- **Dados sintéticos**, gerados com IA, podem ter uma distribuição diferente da real.
- **Máquina de desenvolvimento**, não um servidor dedicado; o cache do sistema operacional fica fora do controle
  do PostgreSQL.
- **Índices diferentes.** O fato da estrela tem índices nas chaves estrangeiras e o do floco não. Nesta consulta,
  que lê o fato inteiro, os índices não são usados (os planos mostram `Seq Scan`), mas eles fariam diferença em
  consultas com filtros seletivos (slice e dice).
