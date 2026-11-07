package br.edu.ucsal.bd2.model;

import java.math.BigDecimal;


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
	
}
