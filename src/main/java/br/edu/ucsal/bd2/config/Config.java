package br.edu.ucsal.bd2.config;


import java.math.BigDecimal;
import java.util.List;

import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;

import br.edu.ucsal.bd2.model.Product;
import br.edu.ucsal.bd2.repository.ProductRepository;

@Configuration
public class Config {
	
	@Bean 
	@Order(1)
	CommandLineRunner loadProductList(ProductRepository repository) {
		return args->{
			if(repository.count() == 0) {
				Product p1 = new Product();
				p1.setName("Diamante Negro 100g");
				p1.setDescription("Barra de chocolate");
				p1.setPrice(new BigDecimal("23.99"));
				p1.setBarCode("8823999944221");

				Product p2 = new Product();
				p2.setName("Leite UHT Integral 1L");
				p2.setDescription("Leite de vaca integral pasteurizado");
				p2.setPrice(new BigDecimal("4.99"));
				p2.setBarCode("7891234567890");

				Product p3 = new Product();
				p3.setName("Arroz Tipo 1 5Kg");
				p3.setDescription("Arroz branco tipo 1");
				p3.setPrice(new BigDecimal("12.50"));
				p3.setBarCode("7891234567891");

				Product p4 = new Product();
				p4.setName("Feijão Preto 1Kg");
				p4.setDescription("Feijão preto seco");
				p4.setPrice(new BigDecimal("8.99"));
				p4.setBarCode("7891234567892");

				Product p5 = new Product();
				p5.setName("Açúcar Refinado 1Kg");
				p5.setDescription("Açúcar cristal refinado");
				p5.setPrice(new BigDecimal("5.25"));
				p5.setBarCode("7891234567893");

				Product p6 = new Product();
				p6.setName("Macarrão Parafuso 500g");
				p6.setDescription("Macarrão tipo parafuso");
				p6.setPrice(new BigDecimal("3.75"));
				p6.setBarCode("7891234567894");

				Product p7 = new Product();
				p7.setName("Óleo de Soja 900ml");
				p7.setDescription("Óleo vegetal de soja");
				p7.setPrice(new BigDecimal("7.99"));
				p7.setBarCode("7891234567895");

				Product p8 = new Product();
				p8.setName("Sal Iodado 1Kg");
				p8.setDescription("Sal de cozinha iodado");
				p8.setPrice(new BigDecimal("2.49"));
				p8.setBarCode("7891234567896");

				Product p9 = new Product();
				p9.setName("Café Torrado 250g");
				p9.setDescription("Café torrado e moído");
				p9.setPrice(new BigDecimal("15.99"));
				p9.setBarCode("7891234567897");

				Product p10 = new Product();
				p10.setName("Pão Francês 500g");
				p10.setDescription("Pão francês fresco");
				p10.setPrice(new BigDecimal("6.50"));
				p10.setBarCode("7891234567898");

				Product p11 = new Product();
				p11.setName("Manteiga 250g");
				p11.setDescription("Manteiga com sal");
				p11.setPrice(new BigDecimal("8.99"));
				p11.setBarCode("7891234567899");

				Product p12 = new Product();
				p12.setName("Queijo Mussarela 500g");
				p12.setDescription("Queijo mussarela fresca");
				p12.setPrice(new BigDecimal("18.99"));
				p12.setBarCode("7891234567900");

				Product p13 = new Product();
				p13.setName("Presunto Fatiado 300g");
				p13.setDescription("Presunto magro fatiado");
				p13.setPrice(new BigDecimal("12.50"));
				p13.setBarCode("7891234567901");

				Product p14 = new Product();
				p14.setName("Refrigerante Coca-Cola 2L");
				p14.setDescription("Refrigerante cola");
				p14.setPrice(new BigDecimal("7.99"));
				p14.setBarCode("7891234567902");

				Product p15 = new Product();
				p15.setName("Suco de Laranja 1L");
				p15.setDescription("Suco natural de laranja");
				p15.setPrice(new BigDecimal("5.99"));
				p15.setBarCode("7891234567903");

				Product p16 = new Product();
				p16.setName("Banana Prata 1Kg");
				p16.setDescription("Banana prata fresca");
				p16.setPrice(new BigDecimal("4.50"));
				p16.setBarCode("7891234567904");

				Product p17 = new Product();
				p17.setName("Maçã Vermelha 1Kg");
				p17.setDescription("Maçã vermelha fresca");
				p17.setPrice(new BigDecimal("6.99"));
				p17.setBarCode("7891234567905");

				Product p18 = new Product();
				p18.setName("Cenoura 500g");
				p18.setDescription("Cenoura fresca");
				p18.setPrice(new BigDecimal("3.25"));
				p18.setBarCode("7891234567906");

				Product p19 = new Product();
				p19.setName("Alface Crescente 100g");
				p19.setDescription("Alface crescente fresca");
				p19.setPrice(new BigDecimal("2.99"));
				p19.setBarCode("7891234567907");

				Product p20 = new Product();
				p20.setName("Ovo CX 12 unidades");
				p20.setDescription("Ovos de galinha");
				p20.setPrice(new BigDecimal("11.99"));
				p20.setBarCode("7891234567908");
				
				repository.saveAll(List.of(p1,p2,p3,p4,p5,p6,p7,p8,p9,p10,p11,p12,p13,p14,p15,p16,p17,p18,p19,p20));
			}
			
		};
		
	}

}
