package br.edu.ucsal.bd2.util;

import java.math.BigDecimal;
import java.math.RoundingMode;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import br.edu.ucsal.bd2.model.Product;

// Regras da quantidade de um produto, comuns à compra e ao item de venda. As colunas inventory_movement.quantity e
// sale_items.quantity são numeric(12,3): no máximo 9 dígitos inteiros e 3 casas decimais. Fora disso o banco recusaria
// o INSERT e a API responderia 500 em vez de 400.
public final class QuantityRules {

    public static final int MAX_SCALE = 3;
    public static final BigDecimal MAX_QUANTITY = new BigDecimal("999999999.999");

    private QuantityRules() {
    }

    // Devolve a quantidade com 3 casas, como fica no banco. Produto por unidade (UNIT) só aceita número inteiro.
    public static BigDecimal validate(Product product, BigDecimal quantity) {
        if (quantity == null || quantity.signum() <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A quantidade deve ser maior que zero.");
        }

        // stripTrailingZeros: "24.000" e "24" valem o mesmo; só importa se sobra parte fracionária.
        int scale = quantity.stripTrailingZeros().scale();
        if (scale > MAX_SCALE) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A quantidade aceita no máximo 3 casas decimais.");
        }
        if (product.getUnitOfMeasure() == MeasurementType.UNIT && scale > 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "O produto '" + product.getName() + "' é medido por unidade: a quantidade deve ser inteira.");
        }
        if (quantity.compareTo(MAX_QUANTITY) > 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A quantidade é grande demais.");
        }
        return quantity.setScale(MAX_SCALE, RoundingMode.UNNECESSARY);
    }
}
