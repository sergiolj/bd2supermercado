package br.edu.ucsal.bd2.dao;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import br.edu.ucsal.bd2.dto.response.CategoryMonthlyRevenueResponse;
import br.edu.ucsal.bd2.dto.response.CategoryStockFlowResponse;
import br.edu.ucsal.bd2.dto.response.CustomerSpendingResponse;
import br.edu.ucsal.bd2.dto.response.InactiveCustomerResponse;
import br.edu.ucsal.bd2.dto.response.PaymentMixResponse;
import br.edu.ucsal.bd2.dto.response.ProductPriceResponse;
import br.edu.ucsal.bd2.dto.response.ProductSalesResponse;
import br.edu.ucsal.bd2.dto.response.PurchaseHistoryResponse;
import br.edu.ucsal.bd2.dto.response.SellerPerformanceResponse;
import br.edu.ucsal.bd2.dto.response.StoreRevenueResponse;
import br.edu.ucsal.bd2.model.Sale;
import br.edu.ucsal.bd2.util.PaymentStatus;
import br.edu.ucsal.bd2.util.SaleStatus;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.TypedQuery;

@Repository
public class JpqlQueryDAO {

    @PersistenceContext
    private EntityManager em;

    // @NamedQuery: junção com o cliente, GROUP BY / HAVING e paginação
    public List<CustomerSpendingResponse> totalByCustomer(long minTotalInCents, int page, int size) {
        return em.createNamedQuery("Sale.totalByCustomer", CustomerSpendingResponse.class)
                .setParameter("status", SaleStatus.COMPLETED)
                .setParameter("minTotal", minTotalInCents)
                .setFirstResult(page * size)
                .setMaxResults(size)
                .getResultList();
    }

    // @NamedQuery: junção com a loja e GROUP BY
    public List<StoreRevenueResponse> revenueByStore() {
        return em.createNamedQuery("Sale.revenueByStore", StoreRevenueResponse.class)
                .setParameter("status", SaleStatus.COMPLETED)
                .getResultList();
    }

    // Subconsulta: preço vigente acima da média de todos os preços vigentes
    public List<ProductPriceResponse> productsAboveAveragePrice() {
        return em.createQuery("""
                SELECT new br.edu.ucsal.bd2.dto.response.ProductPriceResponse(p.name, c.name, pr.valueInCents)
                  FROM Price pr
                  JOIN pr.product p
                  JOIN p.category c
                 WHERE pr.deletedAt IS NULL
                   AND p.deletedAt IS NULL
                   AND pr.valueInCents > (SELECT AVG(x.valueInCents) FROM Price x WHERE x.deletedAt IS NULL)
                 ORDER BY pr.valueInCents DESC
                """, ProductPriceResponse.class)
                .getResultList();
    }

    // Junções item -> venda -> loja e item -> produto, agrupando pelos mais vendidos da loja
    public List<ProductSalesResponse> topProductsByStore(UUID storeUuid, int limit) {
        return em.createQuery("""
                SELECT new br.edu.ucsal.bd2.dto.response.ProductSalesResponse(p.name, SUM(i.quantity), COUNT(DISTINCT s))
                  FROM SaleItem i
                  JOIN i.sale s
                  JOIN s.store st
                  JOIN i.product p
                 WHERE st.uuid = :storeUuid
                   AND s.status = :status
                 GROUP BY p.id, p.name
                 ORDER BY SUM(i.quantity) DESC
                """, ProductSalesResponse.class)
                .setParameter("storeUuid", storeUuid)
                .setParameter("status", SaleStatus.COMPLETED)
                .setMaxResults(limit)
                .getResultList();
    }

