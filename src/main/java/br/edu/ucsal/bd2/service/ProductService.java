package br.edu.ucsal.bd2.service;

import java.util.List;

import org.springframework.stereotype.Service;

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


	public List<Product> findAll(){
		return repository.findAll();
		}

	public Product findByID(Long productId) {
		return repository.findById(productId)
				.orElseThrow(() -> new EntityNotFoundException("Produto com id: " + productId + " não encontrado."));
		}

	public Product createProduct(ProductRequest dto) {
		if(repository.existsByBarcode(dto.barcode())){
			throw new EntityExistsException("Produto já existe com esse código de barras.");
		}

		Category categoryProxyCategory = categoryRepository.getReferenceById(dto.categoryId());

		Product product = new Product(
				dto.name(),
				categoryProxyCategory,
				dto.barcode(),
				dto.description(),
				dto.unitOfMeasure()
				);
		
		return repository.save(product);
		}
}
