package br.edu.ucsal.bd2.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import br.edu.ucsal.bd2.model.Staff;
import br.edu.ucsal.bd2.util.StaffRole;

public interface StaffRepository extends JpaRepository<Staff, Long> {

	Optional<Staff> findByUuid(UUID uuid);

	Optional<Staff> findByTaxNumber(String taxNumber);

	List<Staff> findByDeletedAtIsNull();

	Optional<Staff> findByIdAndDeletedAtIsNull(Long id);

	List<Staff> findByRoleAndDeletedAtIsNull(StaffRole role);

	boolean existsByTaxNumber(String taxNumber);

	boolean existsByTaxNumberAndIdNot(String taxNumber, Long id);

}
