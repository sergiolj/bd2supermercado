package br.edu.ucsal.bd2.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import br.edu.ucsal.bd2.model.Inventory;
import br.edu.ucsal.bd2.model.Product;
import br.edu.ucsal.bd2.model.Store;
import jakarta.persistence.LockModeType;

public interface InventoryRepository extends JpaRepository<Inventory, Long> {

	// O estoque é por loja: o mesmo produto tem uma linha em cada loja.
	Optional<Inventory> findByStoreIdAndProductId(Long storeId, Long productId);

	// Saldo de vários produtos na loja, sem travar. Produto que nunca entrou na loja não tem linha (saldo zero).
	List<Inventory> findByStoreAndProductIn(Store store, Collection<Product> products);

	// O mesmo, travando as linhas até o fim da transação (SELECT ... FOR UPDATE). Na efetivação da venda, ninguém mexe
	// nesse saldo entre a conferência e a baixa feita pelo trigger. A ordem por produto faz toda venda travar as linhas
	// na mesma sequência, então duas vendas com produtos em comum não ficam esperando uma pela outra (deadlock).
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("SELECT i FROM Inventory i WHERE i.store = :store AND i.product IN :products ORDER BY i.product.id")
	List<Inventory> lockByStoreAndProducts(@Param("store") Store store, @Param("products") Collection<Product> products);

}
