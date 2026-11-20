package br.edu.ucsal.bd2.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import br.edu.ucsal.bd2.model.Supplier;

public interface SupplierRepository extends JpaRepository<Supplier, Long> {

	Optional<Supplier> findByUuid(UUID uuid);

	Optional<Supplier> findByTaxNumber(String taxNumber);

	List<Supplier> findByDeletedAtIsNull();

	Optional<Supplier> findByIdAndDeletedAtIsNull(Long id);

	boolean existsByTaxNumber(String taxNumber);

	boolean existsByTaxNumberAndIdNot(String taxNumber, Long id);

	// Usados pelo CRUD (SupplierService): soft delete, então só fornecedores ativos aparecem.
	Page<Supplier> findByDeletedAtIsNull(Pageable pageable);

	Optional<Supplier> findByUuidAndDeletedAtIsNull(UUID uuid);

	boolean existsByTaxNumberAndUuidNot(String taxNumber, UUID uuid);

}
