package br.edu.ucsal.bd2.service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import br.edu.ucsal.bd2.dao.InventoryMovementDAO;
import br.edu.ucsal.bd2.dao.PriceDAO;
import br.edu.ucsal.bd2.dao.SaleDAO;
import br.edu.ucsal.bd2.dto.request.PaymentRequest;
import br.edu.ucsal.bd2.dto.request.SaleItemRequest;
import br.edu.ucsal.bd2.dto.request.SaleRequest;
import br.edu.ucsal.bd2.model.Inventory;
import br.edu.ucsal.bd2.model.InventoryMovement;
import br.edu.ucsal.bd2.model.Payment;
import br.edu.ucsal.bd2.model.Price;
import br.edu.ucsal.bd2.model.Product;
import br.edu.ucsal.bd2.model.Sale;
import br.edu.ucsal.bd2.model.SaleItem;
import br.edu.ucsal.bd2.model.Staff;
import br.edu.ucsal.bd2.model.Store;
import br.edu.ucsal.bd2.repository.InventoryRepository;
import br.edu.ucsal.bd2.repository.ProductRepository;
import br.edu.ucsal.bd2.util.MovementType;
import br.edu.ucsal.bd2.util.PaymentStatus;
import br.edu.ucsal.bd2.util.QuantityRules;
import br.edu.ucsal.bd2.util.SaleStatus;
import jakarta.persistence.EntityNotFoundException;
import lombok.AllArgsConstructor;

/**
 * Venda de uma loja (header X-Store-Id), do carrinho ao pagamento:
 *
 *   1. Abertura: a venda nasce em aberto (OPEN) com os itens, cada um com o preço vigente do produto naquele momento.
 *      Todo item precisa ter saldo no estoque da loja, mas nada sai do estoque ainda.
 *   2. Pagamento: cada pagamento recebido abate do total, e a venda pode ser paga em partes (ex.: PIX + cartão).
 *   3. Efetivação: o pagamento que quita o total conclui a venda (COMPLETED) e, na mesma transação, lança uma
 *      movimentação SALE por item; o trigger do banco baixa o estoque a cada uma. Se algum item já não tiver saldo,
 *      nada é gravado (nem o pagamento, nem as baixas) e a venda continua em aberto.
 *   4. Cancelamento: só de venda em aberto. Os pagamentos já recebidos são estornados (REFUNDED) e o estoque não é
 *      tocado, porque ainda não tinha saído.
 *
 * Venda concluída é definitiva: as movimentações de estoque são imutáveis no banco e devolução não faz parte deste fluxo.
 * Uma loja não enxerga a venda de outra: para ela, o uuid não existe (404).
 */
@Service
@AllArgsConstructor
public class SaleService {

    private final SaleDAO dao;
    private final InventoryMovementDAO movementDao;
    private final PriceDAO priceDao;
    private final InventoryRepository inventoryRepository;
    private final ProductRepository productRepository;
    private final StaffService staffService;
    private final StoreService storeService;

    @Transactional(readOnly = true)
    public Sale findByUuid(UUID storeUuid, UUID saleUuid) {
        return findInStore(storeService.findByUuid(storeUuid), saleUuid, false);
    }

    @Transactional
    public Sale openSale(UUID storeUuid, SaleRequest dto) {
        Store store = storeService.findByUuid(storeUuid);
        Staff seller = findSeller(store, dto.sellerUuid());

        if (dto.items() == null || dto.items().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A venda precisa de pelo menos um item.");
        }

        Sale sale = new Sale();
        sale.setStore(store);
        sale.setSeller(seller);
        sale.setClientTaxNumber(normalizeClientTaxNumber(dto.clientTaxNumber()));
        sale.setStatus(SaleStatus.OPEN);
        sale.setTotalAmountInCents(addItemsAndCalculateTotal(sale, dto.items()));

        // Só confere: o saldo pode mudar até o pagamento; na efetivação, ele é conferido de novo e travado.
        checkStock(store, sale.getItems(), false);

        dao.save(sale);
        return sale;
    }

