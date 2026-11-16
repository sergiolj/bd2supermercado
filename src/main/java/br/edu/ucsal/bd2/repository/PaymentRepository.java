package br.edu.ucsal.bd2.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import br.edu.ucsal.bd2.model.Payment;
import br.edu.ucsal.bd2.util.PaymentStatus;

public interface PaymentRepository extends JpaRepository<Payment, Long> {

	Optional<Payment> findByUuid(UUID uuid);

	List<Payment> findBySaleId(Long saleId);

	Optional<Payment> findByIdAndSaleId(Long id, Long saleId);

	List<Payment> findByStatus(PaymentStatus status);

	List<Payment> findBySaleIdAndStatus(Long saleId, PaymentStatus status);

	void deleteBySaleId(Long saleId);

}
