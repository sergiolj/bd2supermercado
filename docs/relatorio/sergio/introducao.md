# Introdução

> Seção do relatório · Responsável: Sérgio · **Rascunho**: revisar quando as outras seções estiverem fechadas.

Bancos de dados atendem a duas necessidades diferentes numa organização. Na operação do dia a dia, precisam
registrar cada transação de forma rápida, correta e concorrente. Na gestão, precisam guardar o histórico e
responder perguntas analíticas que envolvem grandes volumes de dados. Este trabalho percorre esses dois mundos
num mesmo cenário: a rede de supermercados **Mercado Central**, com cinco lojas em quatro cidades do Nordeste
(Salvador e Feira de Santana, na Bahia; Aracaju, em Sergipe; e Recife, em Pernambuco).

A **primeira etapa** é um sistema transacional (OLTP) de vendas. Ele foi construído em Java com a especificação
Jakarta Persistence (JPA) e o Hibernate como mapeador objeto-relacional, sobre o PostgreSQL. O modelo tem 13
entidades persistentes, entre elas produtos, categorias, preços com histórico, fornecedores, lojas, funcionários,
clientes, vendas com itens e pagamentos, e movimentações de estoque. Ele também usa os principais recursos de
mapeamento: relacionamentos um-para-um, um-para-muitos bidirecional e muitos-para-um, herança com a estratégia
`JOINED` (`Person` → `Customer`/`Staff`), classe incorporável (`Address`), enumerações e tipos de data do
`java.time`. Sobre esse modelo foram escritas consultas em JPQL e com a Criteria API. A base foi carregada com
dados sintéticos: 2.000 vendas distribuídas em 26 meses, 200 produtos e 200 clientes.

A **segunda etapa** leva esses dados para um ambiente analítico. Os dados são extraídos do OLTP, transformados
e carregados (ETL) num *Data Warehouse* modelado de duas formas, **estrela** e **floco de neve**, com granularidade
de um item vendido por linha. Sobre o DW são feitas consultas **OLAP** (roll-up, drill-down, slice, dice e pivot),
uma comparação de desempenho entre os três modelos e uma análise de **mineração de dados** com o algoritmo Apriori,
em busca de associações de compra ("quem compra X também compra Y").

O objetivo geral é **relacionar teoria e prática** em cada tema da disciplina: mapeamento objeto-relacional,
linguagens de consulta orientadas a objetos, modelagem dimensional, ETL, OLAP e mineração. Cada seção apresenta
o conceito e mostra como ele aparece no código do projeto.

## Diferenças em relação ao enunciado

O professor autorizou flexibilidade no ambiente sugerido. As adaptações foram:

| Enunciado | Adotado | Motivo |
|---|---|---|
| Hibernate 5.2, JDK 8, Eclipse Neon | Spring Boot 4.1, Hibernate 7, Java 21 | Versões antigas sem suporte; o conceito de JPA é o mesmo |
| `javax.persistence` e `persistence.xml` | `jakarta.persistence` e `application.properties` | Novo nome do pacote; o Spring Boot monta a unidade de persistência |
| `GenericDAO<T, ID>` em todos os CRUDs | Spring Data + um `GenericDAO` com `EntityManager` (CRUD de categoria) | Atende ao pedido sem reescrever o que já funcionava |
| Interface console, Swing ou JSF | API REST documentada com Swagger | Teste direto no navegador |
| Weka ou Python para mineração | Weka como biblioteca Java | Mantém o projeto todo em Java |

## Organização do relatório

*(a completar com a ordem final das seções)*: fundamentos de ORM, JPA e Hibernate; linguagens de consulta (OQL e
JPQL); o sistema OLTP; Data Warehouse e modelagem dimensional; ETL; OLTP × OLAP e as consultas analíticas;
mineração de dados; questões de fixação; conclusão; declaração de uso de inteligência artificial; e referências.
