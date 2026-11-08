package br.edu.ucsal.bd2.controller;

import java.util.List;

import org.springframework.http.HttpStatus;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import br.edu.ucsal.bd2.dto.response.ProductResponse;
import br.edu.ucsal.bd2.exceptions.ProductNotFoundException;
import br.edu.ucsal.bd2.model.Product;
import br.edu.ucsal.bd2.service.ProductService;


@RestController
@RequestMapping("/api/product")
public class ProductController {
	
	private final ProductService service;
	
	public ProductController(ProductService service) {
		this.service = service;
		}
	
	@GetMapping
	public ResponseEntity<List<ProductResponse>> findALL() {
		try {
			List<Product> productsList = service.findAll();
			
			List<ProductResponse> responseList = productsList.stream()
					.map(ProductResponse::convertToDTO)
					.toList();			
	
					return ResponseEntity.ok(responseList);
		}catch (IllegalArgumentException e) {
			return ResponseEntity.badRequest().body(null);
		}
	}
	
	@GetMapping("/{productId}")
	public ResponseEntity<ProductResponse> findById(@PathVariable Long productId){
		try {
			Product product = service.findByID(productId);
			ProductResponse response = ProductResponse.convertToDTO(product);
			return ResponseEntity.ok(response);
			
		}catch (ProductNotFoundException e) {
			return ResponseEntity.notFound().build();
		}catch (Exception e) {
			return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
		}
	}
}
