package br.edu.ucsal.bd2.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import br.edu.ucsal.bd2.model.Sale;
import br.edu.ucsal.bd2.util.SaleStatus;

public interface SaleRepository extends JpaRepository<Sale, Long> {

	Optional<Sale> findByUuid(UUID uuid);

	List<Sale> findBySellerId(Long sellerId);

	List<Sale> findByStatus(SaleStatus status);

	List<Sale> findBySellerIdAndStatus(Long sellerId, SaleStatus status);

	List<Sale> findByClientTaxNumber(String clientTaxNumber);

	List<Sale> findByCreatedAtBetween(Instant start, Instant end);

}
