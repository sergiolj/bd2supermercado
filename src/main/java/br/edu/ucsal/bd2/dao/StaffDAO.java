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
import jakarta.persistence.TypedQuery;

@Repository
public class StaffDAO extends GenericDAO<Staff, Long> {

    public StaffDAO() {
        super(Staff.class);
    }

    // Toda a rede, inclusive o ADMIN (que não tem loja).
    public Page<Staff> findActive(Pageable pageable) {
        return findActivePage(null, pageable);
    }

    // Só quem trabalha na loja.
    public Page<Staff> findActiveByStore(Store store, Pageable pageable) {
        return findActivePage(store, pageable);
    }

    // A loja vem no mesmo SELECT (LEFT JOIN FETCH; LEFT porque o ADMIN não tem loja): senão cada funcionário da página
    // dispararia uma consulta extra para carregá-la. O id no fim da ordenação mantém a ordem estável entre páginas
    // quando há nomes repetidos.
    private Page<Staff> findActivePage(Store store, Pageable pageable) {
        String storeFilter = store == null ? "" : " AND s.store = :store";

        TypedQuery<Staff> query = em.createQuery(
                "SELECT s FROM Staff s LEFT JOIN FETCH s.store WHERE s.deletedAt IS NULL" + storeFilter
                        + " ORDER BY s.name, s.id", Staff.class);
        TypedQuery<Long> count = em.createQuery(
                "SELECT COUNT(s) FROM Staff s WHERE s.deletedAt IS NULL" + storeFilter, Long.class);
        if (store != null) {
            query.setParameter("store", store);
            count.setParameter("store", store);
        }

        List<Staff> content = query
                .setFirstResult((int) pageable.getOffset())
                .setMaxResults(pageable.getPageSize())
                .getResultList();

        return new PageImpl<>(content, pageable, count.getSingleResult());
    }

    public Optional<Staff> findActiveByUuid(UUID uuid) {
        List<Staff> result = em.createQuery(
                        "SELECT s FROM Staff s LEFT JOIN FETCH s.store WHERE s.uuid = :uuid AND s.deletedAt IS NULL", Staff.class)
                .setParameter("uuid", uuid)
                .getResultList();

        if (result.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(result.get(0));
    }

    // Olha Person (funcionários e clientes, inclusive excluídos): o CPF é único na tabela person
    public boolean existsByTaxNumber(String taxNumber) {
        Long total = em.createQuery(
                        "SELECT COUNT(p) FROM Person p WHERE p.taxNumber = :taxNumber", Long.class)
                .setParameter("taxNumber", taxNumber)
                .getSingleResult();
        return total > 0;
    }

    public boolean existsByTaxNumberInAnotherPerson(String taxNumber, UUID uuid) {
        Long total = em.createQuery(
                        "SELECT COUNT(p) FROM Person p WHERE p.taxNumber = :taxNumber AND p.uuid <> :uuid", Long.class)
                .setParameter("taxNumber", taxNumber)
                .setParameter("uuid", uuid)
                .getSingleResult();
        return total > 0;
    }

    // Uma loja (ativa) aponta para este funcionário como gerente. A loja apagada solta o gerente, então basta olhar manager.
    public boolean managesStore(Staff staff) {
        Long total = em.createQuery(
                        "SELECT COUNT(st) FROM Store st WHERE st.manager = :staff", Long.class)
                .setParameter("staff", staff)
                .getSingleResult();
        return total > 0;
    }

    // A loja que este funcionário gerencia, se houver (store.manager_id é único: no máximo uma).
    public Optional<Store> findManagedStore(Staff staff) {
        List<Store> result = em.createQuery(
                        "SELECT st FROM Store st WHERE st.manager = :staff", Store.class)
                .setParameter("staff", staff)
                .getResultList();

        if (result.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(result.get(0));
    }

    public long countActiveByStore(Store store) {
        return em.createQuery(
                        "SELECT COUNT(s) FROM Staff s WHERE s.store = :store AND s.deletedAt IS NULL", Long.class)
                .setParameter("store", store)
                .getSingleResult();
    }
}
