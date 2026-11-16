package br.edu.ucsal.bd2.controller;

import java.util.UUID;

import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.SortDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import br.edu.ucsal.bd2.dto.request.SupplierRequest;
import br.edu.ucsal.bd2.dto.response.PageResponse;
import br.edu.ucsal.bd2.dto.response.SupplierResponse;
import br.edu.ucsal.bd2.model.Supplier;
import br.edu.ucsal.bd2.service.SupplierService;

@RestController
@RequestMapping("/api/supplier")
public class SupplierController {

	private final SupplierService service;

	public SupplierController(SupplierService service) {
		this.service = service;
		}

	// GET /api/supplier?page=0&size=20&sort=name,asc  (mesmas regras de paginação do ProductController)
	@GetMapping
	public ResponseEntity<PageResponse<SupplierResponse>> findAll(@ParameterObject @SortDefault(sort = "name") Pageable pageable) {
		Page<Supplier> suppliersPage = service.findAll(pageable);

		return ResponseEntity.ok(PageResponse.from(suppliersPage, SupplierResponse::convertToDTO));
	}

	@GetMapping("/{supplierUuid}")
	public ResponseEntity<SupplierResponse> findByUuid(@PathVariable UUID supplierUuid) {
		Supplier supplier = service.findByUuid(supplierUuid);

		return ResponseEntity.ok(SupplierResponse.convertToDTO(supplier));
	}

	@PostMapping
	public ResponseEntity<SupplierResponse> createSupplier(@RequestBody SupplierRequest request) {
		Supplier supplier = service.createSupplier(request);

		return ResponseEntity.status(HttpStatus.CREATED).body(SupplierResponse.convertToDTO(supplier));
	}

	@PutMapping("/{supplierUuid}")
	public ResponseEntity<SupplierResponse> updateSupplier(@PathVariable UUID supplierUuid, @RequestBody SupplierRequest request) {
		Supplier supplier = service.updateSupplier(supplierUuid, request);

		return ResponseEntity.ok(SupplierResponse.convertToDTO(supplier));
	}

	@DeleteMapping("/{supplierUuid}")
	public ResponseEntity<Void> deleteSupplier(@PathVariable UUID supplierUuid) {
		service.deleteSupplier(supplierUuid);

		return ResponseEntity.noContent().build();
	}
}
