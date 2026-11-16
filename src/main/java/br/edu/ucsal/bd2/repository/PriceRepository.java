package br.edu.ucsal.bd2.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import br.edu.ucsal.bd2.model.Price;

public interface PriceRepository extends JpaRepository<Price, Long> {

	Optional<Price> findFirstByProductIdAndDeletedAtIsNullOrderByCreatedAtDesc(Long productId);

	boolean existsByProductIdAndDeletedAtIsNull(Long productId);

	List<Price> findByDeletedAtIsNull();

	List<Price> findByProductIdOrderByCreatedAtDesc(Long productId);

}