    private long addItemsAndCalculateTotal(Sale sale, List<SaleItemRequest> lines) {
        Set<UUID> productsInSale = new HashSet<>();
        long totalInCents = 0;

        for (SaleItemRequest line : lines) {
            SaleItem item = buildItem(line, productsInSale);
            item.setSale(sale);
            sale.getItems().add(item);
            totalInCents = addToTotal(totalInCents, item.totalInCents());
        }

        return totalInCents;
    }



    // Registra um pagamento recebido. O que quita o total efetiva a venda: COMPLETED e baixa de estoque, tudo na mesma
    // transação. Se a baixa falhar, o pagamento também não fica gravado.
    @Transactional
    public Sale registerPayment(UUID storeUuid, UUID saleUuid, PaymentRequest dto) {
        Store store = storeService.findByUuid(storeUuid);

        if (dto.method() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Forma de pagamento é obrigatória.");
        }
        if (dto.amountInCents() == null || dto.amountInCents() <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "O valor do pagamento deve ser maior que zero (em centavos).");
        }

        Sale sale = findInStore(store, saleUuid, true);
        requireOpen(sale, "Só uma venda em aberto recebe pagamento");

        long remaining = sale.remainingInCents();
        if (dto.amountInCents() > remaining) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "O pagamento de " + dto.amountInCents() + " centavos passa do que falta pagar (" + remaining + " centavos).");
        }

        boolean paysInFull = dto.amountInCents() == remaining;
        if (paysInFull) {
            // Confere antes de gravar qualquer coisa, com as linhas do estoque travadas até o commit.
            checkStock(store, sale.getItems(), true);
        }

        Payment payment = new Payment();
        payment.setSale(sale);
        payment.setMethod(dto.method());
        payment.setAmountInCents(dto.amountInCents());
        payment.setStatus(PaymentStatus.PAID);
        payment.setPaidAt(Instant.now());
        dao.savePayment(payment);
        sale.getPayments().add(payment);

        if (paysInFull) {
            complete(sale);
        }
        return sale;
    }

    // Desiste da venda em aberto: o que já foi pago é estornado. Venda concluída não é cancelada aqui, porque o estoque
    // já saiu e a movimentação não pode ser desfeita por este fluxo.
    @Transactional
    public Sale cancelSale(UUID storeUuid, UUID saleUuid) {
        Sale sale = findInStore(storeService.findByUuid(storeUuid), saleUuid, true);
        requireOpen(sale, "Só uma venda em aberto pode ser cancelada");

        for (Payment payment : sale.getPayments()) {
            if (payment.getStatus() == PaymentStatus.PAID) {
                payment.setStatus(PaymentStatus.REFUNDED);
            }
        }
        sale.setStatus(SaleStatus.CANCELED);
        return sale;
    }

    // Efetivação: uma movimentação SALE por item, na loja da venda. O estoque não é escrito aqui: o trigger do banco
    // baixa o saldo a cada movimentação inserida (e recusaria se ficasse negativo).
    private void complete(Sale sale) {
        for (SaleItem item : sale.getItems()) {
            InventoryMovement movement = new InventoryMovement();
            movement.setType(MovementType.SALE);
            movement.setStore(sale.getStore());
            movement.setProduct(item.getProduct());
            movement.setSale(sale);
            movement.setQuantity(item.getQuantity());
            movementDao.save(movement);
        }
        sale.setStatus(SaleStatus.COMPLETED);
    }

    private Sale findInStore(Store store, UUID saleUuid, boolean lockForUpdate) {
        return dao.findInStore(store, saleUuid, lockForUpdate)
                .orElseThrow(() -> new EntityNotFoundException(
                        "Venda com uuid: " + saleUuid + " não encontrada na loja '" + store.getName() + "'."));
    }

    // O vendedor é um funcionário ativo da loja da venda (o banco confere também, com um trigger em sale).
    private Staff findSeller(Store store, UUID sellerUuid) {
        if (sellerUuid == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Vendedor é obrigatório.");
        }

        Staff seller = staffService.findByUuid(sellerUuid);
        if (seller.getStore() == null || !seller.getStore().getUuid().equals(store.getUuid())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "O funcionário '" + seller.getName() + "' não trabalha na loja '" + store.getName()
                            + "': o vendedor precisa ser da loja da venda.");
        }
        return seller;
    }

    private SaleItem buildItem(SaleItemRequest line, Set<UUID> productsInSale) {
        if (line == null || line.productUuid() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Todo item precisa do produto.");
        }
        if (!productsInSale.add(line.productUuid())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "O produto com uuid: " + line.productUuid()
                    + " aparece em mais de um item: junte as quantidades num item só.");
        }

        Product product = productRepository.findByUuidAndDeletedAtIsNull(line.productUuid())
                .orElseThrow(() -> new EntityNotFoundException("Produto com uuid: " + line.productUuid() + " não encontrado."));
        Price price = priceDao.findCurrent(product)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT,
                        "O produto '" + product.getName() + "' não está à venda: não tem preço vigente."));

        SaleItem item = new SaleItem();
        item.setProduct(product);
        item.setQuantity(QuantityRules.validate(product, line.quantity()));
        item.setUnitPriceInCents(price.getValueInCents());

        // Ex.: 1 g de um produto de R$ 0,01 o kg. Uma linha de valor zero deixaria a venda impossível de pagar.
        if (item.totalInCents() == 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "O item '" + product.getName() + "' fica abaixo de 1 centavo (quantidade x preço): aumente a quantidade.");
        }
        return item;
    }

    // Todo item precisa caber no saldo da loja. Com lockForUpdate, as linhas do estoque ficam travadas até o commit,
    // então o saldo conferido aqui é o mesmo que o trigger vai baixar logo depois.
    private void checkStock(Store store, List<SaleItem> items, boolean lockForUpdate) {
        List<Product> products = items.stream().map(SaleItem::getProduct).toList();
        List<Inventory> rows = lockForUpdate
                ? inventoryRepository.lockByStoreAndProducts(store, products)
                : inventoryRepository.findByStoreAndProductIn(store, products);

        // Pelo id: ele vem da chave estrangeira, sem carregar o produto de cada linha.
        Map<Long, BigDecimal> available = rows.stream()
                .collect(Collectors.toMap(row -> row.getProduct().getId(), Inventory::getTotalQuantity));

        List<String> missing = new ArrayList<>();
        for (SaleItem item : items) {
            BigDecimal stock = available.getOrDefault(item.getProduct().getId(), BigDecimal.ZERO);
            if (stock.compareTo(item.getQuantity()) < 0) {
                missing.add("'" + item.getProduct().getName() + "' (pedido " + plain(item.getQuantity())
                        + ", disponível " + plain(stock) + ")");
            }
        }

        if (!missing.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Estoque insuficiente na loja '" + store.getName() + "' para: " + String.join(", ", missing) + ".");
        }
    }

    private static void requireOpen(Sale sale, String rule) {
        if (sale.getStatus() != SaleStatus.OPEN) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, rule + "; esta está " + sale.getStatus() + ".");
        }
    }

    // Cada linha cabe num long (QuantityRules.MAX_QUANTITY x o teto de preço do PriceService), mas a soma de muitas não.
    private static long addToTotal(long total, long line) {
        try {
            return Math.addExact(total, line);
        } catch (ArithmeticException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "O total da venda é grande demais.");
        }
    }

    // CPF/CNPJ na nota é opcional; quando vem, guarda só os dígitos.
    private static String normalizeClientTaxNumber(String taxNumber) {
        if (taxNumber == null || taxNumber.isBlank()) {
            return null;
        }
        String digits = taxNumber.replaceAll("[.\\-/\\s]", "");
        if (!digits.matches("\\d{11}|\\d{14}")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "CPF/CNPJ na nota deve ter 11 (CPF) ou 14 (CNPJ) dígitos.");
        }
        return digits;
    }

    private static String plain(BigDecimal quantity) {
        return quantity.stripTrailingZeros().toPlainString();
    }
}
