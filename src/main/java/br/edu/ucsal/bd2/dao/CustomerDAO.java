package br.edu.ucsal.bd2.dao;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Repository;

import br.edu.ucsal.bd2.model.Customer;

@Repository
public class CustomerDAO extends GenericDAO<Customer, Long> {

    public CustomerDAO() {
        super(Customer.class);
    }

    public Page<Customer> findActive(Pageable pageable) {
        List<Customer> content = em.createQuery(
                        "SELECT c FROM Customer c WHERE c.deletedAt IS NULL ORDER BY c.name", Customer.class)
                .setFirstResult((int) pageable.getOffset())
                .setMaxResults(pageable.getPageSize())
                .getResultList();

        Long total = em.createQuery(
                        "SELECT COUNT(c) FROM Customer c WHERE c.deletedAt IS NULL", Long.class)
                .getSingleResult();

        return new PageImpl<>(content, pageable, total);
    }

    public Optional<Customer> findActiveByUuid(UUID uuid) {
        List<Customer> result = em.createQuery(
                        "SELECT c FROM Customer c WHERE c.uuid = :uuid AND c.deletedAt IS NULL", Customer.class)
                .setParameter("uuid", uuid)
                .getResultList();

        if (result.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(result.get(0));
    }

    // Olha Person (clientes e funcionários, inclusive excluídos): o CPF é único na tabela person
    public boolean existsByTaxNumber(String taxNumber) {
        Long total = em.createQuery(
                        "SELECT COUNT(p) FROM Person p WHERE p.taxNumber = :taxNumber", Long.class)
                .setParameter("taxNumber", taxNumber)
                .getSingleResult();
        return total > 0;
    }

    public boolean existsByTaxNumberInAnotherCustomer(String taxNumber, UUID uuid) {
        Long total = em.createQuery(
                        "SELECT COUNT(p) FROM Person p WHERE p.taxNumber = :taxNumber AND p.uuid <> :uuid", Long.class)
                .setParameter("taxNumber", taxNumber)
                .setParameter("uuid", uuid)
                .getSingleResult();
        return total > 0;
    }
}

