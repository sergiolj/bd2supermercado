package br.edu.ucsal.bd2.model;

import java.time.Instant;
import java.util.UUID;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UuidGenerator;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

@Entity
@Getter
@Setter
@Table(name = "category")
public class Category{
	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@UuidGenerator
	@Column(nullable = false, unique = true, updatable = false)
	private UUID uuid;

	@Column(name = "name", nullable = false, unique = true)
	private String name;

	@CreationTimestamp
	@Column(nullable = false, updatable = false)
	private Instant createdAt;

	private Instant deletedAt;

	public Category() {}

	public Category(Long id, String name) {
		this.id = id;
		this.name = name;
	}

}
