package br.edu.ucsal.bd2.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import br.edu.ucsal.bd2.model.Product;

public interface ProductRepository extends JpaRepository<Product, Long>{

	Optional<Product> findByBarcode(String barcode);

	List<Product> findByDeletedAtIsNull();

	Optional<Product> findByIdAndDeletedAtIsNull(Long id);

	List<Product> findByCategoryIdAndDeletedAtIsNull(Long categoryId);

	boolean existsByCategoryIdAndDeletedAtIsNull(Long categoryId);

	boolean existsByBarcode(String barcode);

	boolean existsByBarcodeAndIdNot(String barcode, Long id);

}
