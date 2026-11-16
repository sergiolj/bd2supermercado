package br.edu.ucsal.bd2.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import br.edu.ucsal.bd2.model.Product;

public interface ProductRepository extends JpaRepository<Product, Long>{

	Optional<Product> findByBarcode(String barcode);

	// A categoria vem no mesmo SELECT (join), senão cada produto da página dispararia uma consulta extra para carregá-la.
	@EntityGraph(attributePaths = "category")
	Page<Product> findByDeletedAtIsNull(Pageable pageable);

	Optional<Product> findByUuidAndDeletedAtIsNull(UUID uuid);

	List<Product> findByCategoryIdAndDeletedAtIsNull(Long categoryId);

	boolean existsByCategoryIdAndDeletedAtIsNull(Long categoryId);

	boolean existsByBarcode(String barcode);

	boolean existsByBarcodeAndUuidNot(String barcode, UUID uuid);

}
