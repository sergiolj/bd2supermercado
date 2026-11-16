package br.edu.ucsal.bd2.config;

import java.util.List;

import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;

import br.edu.ucsal.bd2.model.Category;
import br.edu.ucsal.bd2.model.Product;
import br.edu.ucsal.bd2.repository.CategoryRepository;
import br.edu.ucsal.bd2.repository.ProductRepository;
import br.edu.ucsal.bd2.util.MeasurementType;

@Configuration
public class Config {

	@Bean
	@Order(1)
	CommandLineRunner loadCategories(CategoryRepository repository) {
		return args->{
			if(repository.count() == 0) {
				List<String> categories = List.of(
						"Produtos Frescos e Hortifrúti",
						"Padaria e Confeitaria",
						"Laticínios e Ovos",
						"Carnes, Aves e Peixes",
						"Mercearia e Grãos",
						"Cereais e Matinais",
						"Alimentos Processados e Enlatados",
						"Congelados",
						"Bebidas",
						"Doces e Salgadinhos",
						"Materiais de Limpeza",
						"Higiene e Cuidados Pessoais",
						"Artigos de Descarte e Papelaria",
						"Cuidados com o Bebê",
						"Produtos para Pet Shop"
						);

				for (String categoryName : categories) {
					repository.save(new Category(null, categoryName));
				}
			}
		};
	}
	
	@Bean
	@Order(2)
	CommandLineRunner loadProductList(ProductRepository repository,
			CategoryRepository categoryRepository) {
		return args->{
			if(repository.count() == 0) {
				Category category = categoryRepository.findByName("Doces e Salgadinhos").orElseThrow();

				Product p1 = new Product();
				p1.setName("Chocolate Classic ao Leite");
				p1.setDescription("Barra de chocolate ao leite 80g");
				p1.setCategory(category);
				p1.setUnitOfMeasure(MeasurementType.UNIT);
				p1.setBarcode("8823999944221");
				
				repository.saveAll(List.of(p1));
			}
			
		};
		
	}

}
