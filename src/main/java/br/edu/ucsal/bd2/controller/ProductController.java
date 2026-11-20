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
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;

@Tag(name = "Produtos", description = "Cadastro de produtos (categoria, código de barras e unidade de medida). "
		+ "O preço de venda é definido em Preços. Exclusão lógica.")
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
	@Operation(summary = "Lista os produtos ativos",
			description = "Paginado: `page` começa em 0; `size` padrão 20, máximo 100. `sort` aceita `name`, `barcode`, "
					+ "`createdAt` e `category.name` (padrão: `name`), ex.: `sort=name,asc`.")
	@ApiResponse(responseCode = "200", description = "Página de produtos")
	@ApiResponse(responseCode = "400", description = "Campo de ordenação não permitido")
	@GetMapping
	public ResponseEntity<PageResponse<ProductResponse>> findALL(@ParameterObject @SortDefault(sort = "name") Pageable pageable) {
		Page<Product> productsPage = service.findAll(pageable);

		return ResponseEntity.ok(PageResponse.from(productsPage, ProductResponse::convertToDTO));
	}

	@Operation(summary = "Busca um produto pelo uuid")
	@ApiResponse(responseCode = "200", description = "Produto encontrado")
	@ApiResponse(responseCode = "404", description = "Produto inexistente ou já apagado")
	@GetMapping("/{productUuid}")
	public ResponseEntity<ProductResponse> findByUuid(@PathVariable UUID productUuid){
		Product product = service.findByUuid(productUuid);
		ProductResponse response = ProductResponse.convertToDTO(product);

		return ResponseEntity.ok(response);
	}

	@Operation(summary = "Cadastra um produto",
			description = "Nome, categoria, código de barras e unidade de medida são obrigatórios; o código de barras não pode "
					+ "repetir. O produto nasce sem preço: defina-o em Preços para poder vendê-lo.")
	@ApiResponse(responseCode = "201", description = "Produto criado")
	@ApiResponse(responseCode = "404", description = "Categoria informada não existe (ou foi apagada)")
	@ApiResponse(responseCode = "409", description = "Código de barras já usado por outro produto")
	@PostMapping()
	public ResponseEntity<ProductResponse> createProduct(@RequestBody ProductRequest request){
		Product product = service.createProduct(request);
		ProductResponse response = ProductResponse.convertToDTO(product);

		return ResponseEntity.status(HttpStatus.CREATED).body(response);
	}

	@Operation(summary = "Atualiza um produto", description = "Substitui todos os campos informados no cadastro.")
	@ApiResponse(responseCode = "200", description = "Produto atualizado")
	@ApiResponse(responseCode = "404", description = "Produto ou categoria inexistente (ou apagado)")
	@ApiResponse(responseCode = "409", description = "Código de barras já usado por outro produto")
	@PutMapping("/{productUuid}")
	public ResponseEntity<ProductResponse> updateProduct(@PathVariable UUID productUuid, @RequestBody ProductRequest request){
		Product product = service.updateProduct(productUuid, request);
		ProductResponse response = ProductResponse.convertToDTO(product);

		return ResponseEntity.ok(response);
	}

	@Operation(summary = "Apaga um produto (exclusão lógica)",
			description = "O produto continua no banco para preservar vendas e movimentações antigas.")
	@ApiResponse(responseCode = "204", description = "Produto apagado")
	@ApiResponse(responseCode = "404", description = "Produto inexistente ou já apagado")
	@DeleteMapping("/{productUuid}")
	public ResponseEntity<Void> deleteProduct(@PathVariable UUID productUuid){
		service.deleteProduct(productUuid);

		return ResponseEntity.noContent().build();
	}
}
