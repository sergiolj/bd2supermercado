package br.edu.ucsal.bd2.model;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.annotations.UuidGenerator;

import br.edu.ucsal.bd2.util.PaymentStatus;
import br.edu.ucsal.bd2.util.SaleStatus;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.NamedQuery;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Getter
@Setter
@NoArgsConstructor
@Table(name = "sale")
// Consultas nomeadas: o Hibernate valida a JPQL ao subir a aplicação, e o DAO chama pelo nome.
@NamedQuery(name = "Sale.totalByCustomer", query = """
        SELECT new br.edu.ucsal.bd2.dto.response.CustomerSpendingResponse(
                   c.name, c.address.city, COUNT(s), SUM(s.totalAmountInCents))
          FROM Sale s
          JOIN s.customer c
         WHERE s.status = :status
         GROUP BY c.id, c.name, c.address.city
        HAVING SUM(s.totalAmountInCents) >= :minTotal
         ORDER BY SUM(s.totalAmountInCents) DESC
        """)
@NamedQuery(name = "Sale.revenueByStore", query = """
        SELECT new br.edu.ucsal.bd2.dto.response.StoreRevenueResponse(
                   st.name, st.address.city, st.address.state, COUNT(s), SUM(s.totalAmountInCents))
          FROM Sale s
          JOIN s.store st
         WHERE s.status = :status
         GROUP BY st.id, st.name, st.address.city, st.address.state
         ORDER BY SUM(s.totalAmountInCents) DESC
        """)
public class Sale {
	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@UuidGenerator
	@Column(nullable = false, unique = true, updatable = false)
	private UUID uuid;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "seller_id", nullable = false)
	private Staff seller;

	// A loja fica na própria venda em vez de vir do vendedor: se ele for transferido, a venda antiga continua na loja
	// onde aconteceu. Pagamentos e itens não repetem a coluna: chegam à loja pela venda.
	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "store_id", nullable = false)
	private Store store;

	// Opcional: muita venda é feita sem identificar o cliente
	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "customer_id")
	private Customer customer;

	// Lado inverso: a chave estrangeira continua em sale_items.sale_id
	@OneToMany(mappedBy = "sale", cascade = CascadeType.ALL, orphanRemoval = true)
	private List<SaleItem> items = new ArrayList<>();

	// Lado inverso: a chave estrangeira fica em payment.sale_id. Sem cascade: cada pagamento é gravado quando é
	// recebido (SaleDAO.savePayment), e nunca é apagado (no máximo estornado).
	@OneToMany(mappedBy = "sale")
	private List<Payment> payments = new ArrayList<>();

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private SaleStatus status;

	@Column(nullable = false)
	private Long totalAmountInCents;

	// CPF/CNPJ na nota (opcional)
	private String clientTaxNumber;

	@CreationTimestamp
	@Column(nullable = false, updatable = false)
	private Instant createdAt;

	@UpdateTimestamp
	@Column(nullable = false)
	private Instant updatedAt;

	// Soma dos pagamentos aceitos (PAID): os recusados e os estornados não contam.
	public long paidInCents() {
		return payments.stream()
				.filter(payment -> payment.getStatus() == PaymentStatus.PAID)
				.mapToLong(Payment::getAmountInCents)
				.sum();
	}

	// O que falta pagar. A venda é concluída quando chega a zero (paga integralmente).
	public long remainingInCents() {
		return totalAmountInCents - paidInCents();
	}

}

