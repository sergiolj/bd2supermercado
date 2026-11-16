package br.edu.ucsal.bd2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;

import br.edu.ucsal.bd2.dto.request.SupplierRequest;
import br.edu.ucsal.bd2.model.Supplier;
import br.edu.ucsal.bd2.repository.SupplierRepository;
import br.edu.ucsal.bd2.service.SupplierService;
import jakarta.persistence.EntityExistsException;
import jakarta.persistence.EntityNotFoundException;

@ExtendWith(MockitoExtension.class)
class SupplierServiceTest {

	@Mock
	private SupplierRepository repository;

	@InjectMocks
	private SupplierService service;

	private Supplier existing() {
		Supplier supplier = new Supplier();
		supplier.setId(1L);
		supplier.setUuid(UUID.randomUUID());
		supplier.setName("Distribuidora Bahia Alimentos");
		supplier.setTaxNumber("12345678000195");
		return supplier;
	}

	@Test
	@DisplayName("Should create a supplier keeping only the CNPJ digits")
	void shouldCreateSupplier() {
		when(repository.existsByTaxNumber("12345678000195")).thenReturn(false);
		when(repository.save(any(Supplier.class))).thenAnswer(inv -> inv.getArgument(0));

		Supplier saved = service.createSupplier(
				new SupplierRequest("  Distribuidora Bahia Alimentos ", "12.345.678/0001-95", "vendas@bahia.com.br", " "));

		assertEquals("Distribuidora Bahia Alimentos", saved.getName());
		assertEquals("12345678000195", saved.getTaxNumber());
		assertEquals("vendas@bahia.com.br", saved.getEmail());
		assertNull(saved.getPhone());
	}

	@Test
	@DisplayName("Should reject a duplicated CNPJ with EntityExistsException")
	void shouldRejectDuplicatedTaxNumber() {
		when(repository.existsByTaxNumber("12345678000195")).thenReturn(true);

		assertThrows(EntityExistsException.class,
				() -> service.createSupplier(new SupplierRequest("Outro", "12345678000195", null, null)));
		verify(repository, never()).save(any());
	}

	@Test
	@DisplayName("Should reject a CNPJ without 14 digits and a blank name")
	void shouldValidateInput() {
		assertThrows(ResponseStatusException.class,
				() -> service.createSupplier(new SupplierRequest("Fornecedor", "123", null, null)));
		assertThrows(ResponseStatusException.class,
				() -> service.createSupplier(new SupplierRequest(" ", "12345678000195", null, null)));
	}

	@Test
	@DisplayName("Should update a supplier ignoring its own CNPJ in the uniqueness check")
	void shouldUpdateSupplier() {
		Supplier supplier = existing();
		when(repository.findByUuidAndDeletedAtIsNull(supplier.getUuid())).thenReturn(Optional.of(supplier));
		when(repository.existsByTaxNumberAndUuidNot("12345678000195", supplier.getUuid())).thenReturn(false);
		when(repository.save(any(Supplier.class))).thenAnswer(inv -> inv.getArgument(0));

		Supplier updated = service.updateSupplier(supplier.getUuid(),
				new SupplierRequest("Distribuidora Bahia LTDA", "12345678000195", null, "(71) 3333-4444"));

		assertEquals("Distribuidora Bahia LTDA", updated.getName());
		assertEquals("(71) 3333-4444", updated.getPhone());
	}

	@Test
	@DisplayName("Should soft delete a supplier")
	void shouldSoftDeleteSupplier() {
		Supplier supplier = existing();
		when(repository.findByUuidAndDeletedAtIsNull(supplier.getUuid())).thenReturn(Optional.of(supplier));

		service.deleteSupplier(supplier.getUuid());

		assertNotNull(supplier.getDeletedAt());
		verify(repository).save(supplier);
	}

	@Test
	@DisplayName("Should throw EntityNotFoundException for an unknown or deleted supplier")
	void shouldThrowWhenNotFound() {
		UUID uuid = UUID.randomUUID();
		when(repository.findByUuidAndDeletedAtIsNull(uuid)).thenReturn(Optional.empty());

		assertThrows(EntityNotFoundException.class, () -> service.findByUuid(uuid));
	}
}
