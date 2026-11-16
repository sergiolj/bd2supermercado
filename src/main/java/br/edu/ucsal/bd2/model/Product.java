package br.edu.ucsal.bd2.model;

import java.time.Instant;
import java.util.UUID;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UuidGenerator;

import br.edu.ucsal.bd2.util.MeasurementType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Getter
@Setter
@NoArgsConstructor
@Table(name = "product")
public class Product {
	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@UuidGenerator
	@Column(nullable = false, unique = true, updatable = false)
	private UUID uuid;

	@ManyToOne(optional = false)
	@JoinColumn(name = "category_id", nullable = false)
	private Category category;

	@Column(nullable = false, unique = true)
	private String barcode;

	@Column(nullable = false)
	private String name;

	@Column(length = 1000)
	private String description;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private MeasurementType unitOfMeasure;

	@CreationTimestamp
	@Column(nullable = false, updatable = false)
	private Instant createdAt;

	private Instant deletedAt;

	public Product(String name, Category category, String barcode, String description,
			MeasurementType unitOfMeasure) {
		super();
		this.name = name;
		this.category = category;
		this.barcode = barcode;
		this.description = description;
		this.unitOfMeasure = unitOfMeasure;
	}

}
