package br.edu.ucsal.bd2.model;

import br.edu.ucsal.bd2.util.MeasurementType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
@Table(name = "product")
public class Product {
	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;
	
	@Column(nullable = false)
	private String name;
	
	@ManyToOne
	@JoinColumn(name = "brand_id")
	private Brand brand;
	
	@Column(nullable = false)
	private String barCode;
	
	@Column(nullable = false)
	private MeasurementType unitType;
	
	@Column(nullable = false)
	private Double weight;
	
	@ManyToOne
	@JoinColumn(name = "category_id")
	private FoodCategory category;

	public Product(String name, Brand brand, String barCode, MeasurementType unitType, Double weight,
			FoodCategory category) {
		super();
		this.name = name;
		this.brand = brand;
		this.barCode = barCode;
		this.unitType = unitType;
		this.weight = weight;
		this.category = category;
	}
	
}
