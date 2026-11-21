package br.edu.ucsal.bd2.dao;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Repository;

import br.edu.ucsal.bd2.model.Payment;
import br.edu.ucsal.bd2.model.Sale;
import br.edu.ucsal.bd2.model.Store;
import jakarta.persistence.LockModeType;
import jakarta.persistence.TypedQuery;

// Vendas de uma loja: a venda de outra loja não é achada por aqui (para ela, o uuid não existe).
@Repository
public class SaleDAO extends GenericDAO<Sale, Long> {

    public SaleDAO() {
        super(Sale.class);
    }

    // Com lockForUpdate, a linha da venda fica travada até o fim da transação (SELECT ... FOR UPDATE): dois pagamentos
    // da mesma venda ao mesmo tempo passam um de cada vez, então a soma nunca passa do total e a venda não é efetivada
    // duas vezes. A trava fica numa consulta só da venda porque, com JOIN, o PostgreSQL travaria também as linhas do
    // vendedor e dos produtos.
    public Optional<Sale> findInStore(Store store, UUID uuid, boolean lockForUpdate) {
        TypedQuery<Sale> query = em.createQuery(
                        "SELECT s FROM Sale s WHERE s.uuid = :uuid AND s.store = :store", Sale.class)
                .setParameter("uuid", uuid)
                .setParameter("store", store);
        if (lockForUpdate) {
            query.setLockMode(LockModeType.PESSIMISTIC_WRITE);
        }

        List<Sale> result = query.getResultList();
        if (result.isEmpty()) {
            return Optional.empty();
        }
        Sale sale = result.get(0);
        loadDetails(sale);
        return Optional.of(sale);
    }

    public void savePayment(Payment payment) {
        em.persist(payment);
    }

    // Vendedor, itens (com o produto) e pagamentos, para que nem as regras nem a resposta disparem uma consulta por item.
    // As duas listas não podem vir no mesmo JOIN FETCH (o Hibernate recusa buscar duas bags juntas), então cada uma vem
    // numa consulta; as duas preenchem a mesma venda, que já está no contexto de persistência.
    private void loadDetails(Sale sale) {
        em.createQuery("SELECT s FROM Sale s JOIN FETCH s.seller JOIN FETCH s.items i JOIN FETCH i.product "
                        + "WHERE s = :sale ORDER BY i.id", Sale.class)
                .setParameter("sale", sale)
                .getResultList();
        em.createQuery("SELECT s FROM Sale s LEFT JOIN FETCH s.payments p WHERE s = :sale ORDER BY p.id", Sale.class)
                .setParameter("sale", sale)
                .getResultList();
    }
}
