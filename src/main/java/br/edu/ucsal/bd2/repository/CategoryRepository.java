package br.edu.ucsal.bd2.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import br.edu.ucsal.bd2.model.Category;

public interface CategoryRepository extends JpaRepository<Category, Long> {

	Optional<Category> findByName(String string);

	List<Category> findByDeletedAtIsNull();

	Optional<Category> findByIdAndDeletedAtIsNull(Long id);

	Optional<Category> findByUuidAndDeletedAtIsNull(UUID uuid);

	boolean existsByName(String name);

	boolean existsByNameAndIdNot(String name, Long id);

}
