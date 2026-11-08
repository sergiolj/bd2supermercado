package br.edu.ucsal.bd2.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import br.edu.ucsal.bd2.model.FoodCategory;

public interface FoodCategoryRepository extends JpaRepository<FoodCategory, Long> {

}
