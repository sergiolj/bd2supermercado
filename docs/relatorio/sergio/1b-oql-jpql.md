# OQL e JPQL: linguagens de consulta sobre objetos

> Seção do relatório teórico · Ref. §3.1 item 1b · Responsável: Sérgio

## 1. O problema que essas linguagens resolvem

Um sistema orientado a objetos pensa em **objetos ligados por referências**: uma venda *tem* um vendedor, que
*tem* uma loja. O banco relacional pensa em **linhas de tabelas ligadas por chaves estrangeiras**:
`sale.seller_id` aponta para `staff.id`. Essa diferença entre os dois modelos é o *descasamento de impedância
objeto-relacional* (detalhado na seção de ORM). Ela também aparece na hora de **consultar**: em SQL, quem escreve a
consulta precisa saber o nome das tabelas e colunas e montar cada junção à mão.

As linguagens de consulta orientadas a objetos invertem isso. A consulta é escrita sobre **classes, atributos e
relacionamentos**, e uma camada de software a traduz para o armazenamento real. Duas linguagens marcam essa ideia:
a **OQL**, dos bancos de dados orientados a objetos, e a **JPQL**, dos frameworks de mapeamento objeto-relacional
em Java.

## 2. OQL: Object Query Language

A OQL faz parte do padrão **ODMG** (*Object Data Management Group*), cuja última versão é a ODMG 3.0
(CATTELL; BARRY, 2000). O padrão definia três peças para bancos de dados orientados a objetos (SGBDOO): um modelo
de objetos, uma linguagem de definição (ODL) e a linguagem de consulta (OQL). Ela foi desenhada para lembrar o SQL,
de propósito, mas sem as restrições do modelo relacional (ELMASRI; NAVATHE, 2018):

- **Consulta sobre extensões de classes.** `Clientes` é o conjunto de todos os objetos da classe, não uma tabela.
- **Expressões de caminho.** `c.endereco.cidade` navega de objeto em objeto pelas referências. Não existe cláusula
  de junção explícita.
- **Resultados de qualquer tipo.** Uma consulta pode devolver um valor atômico, um objeto, uma coleção (`set`,
  `bag`, `list`) ou uma estrutura (`struct`) montada na hora.
- **Chamada de métodos.** Métodos sem efeito colateral podem aparecer na consulta, como `p.idade()`.
- **Composição.** Toda consulta é uma expressão e pode ser usada dentro de outra.

Exemplo em OQL: os nomes dos clientes de Salvador com o total que cada um gastou.

```
select struct(nome: c.nome, total: sum(select v.total from v in c.vendas))
from c in Clientes
where c.endereco.cidade = "Salvador"
```

A OQL teve pouca adoção comercial. Os SGBDOO ficaram em nichos, e o mercado continuou relacional. Mesmo assim,
ela mostrou que é possível **consultar um modelo de objetos de forma declarativa**. Essa ideia voltou na
EJB QL (EJB 2.0, 2001), no HQL do Hibernate e na JPQL (BAUER; KING; GREGORY, 2015).

## 3. JPQL: Jakarta Persistence Query Language

A JPQL é a linguagem de consulta da especificação **Jakarta Persistence** (antes Java Persistence API, JPA). Ela
nasceu com a JPA 1.0 (2006) a partir da EJB QL e evoluiu até a versão 3.2 (ECLIPSE FOUNDATION, 2024), usada neste
projeto via Hibernate 7. A versão 3.1 acrescentou, por exemplo, a função `EXTRACT` para datas, e a 3.2 trouxe
`UNION`/`INTERSECT`/`EXCEPT` e `CAST`.

A diferença essencial para a OQL é o alvo. A JPQL **não roda num banco de objetos**: ela roda sobre um banco
relacional comum, e o provedor JPA (aqui, o Hibernate) a traduz para SQL. Ela herda da OQL a visão de objetos e
herda do SQL o modelo de execução.

### 3.1 Principais recursos

| Recurso | Exemplo em JPQL | Observação |
|---|---|---|
| Consulta sobre entidades | `SELECT s FROM Sale s` | `Sale` é a classe `@Entity`, não a tabela `sale` |
| Expressão de caminho | `s.customer.address.city` | Vira junções e colunas no SQL |
| Junções pela relação | `JOIN s.store st` | A condição `ON` sai do mapeamento (`@JoinColumn`) |
| `JOIN FETCH` | `JOIN FETCH s.items` | Carrega a relação no mesmo SELECT |
| Polimorfismo | `SELECT p FROM Person p` | Devolve `Customer` e `Staff`, conforme a herança |
| Expressão construtora | `SELECT new ...Dto(c.name, SUM(...))` | Resultado direto num objeto que não é entidade |
| Agregação | `GROUP BY`, `HAVING`, `COUNT`, `SUM`, `AVG` | Como no SQL |
| Subconsulta | `WHERE NOT EXISTS (SELECT 1 FROM Sale s WHERE s.customer = c)` | A especificação prevê subconsultas em `WHERE` e `HAVING` |
| Parâmetros nomeados | `WHERE s.status = :status` | Valor ligado com `setParameter` |
| Consultas nomeadas | `@NamedQuery(name = "Sale.revenueByStore", ...)` | Validadas na subida da aplicação |
| Atualização em lote | `UPDATE Product p SET ...` | Não passa pelo contexto de persistência |

### 3.2 Da JPQL ao SQL

O Hibernate não repassa o texto ao banco. Ele:

1. faz a **análise sintática** da JPQL e monta uma árvore da consulta (no Hibernate 6 e 7, o *SQM*, *Semantic
   Query Model*);
2. **confere a árvore com o metamodelo** das entidades: classes, atributos, tipos e relações. Um atributo
   inexistente é rejeitado aqui, antes de chegar ao banco;
