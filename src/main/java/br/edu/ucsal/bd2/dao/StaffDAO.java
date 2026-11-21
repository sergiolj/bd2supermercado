package br.edu.ucsal.bd2.dao;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Repository;

import br.edu.ucsal.bd2.model.Staff;

@Repository
public class StaffDAO extends GenericDAO<Staff, Long> {

    public StaffDAO() {
        super(Staff.class);
    }

    // O id no fim da ordenação mantém a ordem estável entre páginas quando há nomes repetidos.
    public Page<Staff> findActive(Pageable pageable) {
        List<Staff> content = em.createQuery(
                        "SELECT s FROM Staff s WHERE s.deletedAt IS NULL ORDER BY s.name, s.id", Staff.class)
                .setFirstResult((int) pageable.getOffset())
                .setMaxResults(pageable.getPageSize())
                .getResultList();

        Long total = em.createQuery(
                        "SELECT COUNT(s) FROM Staff s WHERE s.deletedAt IS NULL", Long.class)
                .getSingleResult();

        return new PageImpl<>(content, pageable, total);
    }

    public Optional<Staff> findActiveByUuid(UUID uuid) {
        List<Staff> result = em.createQuery(
                        "SELECT s FROM Staff s WHERE s.uuid = :uuid AND s.deletedAt IS NULL", Staff.class)
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
}
