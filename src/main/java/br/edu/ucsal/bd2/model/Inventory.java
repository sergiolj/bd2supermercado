package br.edu.ucsal.bd2.model;

import java.math.BigDecimal;
import java.time.Instant;

import org.hibernate.annotations.UpdateTimestamp;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Getter
@Setter
@NoArgsConstructor
// Um registro por produto: o trigger de inventory_movement faz upsert (ON CONFLICT) nessa chave.
@Table(name = "inventory", uniqueConstraints = @UniqueConstraint(name = "uk_inventory_product", columnNames = "product_id"))
public class Inventory {
	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "product_id", nullable = false)
	private Product product;

	@Column(nullable = false, precision = 12, scale = 3)
	private BigDecimal totalQuantity;

	@UpdateTimestamp
	@Column(nullable = false)
	private Instant updatedAt;

}
