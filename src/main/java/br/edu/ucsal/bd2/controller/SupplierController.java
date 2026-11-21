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
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;

@Tag(name = "Fornecedores", description = "Cadastro de fornecedores, identificados pelo CNPJ. "
		+ "Exclusão lógica: as compras antigas são preservadas.")
@RestController
@RequestMapping("/api/supplier")
public class SupplierController {

	private final SupplierService service;

	public SupplierController(SupplierService service) {
		this.service = service;
		}

	// GET /api/supplier?page=0&size=20&sort=name,asc  (mesmas regras de paginação do ProductController)
	@Operation(summary = "Lista os fornecedores ativos",
			description = "Paginado: `page` começa em 0; `size` padrão 20, máximo 100. `sort` aceita `name`, `taxNumber` "
					+ "e `createdAt` (padrão: `name`), ex.: `sort=name,asc`.")
	@ApiResponse(responseCode = "200", description = "Página de fornecedores")
	@ApiResponse(responseCode = "400", description = "Campo de ordenação não permitido")
	@GetMapping
	public ResponseEntity<PageResponse<SupplierResponse>> findAll(@ParameterObject @SortDefault(sort = "name") Pageable pageable) {
		Page<Supplier> suppliersPage = service.findAll(pageable);

		return ResponseEntity.ok(PageResponse.from(suppliersPage, SupplierResponse::convertToDTO));
	}

	@Operation(summary = "Busca um fornecedor pelo uuid")
	@ApiResponse(responseCode = "200", description = "Fornecedor encontrado")
	@ApiResponse(responseCode = "404", description = "Fornecedor inexistente ou já apagado")
	@GetMapping("/{supplierUuid}")
	public ResponseEntity<SupplierResponse> findByUuid(@PathVariable UUID supplierUuid) {
		Supplier supplier = service.findByUuid(supplierUuid);

		return ResponseEntity.ok(SupplierResponse.convertToDTO(supplier));
	}

	@Operation(summary = "Cadastra um fornecedor",
			description = "CNPJ e nome são obrigatórios. O CNPJ pode vir com ou sem pontuação (é guardado só com os dígitos) "
					+ "e não pode repetir, nem com o de um fornecedor apagado.")
	@ApiResponse(responseCode = "201", description = "Fornecedor criado")
	@ApiResponse(responseCode = "400", description = "CNPJ (14 dígitos) ou nome inválidos")
	@ApiResponse(responseCode = "409", description = "CNPJ já cadastrado")
	@PostMapping
	public ResponseEntity<SupplierResponse> createSupplier(@RequestBody SupplierRequest request) {
		Supplier supplier = service.createSupplier(request);

		return ResponseEntity.status(HttpStatus.CREATED).body(SupplierResponse.convertToDTO(supplier));
	}

	@Operation(summary = "Atualiza um fornecedor", description = "Substitui todos os campos informados no cadastro.")
	@ApiResponse(responseCode = "200", description = "Fornecedor atualizado")
	@ApiResponse(responseCode = "400", description = "CNPJ (14 dígitos) ou nome inválidos")
	@ApiResponse(responseCode = "404", description = "Fornecedor inexistente ou já apagado")
	@ApiResponse(responseCode = "409", description = "CNPJ já usado por outro fornecedor")
	@PutMapping("/{supplierUuid}")
	public ResponseEntity<SupplierResponse> updateSupplier(@PathVariable UUID supplierUuid, @RequestBody SupplierRequest request) {
		Supplier supplier = service.updateSupplier(supplierUuid, request);

		return ResponseEntity.ok(SupplierResponse.convertToDTO(supplier));
	}

	@Operation(summary = "Apaga um fornecedor (exclusão lógica)",
			description = "O fornecedor continua no banco para preservar as compras antigas.")
	@ApiResponse(responseCode = "204", description = "Fornecedor apagado")
	@ApiResponse(responseCode = "404", description = "Fornecedor inexistente ou já apagado")
	@DeleteMapping("/{supplierUuid}")
	public ResponseEntity<Void> deleteSupplier(@PathVariable UUID supplierUuid) {
		service.deleteSupplier(supplierUuid);

		return ResponseEntity.noContent().build();
	}
}
