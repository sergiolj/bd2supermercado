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

import br.edu.ucsal.bd2.dto.request.ProductRequest;
import br.edu.ucsal.bd2.dto.response.PageResponse;
import br.edu.ucsal.bd2.dto.response.ProductResponse;

import br.edu.ucsal.bd2.model.Product;
import br.edu.ucsal.bd2.service.ProductService;


@RestController
@RequestMapping("/api/product")
public class ProductController {

	private final ProductService service;

	public ProductController(ProductService service) {
		this.service = service;
		}

	// GET /api/product?page=0&size=20&sort=name,asc
	// Tamanho padrão e máximo vêm de spring.data.web.pageable.* (application.properties). @SortDefault só define a
	// ordem padrão; não usar @PageableDefault aqui, porque ele fixaria o tamanho e ignoraria essa configuração.
	// @ParameterObject faz o Swagger mostrar page, size e sort como campos separados (sem ele vira um único "pageable").
	// Quais campos o sort aceita é decidido no service (SortPolicy).
	@GetMapping
	public ResponseEntity<PageResponse<ProductResponse>> findALL(@ParameterObject @SortDefault(sort = "name") Pageable pageable) {
		Page<Product> productsPage = service.findAll(pageable);

		return ResponseEntity.ok(PageResponse.from(productsPage, ProductResponse::convertToDTO));
	}

	@GetMapping("/{productUuid}")
	public ResponseEntity<ProductResponse> findByUuid(@PathVariable UUID productUuid){
		Product product = service.findByUuid(productUuid);
		ProductResponse response = ProductResponse.convertToDTO(product);

		return ResponseEntity.ok(response);
	}

	@PostMapping()
	public ResponseEntity<ProductResponse> createProduct(@RequestBody ProductRequest request){
		Product product = service.createProduct(request);
		ProductResponse response = ProductResponse.convertToDTO(product);

		return ResponseEntity.status(HttpStatus.CREATED).body(response);
	}

	@PutMapping("/{productUuid}")
	public ResponseEntity<ProductResponse> updateProduct(@PathVariable UUID productUuid, @RequestBody ProductRequest request){
		Product product = service.updateProduct(productUuid, request);
		ProductResponse response = ProductResponse.convertToDTO(product);

		return ResponseEntity.ok(response);
	}

	@DeleteMapping("/{productUuid}")
	public ResponseEntity<Void> deleteProduct(@PathVariable UUID productUuid){
		service.deleteProduct(productUuid);

		return ResponseEntity.noContent().build();
	}
}
