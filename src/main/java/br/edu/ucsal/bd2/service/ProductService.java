package br.edu.ucsal.bd2.service;

import java.util.List;

import org.springframework.stereotype.Service;

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

}
