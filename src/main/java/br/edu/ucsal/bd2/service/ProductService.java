package br.edu.ucsal.bd2.service;

import java.time.Instant;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import br.edu.ucsal.bd2.dto.request.ProductRequest;
import br.edu.ucsal.bd2.model.Category;
import br.edu.ucsal.bd2.model.Product;
import br.edu.ucsal.bd2.repository.CategoryRepository;
import br.edu.ucsal.bd2.repository.ProductRepository;
import br.edu.ucsal.bd2.util.SortPolicy;
import jakarta.persistence.EntityExistsException;
import jakarta.persistence.EntityNotFoundException;
import lombok.AllArgsConstructor;

@Service
@AllArgsConstructor
public class ProductService {

	private final ProductRepository repository;
	private final CategoryRepository categoryRepository;


	// Campos que o cliente pode usar em ?sort= na listagem de produtos.
	private static final SortPolicy SORT_POLICY = SortPolicy.allowing("name", "barcode", "createdAt", "category.name");

	// Soft delete: só lista produtos ativos (deletedAt nulo). Devolve uma página, nunca a tabela inteira.
	public Page<Product> findAll(Pageable pageable){
		return repository.findByDeletedAtIsNull(SORT_POLICY.apply(pageable));
		}

	// Produto apagado (deletedAt preenchido) é tratado como inexistente.
	public Product findByUuid(UUID productUuid) {
		return repository.findByUuidAndDeletedAtIsNull(productUuid)
				.orElseThrow(() -> new EntityNotFoundException("Produto com uuid: " + productUuid + " não encontrado."));
		}

	@Transactional
	public Product createProduct(ProductRequest dto) {
		if(repository.existsByBarcode(dto.barcode())){
			throw new EntityExistsException("Produto já existe com esse código de barras.");
		}

		Category category = findCategory(dto.categoryUuid());

		Product product = new Product(
				dto.name(),
				category,
				dto.barcode(),
				dto.description(),
				dto.unitOfMeasure()
				);

		return repository.save(product);
		}

	@Transactional
	public Product updateProduct(UUID productUuid, ProductRequest dto) {
		Product product = findByUuid(productUuid);

		// "AndUuidNot" ignora o próprio produto, senão salvar sem mudar o código daria conflito.
		if(repository.existsByBarcodeAndUuidNot(dto.barcode(), productUuid)){
			throw new EntityExistsException("Produto já existe com esse código de barras.");
		}

		product.setName(dto.name());
		product.setCategory(findCategory(dto.categoryUuid()));
		product.setBarcode(dto.barcode());
		product.setDescription(dto.description());
		product.setUnitOfMeasure(dto.unitOfMeasure());

		return repository.save(product);
		}

	// Soft delete: o registro continua no banco (preserva vendas e movimentações antigas).
	@Transactional
	public void deleteProduct(UUID productUuid) {
		Product product = findByUuid(productUuid);
		product.setDeletedAt(Instant.now());

		repository.save(product);
		}

	private Category findCategory(UUID categoryUuid) {
		return categoryRepository.findByUuidAndDeletedAtIsNull(categoryUuid)
				.orElseThrow(() -> new EntityNotFoundException("Categoria com uuid: " + categoryUuid + " não encontrada."));
		}
}
