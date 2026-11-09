package br.edu.ucsal.bd2.config;

import java.util.List;

import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;

import br.edu.ucsal.bd2.model.Brand;
import br.edu.ucsal.bd2.model.FoodCategory;
import br.edu.ucsal.bd2.model.Product;
import br.edu.ucsal.bd2.repository.BrandRepository;
import br.edu.ucsal.bd2.repository.FoodCategoryRepository;
import br.edu.ucsal.bd2.repository.ProductRepository;
import br.edu.ucsal.bd2.util.MeasurementType;

@Configuration
public class Config {

	@Bean
	@Order(1)
	CommandLineRunner loadBrands(BrandRepository repository) {
		return args->{
			if(repository.count() == 0) {
				Brand b1 = new Brand();
				b1.setName("Nestlé");
				repository.saveAll(List.of(b1));				
			}
		};
	}
	
	@Bean
	@Order(2)
	CommandLineRunner loadFoodCategories(FoodCategoryRepository repository) {
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
					repository.save(new FoodCategory(null, categoryName));
				}
			}
		};
	}
	
	@Bean 
	@Order(3)
	CommandLineRunner loadProductList(ProductRepository repository,
			BrandRepository brandRepository, FoodCategoryRepository foodCategoryRepository) {
		return args->{
			if(repository.count() == 0) {
				Brand brand = brandRepository.findById(Long.parseLong("1")).orElseThrow();
				FoodCategory category = foodCategoryRepository.findByName("Doces e Salgadinhos").orElseThrow();
				
				Product p1 = new Product();
				p1.setName("Chocolate Classic ao Leite");
				p1.setCategory(category);
				p1.setBrand(brand);
				p1.setWeight(80.0);
				p1.setUnitType(MeasurementType.GRAM);
				p1.setBarCode("8823999944221");
				
				repository.saveAll(List.of(p1));
			}
			
		};
		
	}

}
