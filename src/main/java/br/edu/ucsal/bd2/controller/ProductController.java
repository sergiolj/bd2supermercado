package br.edu.ucsal.bd2.controller;

import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import br.edu.ucsal.bd2.model.Product;
import br.edu.ucsal.bd2.service.ProductService;


@RestController
@RequestMapping("/api")
public class ProductController {
	
	private final ProductService service;
	
	public ProductController(ProductService service) {
		this.service = service;
		}
	
	@GetMapping("/products")
	public ResponseEntity <?> findALL() {
		try {
			List<Product> productsList = service.findAll();
			if(productsList.isEmpty()){
				return ResponseEntity.notFound().build();
			}
			return ResponseEntity.ok(productsList);
		}catch (IllegalArgumentException e) {
			return ResponseEntity.badRequest().body("Dados inválidos: "+ e.getMessage());
		}
	}
}