3. **gera o SQL** no dialeto do banco configurado (PostgreSQL), resolvendo nomes de tabelas e colunas, junções,
   herança e paginação;
4. executa o SQL via JDBC, como *prepared statement*, e **monta o resultado**: entidades gerenciadas, DTOs ou
   valores.

Um exemplo real do projeto (consulta JPQL 7: desempenho dos vendedores):

```sql
-- JPQL
SELECT new ...SellerPerformanceResponse(v.name, v.role, st.name, COUNT(s), SUM(s.totalAmountInCents), ...)
  FROM Sale s JOIN s.seller v JOIN s.store st
 WHERE s.status = :status AND s.createdAt >= :from AND s.createdAt < :to
 GROUP BY v.id, v.name, v.role, st.name

-- SQL gerado pelo Hibernate (trecho)
select s2_1.name, s2_0.role, s3_0.name, count(s1_0.id), sum(s1_0.total_amount_in_cents), ...
  from sale s1_0
  join staff  s2_0 on s2_0.id = s1_0.seller_id
  join person s2_1 on s2_0.id = s2_1.id          -- junção da herança JOINED, gerada sozinha
  join store  s3_0 on s3_0.id = s1_0.store_id
 where s1_0.status = ? and s1_0.created_at >= ? and s1_0.created_at < ?
 group by s2_0.id, s2_1.name, s2_0.role, s3_0.name
```

O exemplo mostra três traduções:

- **A herança aparece como junção.** `v.name` está em `Person` e `v.role` em `Staff`. Com a estratégia `JOINED`,
  o Hibernate inclui a junção `staff → person` sem que a consulta a peça.
- **Os parâmetros viram `?`.** O valor nunca é concatenado no texto, o que impede *SQL injection* e permite ao
  banco reaproveitar o plano de execução. O Hibernate também converte os tipos: o enum `SaleStatus.COMPLETED` é
  gravado como texto por causa do `@Enumerated(STRING)`.
- **A paginação depende do dialeto.** `setFirstResult`/`setMaxResults` viram `offset ? rows fetch first ? rows only`
  no PostgreSQL e outra sintaxe em outros bancos. A mesma JPQL serve a vários SGBDs.

### 3.3 Cuidados que a JPQL não elimina

A abstração não dispensa entender o SQL gerado. Dois problemas apareceram no projeto:

- **N+1 consultas.** Ler uma relação que não veio carregada dispara um SELECT por objeto. No histórico de compras
  (JPQL 6), `Product.category` é `@ManyToOne`, que é EAGER por padrão, e gerava um SELECT extra por categoria.
  Acrescentar `JOIN FETCH p.category` deixou a consulta inteira em 2 SQLs.
- **`JOIN FETCH` de coleção com paginação.** O SQL devolve uma linha por item, não por venda, então `LIMIT`
  cortaria itens. A solução foi paginar primeiro os ids das vendas e depois buscá-las com `JOIN FETCH`.

A JPQL padrão também **não cobre** `GROUP BY ROLLUP/CUBE`, funções de janela nem comandos do SGBD como
`EXPLAIN ANALYZE`. O HQL do Hibernate acrescenta parte disso como extensão, mas o código deixa de ser portável
entre provedores JPA. Por isso as consultas analíticas (OLAP) do projeto usam SQL nativo, como discutido na seção
de OLAP.

## 4. Comparação

| Aspecto | SQL | OQL (ODMG) | JPQL (Jakarta Persistence) |
|---|---|---|---|
| Opera sobre | Tabelas e colunas | Extensões de classes e objetos | Entidades e atributos mapeados |
| Onde executa | No SGBD relacional | No SGBD orientado a objetos | No SGBD relacional, depois de traduzida para SQL |
| Junções | Explícitas, com condição `ON` | Implícitas, por caminhos | Por caminhos ou pela relação mapeada, sem `ON` obrigatório |
| Herança e polimorfismo | Não existem | Nativos | Mapeados (`SINGLE_TABLE`, `JOINED`, `TABLE_PER_CLASS`) |
| Resultado | Linhas | Objetos, coleções e estruturas | Entidades, DTOs (`SELECT new`) ou valores |
| Chamada de métodos | Não | Sim | Não (só funções da linguagem) |
| Adoção | Universal | Restrita | Ampla no ecossistema Java |

## 5. Aplicação no projeto

O sistema tem 10 consultas JPQL de relatório (`dao/JpqlQueryDAO.java`, expostas em `/api/jpql`), além das usadas
nos CRUDs:

| # | Consulta | Recursos de JPQL |
|---|---|---|
| 1 | Total gasto por cliente | `@NamedQuery`, junção, `GROUP BY`/`HAVING`, paginação |
| 2 | Faturamento por loja | `@NamedQuery`, junção, campos de `@Embeddable` |
| 3 | Produtos acima do preço médio | Subconsulta |
| 4 | Mais vendidos de uma loja | Junções encadeadas, `COUNT(DISTINCT)` |
| 5 | Faturamento por categoria e mês | `EXTRACT`, conta dentro do `SUM` |
| 6 | Histórico de compras com itens | `JOIN FETCH`, `DISTINCT`, paginação em duas etapas |
| 7 | Desempenho dos vendedores | Herança `JOINED`, `AVG`, filtro opcional |
| 8 | Clientes inativos | Subconsulta correlacionada com `NOT EXISTS`, paginação |
| 9 | Entradas, vendas e perdas de estoque | `SUM(CASE WHEN ...)`, literais de enum |
| 10 | Recebimentos por forma de pagamento | Caminho encadeado, `BETWEEN` |

As consultas são escritas com o `EntityManager` direto, e não com os métodos derivados do Spring Data, para
mostrar o uso da JPA pedido na Ref. §4.2 item 4.
