package br.edu.ucsal.bd2.model;

import br.edu.ucsal.bd2.util.StaffRole;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
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
@Table(name = "staff")
public class Staff extends Person {

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private StaffRole role;

	// Loja onde trabalha. Nula só para o ADMIN, que é da rede: a regra fica no StaffService e num CHECK do banco
	// (db/store_constraints.sql).
	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "store_id")
	private Store store;

}

