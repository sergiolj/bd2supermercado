package br.edu.ucsal.bd2.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import br.edu.ucsal.bd2.model.SaleItem;

public interface SaleItemRepository extends JpaRepository<SaleItem, Long> {

	List<SaleItem> findBySaleId(Long saleId);

	Optional<SaleItem> findByIdAndSaleId(Long id, Long saleId);

	List<SaleItem> findByProductId(Long productId);

	boolean existsBySaleId(Long saleId);

	boolean existsByProductId(Long productId);

	void deleteBySaleId(Long saleId);

}