    // Funções de data (EXTRACT) no SELECT e no GROUP BY, e uma medida calculada (quantidade x preço) dentro do SUM.
    // O período é [from, to): from entra, to não. O mês segue o fuso da sessão do banco (o mesmo da JVM).
    public List<CategoryMonthlyRevenueResponse> monthlyRevenueByCategory(Instant from, Instant to) {
        return em.createQuery("""
                SELECT new br.edu.ucsal.bd2.dto.response.CategoryMonthlyRevenueResponse(
                           c.name,
                           EXTRACT(YEAR FROM s.createdAt),
                           EXTRACT(MONTH FROM s.createdAt),
                           SUM(i.quantity),
                           SUM(i.quantity * i.unitPriceInCents))
                  FROM SaleItem i
                  JOIN i.sale s
                  JOIN i.product p
                  JOIN p.category c
                 WHERE s.status = :status
                   AND s.createdAt >= :from
                   AND s.createdAt < :to
                 GROUP BY c.name, EXTRACT(YEAR FROM s.createdAt), EXTRACT(MONTH FROM s.createdAt)
                 ORDER BY EXTRACT(YEAR FROM s.createdAt), EXTRACT(MONTH FROM s.createdAt),
                          SUM(i.quantity * i.unitPriceInCents) DESC
                """, CategoryMonthlyRevenueResponse.class)
                .setParameter("status", SaleStatus.COMPLETED)
                .setParameter("from", from)
                .setParameter("to", to)
                .getResultList();
    }

    // JOIN FETCH com paginação, em duas consultas:
    // 1) pagina só os ids das vendas (uma linha por venda, então LIMIT/OFFSET corta certo);
    // 2) carrega essas vendas com loja, itens e produtos no mesmo SELECT (JOIN FETCH).
    // Paginar direto a consulta com JOIN FETCH de uma coleção não funciona: o SQL devolve uma linha por item, e o
    // Hibernate teria de trazer tudo e paginar na memória (ele avisa com o HHH90003004).
    // A categoria também vem no JOIN FETCH: Product.category é EAGER (padrão do @ManyToOne), e sem isso o Hibernate
    // faria um SELECT extra por categoria depois da consulta (o problema N+1).
    @Transactional(readOnly = true)
    public List<PurchaseHistoryResponse> purchaseHistory(UUID customerUuid, int page, int size) {
        List<Long> ids = em.createQuery("""
                SELECT s.id
                  FROM Sale s
                 WHERE s.customer.uuid = :customerUuid
                   AND s.status = :status
                 ORDER BY s.createdAt DESC, s.id DESC
                """, Long.class)
                .setParameter("customerUuid", customerUuid)
                .setParameter("status", SaleStatus.COMPLETED)
                .setFirstResult(page * size)
                .setMaxResults(size)
                .getResultList();
        if (ids.isEmpty()) {
            return List.of();
        }

        return em.createQuery("""
                SELECT DISTINCT s
                  FROM Sale s
                  JOIN FETCH s.store
                  JOIN FETCH s.items i
                  JOIN FETCH i.product p
                  JOIN FETCH p.category
                 WHERE s.id IN :ids
                 ORDER BY s.createdAt DESC, s.id DESC
                """, Sale.class)
                .setParameter("ids", ids)
                .getResultList()
                .stream()
                .map(PurchaseHistoryResponse::from)
                .toList();
    }

    // Herança JOINED: o nome do vendedor está em Person (tabela person) e o cargo em Staff (tabela staff). Na JPQL é
    // só "v.name" e "v.role"; o Hibernate monta a junção staff -> person sozinho.
    // A loja é opcional: o trecho fixo do WHERE só entra quando ela é informada (os valores vão sempre por parâmetro).
    public List<SellerPerformanceResponse> sellerPerformance(UUID storeUuid, Instant from, Instant to) {
        String jpql = """
                SELECT new br.edu.ucsal.bd2.dto.response.SellerPerformanceResponse(
                           v.name, v.role, st.name, COUNT(s), SUM(s.totalAmountInCents), AVG(s.totalAmountInCents))
                  FROM Sale s
                  JOIN s.seller v
                  JOIN s.store st
                 WHERE s.status = :status
                   AND s.createdAt >= :from
                   AND s.createdAt < :to
                """
                + (storeUuid == null ? "" : "   AND st.uuid = :storeUuid\n")
                + """
                 GROUP BY v.id, v.name, v.role, st.name
                 ORDER BY SUM(s.totalAmountInCents) DESC
                """;
        TypedQuery<SellerPerformanceResponse> query = em.createQuery(jpql, SellerPerformanceResponse.class)
                .setParameter("status", SaleStatus.COMPLETED)
                .setParameter("from", from)
                .setParameter("to", to);
        if (storeUuid != null) {
            query.setParameter("storeUuid", storeUuid);
        }
        return query.getResultList();
    }

