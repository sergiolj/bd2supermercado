package br.edu.ucsal.bd2.service;

import java.util.List;

import org.springframework.stereotype.Service;

import br.edu.ucsal.bd2.dto.request.ProductRequest;
import br.edu.ucsal.bd2.exceptions.ProductNotFoundException;
import br.edu.ucsal.bd2.model.Product;
import br.edu.ucsal.bd2.repository.ProductRepository;

@Service
public class ProductService {
	
	private final ProductRepository repository;
	
	public ProductService (ProductRepository repository) {
		this.repository = repository;
		}
	
	public List<Product> findAll(){
		return repository.findAll();
		}

	public Product findByID(Long productId) {
		return repository.findById(productId)
				.orElseThrow(() -> new ProductNotFoundException("Produto não encontrado " + productId.toString()));
		}

	public Product createProduct(ProductRequest dto) {
		Product product = Product.convertToEntity(dto);
		return repository.save(product);

		}


}
