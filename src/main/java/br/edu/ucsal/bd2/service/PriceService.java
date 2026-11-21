package br.edu.ucsal.bd2.service;

import java.time.Instant;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import br.edu.ucsal.bd2.dao.PriceDAO;
import br.edu.ucsal.bd2.dto.request.PriceRequest;
import br.edu.ucsal.bd2.model.Price;
import br.edu.ucsal.bd2.model.Product;
import jakarta.persistence.EntityExistsException;
import jakarta.persistence.EntityNotFoundException;
import lombok.AllArgsConstructor;

// Preço do produto como histórico: o preço vigente é a linha com deletedAt nulo. Trocar o preço fecha a linha
// antiga (deletedAt = início da nova), então nada se perde e as vendas antigas continuam batendo com o preço da época.
// Produto sem preço vigente não está à venda.
@Service
@AllArgsConstructor
public class PriceService {

    // Teto para o valor de uma venda (quantidade x preço) caber sempre num long.
    private static final long MAX_PRICE_IN_CENTS = 1_000_000_000L;

    private final PriceDAO dao;

    public Price findCurrent(UUID productUuid) {
        Product product = findProduct(productUuid, false);
        return currentOrThrow(product);
    }

    public Page<Price> findHistory(UUID productUuid, Pageable pageable) {
        Product product = findProduct(productUuid, false);
        return dao.findHistory(product, pageable);
    }

    // Define o primeiro preço. Para mudar um preço que já existe, é o PUT.
    @Transactional
    public Price createPrice(UUID productUuid, PriceRequest dto) {
        long value = validateValue(dto);
        Product product = findProduct(productUuid, true);

        if (dao.findCurrent(product).isPresent()) {
            throw new EntityExistsException("O produto '" + product.getName() + "' já tem preço vigente. Use PUT para alterá-lo.");
        }

        return insert(product, value);
    }

    // Troca o preço vigente: grava o novo e fecha o antigo. Se o valor é o mesmo, não muda nada (nem o histórico).
    @Transactional
    public Price updatePrice(UUID productUuid, PriceRequest dto) {
        long value = validateValue(dto);
        Product product = findProduct(productUuid, true);
        Price current = dao.findCurrent(product).orElseThrow(() -> new EntityNotFoundException(
                "O produto '" + product.getName() + "' não tem preço vigente. Use POST para definir o primeiro preço."));

        if (current.getValueInCents() == value) {
            return current;
        }

        Price created = insert(product, value);
        current.setDeletedAt(created.getCreatedAt());
        dao.update(current);
        return created;
    }

    // Tira o produto de venda: fecha o preço vigente sem colocar outro no lugar. O histórico fica.
    @Transactional
    public void deletePrice(UUID productUuid) {
        Product product = findProduct(productUuid, true);
        Price current = currentOrThrow(product);

        current.setDeletedAt(Instant.now());
        dao.update(current);
    }

    private Product findProduct(UUID productUuid, boolean lockForUpdate) {
        return dao.findActiveProduct(productUuid, lockForUpdate)
                .orElseThrow(() -> new EntityNotFoundException("Produto com uuid: " + productUuid + " não encontrado."));
    }

    private Price currentOrThrow(Product product) {
        return dao.findCurrent(product).orElseThrow(
                () -> new EntityNotFoundException("O produto '" + product.getName() + "' não tem preço vigente."));
    }

    private Price insert(Product product, long value) {
        Price price = new Price();
        price.setProduct(product);
        price.setValueInCents(value);
        dao.save(price);
        return price;
    }

    private long validateValue(PriceRequest dto) {
        Long value = dto == null ? null : dto.valueInCents();
        if (value == null || value <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "O preço deve ser maior que zero (em centavos).");
        }
        if (value > MAX_PRICE_IN_CENTS) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "O preço não pode passar de " + MAX_PRICE_IN_CENTS + " centavos.");
        }
        return value;
    }
}
