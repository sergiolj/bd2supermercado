package br.edu.ucsal.bd2.service;

import java.util.List;

import org.springframework.stereotype.Service;

import br.edu.ucsal.bd2.dto.request.ProductRequest;
import br.edu.ucsal.bd2.model.Brand;
import br.edu.ucsal.bd2.model.FoodCategory;
import br.edu.ucsal.bd2.model.Product;
import br.edu.ucsal.bd2.repository.BrandRepository;
import br.edu.ucsal.bd2.repository.FoodCategoryRepository;
import br.edu.ucsal.bd2.repository.ProductRepository;
import jakarta.persistence.EntityExistsException;
import jakarta.persistence.EntityNotFoundException;
import lombok.AllArgsConstructor;

@Service
@AllArgsConstructor
public class ProductService {
	
	private final ProductRepository repository;
	private final BrandRepository brandRepository;
	private final FoodCategoryRepository foodCategoryRepository;


	public List<Product> findAll(){
		return repository.findAll();
		}

	public Product findByID(Long productId) {
		return repository.findById(productId)
				.orElseThrow(() -> new EntityNotFoundException("Produto com id: " + productId + " não encontrado."));
		}

	public Product createProduct(ProductRequest dto) {
		if(repository.existsByName(dto.name())){
			throw new EntityExistsException("Produto já existe com esse nome.");
		}
		
		Brand brandProxy = brandRepository.getReferenceById(dto.brandId());
		FoodCategory categoryProxyCategory = foodCategoryRepository.getReferenceById(dto.categoryId());
		
		Product product = new Product(
				dto.name(),
				brandProxy,
				dto.barCode(),
				dto.unitType(),
				dto.weight(),
				categoryProxyCategory
				);
		
		return repository.save(product);
		}
}
