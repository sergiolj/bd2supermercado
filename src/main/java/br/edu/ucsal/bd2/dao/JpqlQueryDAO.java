package br.edu.ucsal.bd2.dao;

import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Repository;

import br.edu.ucsal.bd2.dto.response.CustomerSpendingResponse;
import br.edu.ucsal.bd2.dto.response.ProductPriceResponse;
import br.edu.ucsal.bd2.dto.response.ProductSalesResponse;
import br.edu.ucsal.bd2.dto.response.StoreRevenueResponse;
import br.edu.ucsal.bd2.util.SaleStatus;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

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
}

