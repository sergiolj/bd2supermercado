package br.edu.ucsal.bd2.model;

import br.edu.ucsal.bd2.util.StaffRole;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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

}

