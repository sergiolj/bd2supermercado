package br.edu.ucsal.bd2.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import br.edu.ucsal.bd2.model.Product;

public interface ProductRepository extends JpaRepository<Product, Long>{

	boolean existsByName(String name);

}
