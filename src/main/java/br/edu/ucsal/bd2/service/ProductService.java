package br.edu.ucsal.bd2.service;

import java.time.Instant;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import br.edu.ucsal.bd2.dto.request.ProductRequest;
import br.edu.ucsal.bd2.model.Category;
import br.edu.ucsal.bd2.model.Product;
import br.edu.ucsal.bd2.repository.CategoryRepository;
import br.edu.ucsal.bd2.repository.ProductRepository;
import jakarta.persistence.EntityExistsException;
import jakarta.persistence.EntityNotFoundException;
import lombok.AllArgsConstructor;

@Service
@AllArgsConstructor
public class ProductService {

	private final ProductRepository repository;
	private final CategoryRepository categoryRepository;


	// Soft delete: só lista produtos ativos (deletedAt nulo).
	public List<Product> findAll(){
		return repository.findByDeletedAtIsNull();
		}

	// Produto apagado (deletedAt preenchido) é tratado como inexistente.
	public Product findByID(Long productId) {
		return repository.findByIdAndDeletedAtIsNull(productId)
				.orElseThrow(() -> new EntityNotFoundException("Produto com id: " + productId + " não encontrado."));
		}

	@Transactional
	public Product createProduct(ProductRequest dto) {
		if(repository.existsByBarcode(dto.barcode())){
			throw new EntityExistsException("Produto já existe com esse código de barras.");
		}

		Category category = findCategory(dto.categoryId());

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
	public Product updateProduct(Long productId, ProductRequest dto) {
		Product product = findByID(productId);

		// "AndIdNot" ignora o próprio produto, senão salvar sem mudar o código daria conflito.
		if(repository.existsByBarcodeAndIdNot(dto.barcode(), productId)){
			throw new EntityExistsException("Produto já existe com esse código de barras.");
		}

		product.setName(dto.name());
		product.setCategory(findCategory(dto.categoryId()));
		product.setBarcode(dto.barcode());
		product.setDescription(dto.description());
		product.setUnitOfMeasure(dto.unitOfMeasure());

		return repository.save(product);
		}

	// Soft delete: o registro continua no banco (preserva vendas e movimentações antigas).
	@Transactional
	public void deleteProduct(Long productId) {
		Product product = findByID(productId);
		product.setDeletedAt(Instant.now());

		repository.save(product);
		}

	private Category findCategory(Long categoryId) {
		return categoryRepository.findByIdAndDeletedAtIsNull(categoryId)
				.orElseThrow(() -> new EntityNotFoundException("Categoria com id: " + categoryId + " não encontrada."));
		}
}
