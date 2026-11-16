package br.edu.ucsal.bd2.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import br.edu.ucsal.bd2.model.InventoryMovement;
import br.edu.ucsal.bd2.util.MovementType;

public interface InventoryMovementRepository extends JpaRepository<InventoryMovement, Long> {

	List<InventoryMovement> findByType(MovementType type);

	List<InventoryMovement> findByProductIdOrderByCreatedAtDesc(Long productId);

	List<InventoryMovement> findByProductIdAndType(Long productId, MovementType type);

	List<InventoryMovement> findBySupplierId(Long supplierId);

	List<InventoryMovement> findBySaleId(Long saleId);

	boolean existsBySaleId(Long saleId);

	boolean existsBySupplierId(Long supplierId);

}
