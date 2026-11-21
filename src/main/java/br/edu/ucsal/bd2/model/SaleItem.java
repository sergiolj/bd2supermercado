package br.edu.ucsal.bd2.model;

import java.math.BigDecimal;
import java.math.RoundingMode;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
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
@Table(name = "sale_items")
public class SaleItem {
	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "product_id", nullable = false)
	private Product product;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "sale_id", nullable = false)
	private Sale sale;

	@Column(nullable = false, precision = 12, scale = 3)
	private BigDecimal quantity;

	// Preço vigente do produto quando o item entrou na venda: trocar o preço depois não muda a venda.
	@Column(nullable = false)
	private Long unitPriceInCents;

	// Valor da linha: quantidade x preço unitário, arredondado para o centavo (meio para cima), como no seed.
	public long totalInCents() {
		return quantity.multiply(BigDecimal.valueOf(unitPriceInCents))
				.setScale(0, RoundingMode.HALF_UP)
				.longValueExact();
	}

}
