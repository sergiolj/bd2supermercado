package br.edu.ucsal.bd2.dao;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Repository;

import br.edu.ucsal.bd2.model.Category;

@Repository
public class CategoryDAO extends GenericDAO<Category, Long> {

    public CategoryDAO() {
        super(Category.class);
    }

    public Page<Category> findActive(Pageable pageable) {
        List<Category> content = em.createQuery(
                        "SELECT c FROM Category c WHERE c.deletedAt IS NULL ORDER BY c.name", Category.class)
                .setFirstResult((int) pageable.getOffset())
                .setMaxResults(pageable.getPageSize())
                .getResultList();

        Long total = em.createQuery(
                        "SELECT COUNT(c) FROM Category c WHERE c.deletedAt IS NULL", Long.class)
                .getSingleResult();

        return new PageImpl<>(content, pageable, total);
    }

    public Optional<Category> findActiveByUuid(UUID uuid) {
        List<Category> result = em.createQuery(
                        "SELECT c FROM Category c WHERE c.uuid = :uuid AND c.deletedAt IS NULL", Category.class)
                .setParameter("uuid", uuid)
                .getResultList();

        if (result.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(result.get(0));
    }

    public boolean existsByName(String name) {
        Long total = em.createQuery(
                        "SELECT COUNT(c) FROM Category c WHERE c.name = :name", Long.class)
                .setParameter("name", name)
                .getSingleResult();
        return total > 0;
    }

    public boolean existsByNameInAnotherCategory(String name, UUID uuid) {
        Long total = em.createQuery(
                        "SELECT COUNT(c) FROM Category c WHERE c.name = :name AND c.uuid <> :uuid", Long.class)
                .setParameter("name", name)
                .setParameter("uuid", uuid)
                .getSingleResult();
        return total > 0;
    }

    public boolean hasActiveProducts(Category category) {
        Long total = em.createQuery(
                        "SELECT COUNT(p) FROM Product p WHERE p.category = :category AND p.deletedAt IS NULL", Long.class)
                .setParameter("category", category)
                .getSingleResult();
        return total > 0;
    }
}

