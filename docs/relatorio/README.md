# Relatório teórico: textos-base

Rascunhos das seções do relatório (Ref. §3), versionados para revisão em PR. A versão final, em PDF no padrão
ABNT (12 a 20 páginas no total), é montada a partir destes textos.

## Seções do Sérgio (`sergio/`)

| Arquivo | Conteúdo | Ref. | Status |
|---|---|---|---|
| [introducao.md](sergio/introducao.md) | Introdução, adaptações ao enunciado | — | Rascunho |
| [1b-oql-jpql.md](sergio/1b-oql-jpql.md) | OQL e JPQL; tradução para SQL no Hibernate | §3.1 item 1b | Pronto para revisão |
| [2e-olap-oltp.md](sergio/2e-olap-oltp.md) | OLTP × OLAP, cubo, operações, ROLLUP/CUBE | §3.1 item 2e | Pronto para revisão |
| [consultas-olap.md](sergio/consultas-olap.md) | As 12 consultas OLAP: revisão, correções, `CUBE`/`GROUPING SETS` e resultados | §4.3 item 3 | Pronto para revisão |
| [comparacao-desempenho.md](sergio/comparacao-desempenho.md) | `EXPLAIN ANALYZE` OLTP × estrela × floco, com teste de escala | §4.3 item 3; Q9, Q12 | Pronto para revisão |
| [conclusao.md](sergio/conclusao.md) | Conclusão | — | Rascunho (falta a mineração) |
| [declaracao-uso-ia.md](sergio/declaracao-uso-ia.md) | Declaração de uso de IA | §6 | Rascunho (cada um completa a sua linha) |
| [referencias.md](sergio/referencias.md) | Referências ABNT destas seções | Anexo D | Para a lista consolidada do Luiz |

## Pendências

- **Questões de fixação 2, 6 e 12:** os enunciados estão no documento do professor, que não está no repositório.
  Respondê-las depois de ler o enunciado. A 12 tem relação com a comparação de desempenho, cuja base está em
  `comparacao-desempenho.md`.
- **Mineração (Bruna):** a conclusão tem um trecho reservado para as regras do Apriori.
- **Desconto no item de venda:** previsto no plano (`SaleItem.discountInCents`), mas ainda não existe no OLTP. Sem
  ele, `valor_liquido = valor_bruto` no DW.

### Observações sobre o ETL do PR #17 (para o Pedro avaliar)

Encontradas ao testar a carga. Nenhuma impede o uso do DW; não foram alteradas neste PR por serem da parte do ETL.

1. **Unidades de medida:** o `CASE` traduz `G`, `L` e `ML`, mas o enum do OLTP é `GRAM`, `LITER` e `MILLILITER`.
   Três produtos ficam com o código em inglês em `dim_produto.unidade_medida`.
2. **`INITCAP` em nomes:** grava "Feira De Santana", "Café Torrado E Moído 500g" e "Mercearia E Grãos". Um
   `btrim` + ajuste de espaços preservaria os nomes originais.
3. **Textos do membro fixo:** o script de estrutura grava "Cliente Não Identificado" e "Não informado"; o ETL,
   depois de uma carga completa, grava "Cliente Nao Identificado" e "Nao informado".
4. **SCD tipo 2 só com venda nova:** as dimensões são extraídas dos itens de venda do lote, então a mudança de
   cidade de um cliente só vira nova versão quando ele compra de novo. Na demonstração, é preciso alterar o
   cliente **e** registrar uma venda dele antes de rodar o ETL.
5. **Estatísticas:** sem `ANALYZE` no fim da carga, os planos logo depois do ETL saem piores (ver
   `comparacao-desempenho.md`, seção 4.3). Basta `ANALYZE dw_estrela.fato_venda` e `ANALYZE dw_floco.fato_venda`
   no fim da procedure.
6. **Índices do floco:** o fato do floco não tem índices nas chaves estrangeiras, e o da estrela não tem em
   `sk_funcionario`.
7. **Disparo pela API:** o plano previa `POST /api/etl/run` com as contagens antes e depois. Hoje a carga é só por
   `CALL etl.executar_carga(...)` no banco, o que funciona na demonstração pelo Adminer.
