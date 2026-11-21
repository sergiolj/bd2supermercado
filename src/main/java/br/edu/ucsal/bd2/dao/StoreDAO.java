package br.edu.ucsal.bd2.dao;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Repository;

import br.edu.ucsal.bd2.model.Staff;
import br.edu.ucsal.bd2.model.Store;

@Repository
public class StoreDAO extends GenericDAO<Store, Long> {

    public StoreDAO() {
        super(Store.class);
    }

    // O gerente vem no mesmo SELECT (LEFT JOIN FETCH): senão cada loja da página dispararia uma consulta extra para
    // carregá-lo. LEFT porque a loja pode não ter gerente. O id no fim da ordenação mantém a ordem estável entre páginas.
    public Page<Store> findActive(Pageable pageable) {
        List<Store> content = em.createQuery(
                        "SELECT s FROM Store s LEFT JOIN FETCH s.manager WHERE s.deletedAt IS NULL ORDER BY s.name, s.id", Store.class)
                .setFirstResult((int) pageable.getOffset())
                .setMaxResults(pageable.getPageSize())
                .getResultList();

        Long total = em.createQuery(
                        "SELECT COUNT(s) FROM Store s WHERE s.deletedAt IS NULL", Long.class)
                .getSingleResult();

        return new PageImpl<>(content, pageable, total);
    }

    public Optional<Store> findActiveByUuid(UUID uuid) {
        List<Store> result = em.createQuery(
                        "SELECT s FROM Store s LEFT JOIN FETCH s.manager WHERE s.uuid = :uuid AND s.deletedAt IS NULL", Store.class)
                .setParameter("uuid", uuid)
                .getResultList();

        if (result.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(result.get(0));
    }

    // O nome é único na tabela inteira, inclusive entre lojas apagadas.
    public boolean existsByName(String name) {
        Long total = em.createQuery(
                        "SELECT COUNT(s) FROM Store s WHERE s.name = :name", Long.class)
                .setParameter("name", name)
                .getSingleResult();
        return total > 0;
    }

    public boolean existsByNameInAnotherStore(String name, UUID uuid) {
        Long total = em.createQuery(
                        "SELECT COUNT(s) FROM Store s WHERE s.name = :name AND s.uuid <> :uuid", Long.class)
                .setParameter("name", name)
                .setParameter("uuid", uuid)
                .getSingleResult();
        return total > 0;
    }

    // Um funcionário gerencia no máximo uma loja (store.manager_id é único).
    public boolean existsByManager(Staff manager) {
        Long total = em.createQuery(
                        "SELECT COUNT(s) FROM Store s WHERE s.manager = :manager", Long.class)
                .setParameter("manager", manager)
                .getSingleResult();
        return total > 0;
    }

    public boolean existsByManagerInAnotherStore(Staff manager, UUID uuid) {
        Long total = em.createQuery(
                        "SELECT COUNT(s) FROM Store s WHERE s.manager = :manager AND s.uuid <> :uuid", Long.class)
                .setParameter("manager", manager)
                .setParameter("uuid", uuid)
                .getSingleResult();
        return total > 0;
    }
}
