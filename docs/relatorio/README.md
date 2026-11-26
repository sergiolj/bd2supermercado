# Relatório teórico: textos-base

Rascunhos das seções do relatório (Ref. §3), versionados para revisão em PR. A versão final, em PDF no padrão
ABNT (12 a 20 páginas no total), é montada a partir destes textos.

## Seções do Sérgio (`sergio/`)

| Arquivo | Conteúdo | Ref. | Status |
|---|---|---|---|
| [introducao.md](sergio/introducao.md) | Introdução, adaptações ao enunciado | — | Rascunho |
| [1b-oql-jpql.md](sergio/1b-oql-jpql.md) | OQL e JPQL; tradução para SQL no Hibernate | §3.1 item 1b | Pronto para revisão |
| [2e-olap-oltp.md](sergio/2e-olap-oltp.md) | OLTP × OLAP, cubo, operações, ROLLUP/CUBE | §3.1 item 2e | Pronto para revisão |
| [consultas-olap.md](sergio/consultas-olap.md) | Fundamentos e planejamento das 10 consultas OLAP | §4.3 item 3 | Teoria pronta; consultas dependem do DW |
| [comparacao-desempenho.md](sergio/comparacao-desempenho.md) | EXPLAIN ANALYZE e método de comparação OLTP × estrela × floco | §4.3 item 3; Q9, Q12 | Método pronto; medições dependem do DW |
| [conclusao.md](sergio/conclusao.md) | Conclusão | — | Esqueleto (depende dos resultados) |
| [declaracao-uso-ia.md](sergio/declaracao-uso-ia.md) | Declaração de uso de IA | §6 | Rascunho (cada um completa a sua linha) |
| [referencias.md](sergio/referencias.md) | Referências ABNT destas seções | Anexo D | Para a lista consolidada do Luiz |

## Pendências

- **Questões de fixação 2, 6 e 12:** os enunciados estão no documento do professor, que não está no repositório.
  Respondê-las depois de ler o enunciado. A 12 tem relação com a comparação de desempenho, cuja base já está em
  `comparacao-desempenho.md`.
- **Desconto no item de venda:** previsto no plano (`SaleItem.discountInCents`), mas ainda não existe no OLTP. Sem
  ele, `valor_liquido = valor_bruto` no DW.
- **Nomes do DW:** as consultas de exemplo usam nomes ilustrativos (`fato_venda`, `dim_tempo` etc.), a ajustar aos
  scripts do Pedro.
