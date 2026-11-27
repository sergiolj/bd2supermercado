package br.edu.ucsal.bd2.config;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.AnnotatedElementUtils;

import br.edu.ucsal.bd2.controller.CategoryController;
import br.edu.ucsal.bd2.controller.CriteriaQueryController;
import br.edu.ucsal.bd2.controller.EtlController;
import br.edu.ucsal.bd2.controller.CustomerController;
import br.edu.ucsal.bd2.controller.PriceController;
import br.edu.ucsal.bd2.controller.ProductController;
import br.edu.ucsal.bd2.controller.PurchaseController;
import br.edu.ucsal.bd2.controller.SaleController;
import br.edu.ucsal.bd2.controller.StaffController;
import br.edu.ucsal.bd2.controller.StoreController;
import br.edu.ucsal.bd2.controller.SupplierController;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.swagger.v3.oas.models.Paths;

// Ordem do Swagger UI. Sem isto o springdoc gera as seções (tags) e os caminhos numa ordem qualquer, e o Swagger UI
// mostra tudo na ordem em que vem no /v3/api-docs. Aqui as duas listas são reordenadas depois de o documento ser montado.
@Configuration
public class OpenApiConfig {

    // Seções na ordem do roteiro de teste, em que cada passo usa o que os anteriores cadastraram: a loja vem antes do
    // funcionário e da compra, a compra põe o produto no estoque antes da venda, e as consultas ficam no fim, com os
    // dados já lançados. O nome da tag é lido do @Tag de cada controller, então renomear uma tag não quebra a ordem.
    // Uma tag que não esteja na lista vai para o fim, na ordem em que o springdoc a encontrou.
    private static final List<Class<?>> TAG_ORDER = List.of(
            StoreController.class,          // loja: o uuid dela é o header X-Store-Id das operações de loja
            StaffController.class,          // funcionário da loja; depois o PUT da loja define o gerente
            CategoryController.class,       // categoria (o seed já cria as padrão)
            ProductController.class,        // produto de uma categoria
            PriceController.class,          // preço vigente: sem ele o produto não pode ser vendido
            SupplierController.class,       // fornecedor, exigido na compra
            PurchaseController.class,       // compra: dá entrada no estoque da loja
            CustomerController.class,       // cliente (o CPF pode ir na nota da venda)
            SaleController.class,           // venda: precisa de vendedor, preço vigente e estoque
            CriteriaQueryController.class,  // consultas sobre o que foi lançado
            EtlController.class);           // carga do Data Warehouse com as vendas concluídas

    @Bean
    public OpenApiCustomizer sortTagsByTestFlow() {
        List<String> order = TAG_ORDER.stream().map(OpenApiConfig::tagName).toList();
        return openApi -> {
            if (openApi.getTags() == null) {
                return;
            }
            // A ordenação é estável: as tags fora da lista empatam em order.size() e mantêm a ordem entre si.
            openApi.setTags(openApi.getTags().stream()
                    .sorted(Comparator.comparingInt(tag -> {
                        int position = order.indexOf(tag.getName());
                        return position >= 0 ? position : order.size();
                    }))
                    .collect(Collectors.toCollection(ArrayList::new)));
        };
    }

    // Dentro de cada seção, os endpoints aparecem na ordem dos caminhos: o pai antes dos filhos
    // (/api/store antes de /api/store/{storeUuid}). Os caminhos são comparados trecho a trecho, e entre irmãos vale a
    // ordem alfabética, em que o trecho fixo vem antes do variável ('{' fica depois das letras e dígitos).
    // No mesmo caminho, a ordem dos métodos é a do swagger-core (GET, PUT, POST, DELETE) e não muda aqui.
    @Bean
    public OpenApiCustomizer sortPathsByPrecedence() {
        return openApi -> {
            if (openApi.getPaths() == null) {
                return;
            }
            Paths sorted = new Paths();
            openApi.getPaths().entrySet().stream()
                    .sorted(Map.Entry.comparingByKey(PATH_PRECEDENCE))
                    .forEach(path -> sorted.addPathItem(path.getKey(), path.getValue()));
            sorted.setExtensions(openApi.getPaths().getExtensions());
            openApi.setPaths(sorted);
        };
    }

    private static final Comparator<String> PATH_PRECEDENCE = (left, right) -> {
        String[] leftSegments = left.split("/");
        String[] rightSegments = right.split("/");
        for (int i = 0; i < Math.min(leftSegments.length, rightSegments.length); i++) {
            int bySegment = leftSegments[i].compareTo(rightSegments[i]);
            if (bySegment != 0) {
                return bySegment;
            }
        }
        return Integer.compare(leftSegments.length, rightSegments.length);
    };

    private static String tagName(Class<?> controller) {
        Tag tag = AnnotatedElementUtils.findMergedAnnotation(controller, Tag.class);
        if (tag == null) {
            throw new IllegalStateException(controller.getSimpleName() + " está na ordem do Swagger, mas não tem @Tag");
        }
        return tag.name();
    }
}
