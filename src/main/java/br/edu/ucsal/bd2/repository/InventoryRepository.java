package br.edu.ucsal.bd2.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import br.edu.ucsal.bd2.model.Inventory;

public interface InventoryRepository extends JpaRepository<Inventory, Long> {

	// O estoque é por loja: o mesmo produto tem uma linha em cada loja.
	Optional<Inventory> findByStoreIdAndProductId(Long storeId, Long productId);

}