    // Subconsulta correlacionada com NOT EXISTS: a subconsulta usa o "c" da consulta de fora, então o banco a avalia
    // para cada cliente. Entram os clientes ativos no cadastro sem compra concluída desde "since" (inclusive os que
    // nunca compraram). Paginada.
    public List<InactiveCustomerResponse> inactiveCustomers(Instant since, int page, int size) {
        return em.createQuery("""
                SELECT new br.edu.ucsal.bd2.dto.response.InactiveCustomerResponse(
                           c.uuid, c.name, c.email, c.phone, c.address.city, c.address.state)
                  FROM Customer c
                 WHERE c.deletedAt IS NULL
                   AND NOT EXISTS (
                           SELECT 1
                             FROM Sale s
                            WHERE s.customer = c
                              AND s.status = :status
                              AND s.createdAt >= :since)
                 ORDER BY c.name, c.id
                """, InactiveCustomerResponse.class)
                .setParameter("status", SaleStatus.COMPLETED)
                .setParameter("since", since)
                .setFirstResult(page * size)
                .setMaxResults(size)
                .getResultList();
    }

    // CASE WHEN dentro do SUM ("soma condicional"): numa só passada pelas movimentações, separa o que entrou (compra),
    // o que saiu vendido e o que se perdeu, por categoria. Os tipos são literais de enum, com o nome completo da classe.
    public List<CategoryStockFlowResponse> stockFlowByCategory(UUID storeUuid, Instant from, Instant to) {
        return em.createQuery("""
                SELECT new br.edu.ucsal.bd2.dto.response.CategoryStockFlowResponse(
                           c.name,
                           SUM(CASE WHEN m.type = br.edu.ucsal.bd2.util.MovementType.PURCHASE THEN m.quantity ELSE 0 END),
                           SUM(CASE WHEN m.type = br.edu.ucsal.bd2.util.MovementType.SALE THEN m.quantity ELSE 0 END),
                           SUM(CASE WHEN m.type = br.edu.ucsal.bd2.util.MovementType.LOSS THEN m.quantity ELSE 0 END))
                  FROM InventoryMovement m
                  JOIN m.store st
                  JOIN m.product p
                  JOIN p.category c
                 WHERE st.uuid = :storeUuid
                   AND m.createdAt >= :from
                   AND m.createdAt < :to
                 GROUP BY c.name
                 ORDER BY SUM(CASE WHEN m.type = br.edu.ucsal.bd2.util.MovementType.LOSS THEN m.quantity ELSE 0 END) DESC,
                          c.name
                """, CategoryStockFlowResponse.class)
                .setParameter("storeUuid", storeUuid)
                .setParameter("from", from)
                .setParameter("to", to)
                .getResultList();
    }

    // Caminho encadeado (p.sale.store) e BETWEEN: quanto cada loja recebeu por forma de pagamento. Só os pagamentos
    // aceitos (PAID) contam; recusados e estornados ficam fora.
    public List<PaymentMixResponse> paymentMixByStore(Instant from, Instant to) {
        return em.createQuery("""
                SELECT new br.edu.ucsal.bd2.dto.response.PaymentMixResponse(
                           p.sale.store.name, p.method, COUNT(p), SUM(p.amountInCents))
                  FROM Payment p
                 WHERE p.status = :status
                   AND p.paidAt BETWEEN :from AND :to
                 GROUP BY p.sale.store.name, p.method
                 ORDER BY p.sale.store.name, SUM(p.amountInCents) DESC
                """, PaymentMixResponse.class)
                .setParameter("status", PaymentStatus.PAID)
                .setParameter("from", from)
                .setParameter("to", to)
                .getResultList();
    }
}

