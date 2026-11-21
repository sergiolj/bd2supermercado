package br.edu.ucsal.bd2.dao;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Repository;

import br.edu.ucsal.bd2.model.InventoryMovement;
import br.edu.ucsal.bd2.model.Store;
import br.edu.ucsal.bd2.util.MovementType;

// Movimentações de estoque. As consultas daqui são das compras (PURCHASE); as saídas de venda (SALE) são lançadas pelo
// SaleService quando a venda é paga integralmente, e a perda (LOSS) entra depois.
// Toda consulta é de uma loja: a movimentação de outra loja não é achada por aqui.
// O saldo (inventory) NUNCA é escrito aqui: o trigger do banco o atualiza a cada inserção ou remoção.
@Repository
public class InventoryMovementDAO extends GenericDAO<InventoryMovement, Long> {

    public InventoryMovementDAO() {
        super(InventoryMovement.class);
    }

    // Produto e fornecedor vêm no mesmo SELECT (JOIN FETCH): senão cada compra da página dispararia consultas extras.
    // As compras mais recentes primeiro; o id no fim da ordenação mantém a ordem estável entre páginas.
    public Page<InventoryMovement> findPurchases(Store store, Pageable pageable) {
        List<InventoryMovement> content = em.createQuery(
                        "SELECT m FROM InventoryMovement m JOIN FETCH m.product LEFT JOIN FETCH m.supplier "
                                + "WHERE m.store = :store AND m.type = :type ORDER BY m.createdAt DESC, m.id DESC", InventoryMovement.class)
                .setParameter("store", store)
                .setParameter("type", MovementType.PURCHASE)
                .setFirstResult((int) pageable.getOffset())
                .setMaxResults(pageable.getPageSize())
                .getResultList();

        Long total = em.createQuery(
                        "SELECT COUNT(m) FROM InventoryMovement m WHERE m.store = :store AND m.type = :type", Long.class)
                .setParameter("store", store)
                .setParameter("type", MovementType.PURCHASE)
                .getSingleResult();

        return new PageImpl<>(content, pageable, total);
    }

    // Só enxerga compras da loja: o uuid de uma venda, de uma perda ou de uma compra de outra loja não é achado por aqui.
    public Optional<InventoryMovement> findPurchaseByUuid(Store store, UUID uuid) {
        List<InventoryMovement> result = em.createQuery(
                        "SELECT m FROM InventoryMovement m JOIN FETCH m.product LEFT JOIN FETCH m.supplier "
                                + "WHERE m.uuid = :uuid AND m.store = :store AND m.type = :type", InventoryMovement.class)
                .setParameter("uuid", uuid)
                .setParameter("store", store)
                .setParameter("type", MovementType.PURCHASE)
                .getResultList();

        if (result.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(result.get(0));
    }

    // O flush força o DELETE agora: o trigger desfaz a entrada no estoque e recusa se o saldo ficaria negativo.
    // Sem o flush, essa recusa só apareceria no commit, longe do ponto em que o service sabe tratá-la.
    public void removeAndFlush(InventoryMovement movement) {
        em.remove(movement);
        em.flush();
    }
}
