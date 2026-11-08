package br.edu.ucsal.bd2.model;

import java.math.BigDecimal;

import br.edu.ucsal.bd2.dto.request.ProductRequest;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import lombok.Data;

@Data
@Entity
public class Product {
	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long idLong;
	
	@Column(nullable = false)
	private String barCode;
	
	@Column(nullable = false, precision = 10, scale = 2)
	private BigDecimal price;
	
	@Column(nullable = false)
	private String name;
	
	@Column(nullable = false)
	private String description;
	
	public static Product convertToEntity(ProductRequest dto) {
		return new Product(
				dto.name(),
				dto.description(),
				dto.price(),
				dto.barCode());
	}
	
	public Product() {}

	public Product(String name, String description, BigDecimal price, String barCode) {
		super();
		this.barCode = barCode;
		this.price = price;
		this.name = name;
		this.description = description;
	}
	
	
}
