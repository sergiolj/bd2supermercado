package br.edu.ucsal.bd2.dao;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Repository;

import br.edu.ucsal.bd2.model.Price;
import br.edu.ucsal.bd2.model.Product;
import jakarta.persistence.LockModeType;
import jakarta.persistence.TypedQuery;

// Preço é um histórico: o vigente é a linha com deletedAt nulo; ao trocar o preço, a linha antiga é "fechada".
@Repository
public class PriceDAO extends GenericDAO<Price, Long> {

    public PriceDAO() {
        super(Price.class);
    }

    // Com lockForUpdate, a linha do produto fica travada até o fim da transação (SELECT ... FOR UPDATE). Quem altera
    // o preço do mesmo produto ao mesmo tempo espera aqui, e assim nunca sobram dois preços vigentes.
    public Optional<Product> findActiveProduct(UUID productUuid, boolean lockForUpdate) {
        TypedQuery<Product> query = em.createQuery(
                "SELECT p FROM Product p WHERE p.uuid = :uuid AND p.deletedAt IS NULL", Product.class)
                .setParameter("uuid", productUuid);
        if (lockForUpdate) {
            query.setLockMode(LockModeType.PESSIMISTIC_WRITE);
        }

        List<Product> result = query.getResultList();
        if (result.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(result.get(0));
    }

    public Optional<Price> findCurrent(Product product) {
        List<Price> result = em.createQuery(
                        "SELECT pr FROM Price pr WHERE pr.product = :product AND pr.deletedAt IS NULL "
                                + "ORDER BY pr.createdAt DESC, pr.id DESC", Price.class)
                .setParameter("product", product)
                .setMaxResults(1)
                .getResultList();

        if (result.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(result.get(0));
    }

    // Histórico completo (o vigente e os substituídos), do mais novo para o mais antigo.
    public Page<Price> findHistory(Product product, Pageable pageable) {
        List<Price> content = em.createQuery(
                        "SELECT pr FROM Price pr WHERE pr.product = :product ORDER BY pr.createdAt DESC, pr.id DESC", Price.class)
                .setParameter("product", product)
                .setFirstResult((int) pageable.getOffset())
                .setMaxResults(pageable.getPageSize())
                .getResultList();

        Long total = em.createQuery(
                        "SELECT COUNT(pr) FROM Price pr WHERE pr.product = :product", Long.class)
                .setParameter("product", product)
                .getSingleResult();

        return new PageImpl<>(content, pageable, total);
    }
}
