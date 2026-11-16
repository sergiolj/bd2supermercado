package br.edu.ucsal.bd2.service;

import java.time.Instant;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import br.edu.ucsal.bd2.dto.request.SupplierRequest;
import br.edu.ucsal.bd2.model.Supplier;
import br.edu.ucsal.bd2.repository.SupplierRepository;
import br.edu.ucsal.bd2.util.SortPolicy;
import jakarta.persistence.EntityExistsException;
import jakarta.persistence.EntityNotFoundException;
import lombok.AllArgsConstructor;

// CRUD de fornecedor no mesmo padrão do ProductService: uuid na API, soft delete e listagem paginada.
@Service
@AllArgsConstructor
public class SupplierService {

	private final SupplierRepository repository;

	// Campos que o cliente pode usar em ?sort= na listagem de fornecedores.
	private static final SortPolicy SORT_POLICY = SortPolicy.allowing("name", "taxNumber", "createdAt");

	public Page<Supplier> findAll(Pageable pageable) {
		return repository.findByDeletedAtIsNull(SORT_POLICY.apply(pageable));
		}

	// Fornecedor apagado (deletedAt preenchido) é tratado como inexistente.
	public Supplier findByUuid(UUID supplierUuid) {
		return repository.findByUuidAndDeletedAtIsNull(supplierUuid)
				.orElseThrow(() -> new EntityNotFoundException("Fornecedor com uuid: " + supplierUuid + " não encontrado."));
		}

	@Transactional
	public Supplier createSupplier(SupplierRequest dto) {
		String taxNumber = normalizeTaxNumber(dto.taxNumber());
		String name = requireName(dto.name());

		// O CNPJ é único na tabela inteira, inclusive entre fornecedores apagados.
		if (repository.existsByTaxNumber(taxNumber)) {
			throw new EntityExistsException("Fornecedor já existe com esse CNPJ.");
		}

		Supplier supplier = new Supplier();
		supplier.setName(name);
		supplier.setTaxNumber(taxNumber);
		supplier.setEmail(blankToNull(dto.email()));
		supplier.setPhone(blankToNull(dto.phone()));

		return repository.save(supplier);
		}

	@Transactional
	public Supplier updateSupplier(UUID supplierUuid, SupplierRequest dto) {
		Supplier supplier = findByUuid(supplierUuid);
		String taxNumber = normalizeTaxNumber(dto.taxNumber());
		String name = requireName(dto.name());

		// "AndUuidNot" ignora o próprio fornecedor, senão salvar sem mudar o CNPJ daria conflito.
		if (repository.existsByTaxNumberAndUuidNot(taxNumber, supplierUuid)) {
			throw new EntityExistsException("Fornecedor já existe com esse CNPJ.");
		}

		supplier.setName(name);
		supplier.setTaxNumber(taxNumber);
		supplier.setEmail(blankToNull(dto.email()));
		supplier.setPhone(blankToNull(dto.phone()));

		return repository.save(supplier);
		}

	// Soft delete: o registro continua no banco (preserva as compras antigas em inventory_movement).
	@Transactional
	public void deleteSupplier(UUID supplierUuid) {
		Supplier supplier = findByUuid(supplierUuid);
		supplier.setDeletedAt(Instant.now());

		repository.save(supplier);
		}

	// Guarda só os dígitos, igual ao seed ("12.345.678/0001-95" -> "12345678000195").
	static String normalizeTaxNumber(String taxNumber) {
		String digits = taxNumber == null ? "" : taxNumber.replaceAll("\\D", "");
		if (digits.length() != 14) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "CNPJ deve ter 14 dígitos.");
		}
		return digits;
		}

	private static String requireName(String name) {
		if (name == null || name.isBlank()) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Nome do fornecedor é obrigatório.");
		}
		return name.trim();
		}

	private static String blankToNull(String value) {
		return value == null || value.isBlank() ? null : value.trim();
		}
}
