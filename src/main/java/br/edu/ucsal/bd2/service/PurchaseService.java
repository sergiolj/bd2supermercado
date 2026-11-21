package br.edu.ucsal.bd2.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.UUID;

import org.springframework.core.NestedExceptionUtils;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import br.edu.ucsal.bd2.dao.InventoryMovementDAO;
import br.edu.ucsal.bd2.dto.request.PurchaseRequest;
import br.edu.ucsal.bd2.model.InventoryMovement;
import br.edu.ucsal.bd2.model.Product;
import br.edu.ucsal.bd2.model.Store;
import br.edu.ucsal.bd2.model.Supplier;
import br.edu.ucsal.bd2.repository.ProductRepository;
import br.edu.ucsal.bd2.repository.SupplierRepository;
import br.edu.ucsal.bd2.util.MeasurementType;
import br.edu.ucsal.bd2.util.MovementType;
import jakarta.persistence.EntityNotFoundException;
import lombok.AllArgsConstructor;

/**
 * Lançamentos de compra (reposição de estoque): InventoryMovement do tipo PURCHASE.
 *
 * Cada compra é de uma loja (header X-Store-Id) e só entra no estoque dela. Uma loja não enxerga nem apaga as compras
 * de outra: para ela, o uuid de outra loja não existe (404).
 *
 * O estoque (inventory) não é escrito aqui: o trigger do banco soma a quantidade ao registrar a compra e a desfaz
 * ao apagá-la (e recusa se o saldo ficaria negativo). A movimentação é imutável no banco (UPDATE é proibido), então
 * não existe edição: para corrigir um lançamento, apaga-se e lança-se outro.
 */
@Service
@AllArgsConstructor
public class PurchaseService {

    // inventory_movement.quantity é numeric(12,3): no máximo 9 dígitos inteiros e 3 casas decimais.
    private static final int MAX_QUANTITY_SCALE = 3;
    private static final BigDecimal MAX_QUANTITY = new BigDecimal("999999999.999");

    // Teto para quantidade x custo caber sempre num long (999.999.999,999 x 1.000.000.000 < 9,2 x 10^18).
    private static final long MAX_UNIT_COST_IN_CENTS = 1_000_000_000L;

    // Mensagem levantada pelo trigger de estoque (db/inventory_trigger.sql) quando a operação deixaria o saldo negativo.
    private static final String TRIGGER_STOCK_MESSAGE = "Estoque insuficiente";

    private final InventoryMovementDAO dao;
    private final ProductRepository productRepository;
    private final SupplierRepository supplierRepository;
    private final StoreService storeService;

    public Page<InventoryMovement> findAll(UUID storeUuid, Pageable pageable) {
        return dao.findPurchases(storeService.findByUuid(storeUuid), pageable);
    }

    public InventoryMovement findByUuid(UUID storeUuid, UUID purchaseUuid) {
        return findInStore(storeService.findByUuid(storeUuid), purchaseUuid);
    }

    @Transactional
    public InventoryMovement registerPurchase(UUID storeUuid, PurchaseRequest dto) {
        Store store = storeService.findByUuid(storeUuid);

        if (dto.productUuid() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Produto é obrigatório.");
        }
        if (dto.supplierUuid() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Fornecedor é obrigatório.");
        }

        Product product = productRepository.findByUuidAndDeletedAtIsNull(dto.productUuid())
                .orElseThrow(() -> new EntityNotFoundException("Produto com uuid: " + dto.productUuid() + " não encontrado."));
        Supplier supplier = supplierRepository.findByUuidAndDeletedAtIsNull(dto.supplierUuid())
                .orElseThrow(() -> new EntityNotFoundException("Fornecedor com uuid: " + dto.supplierUuid() + " não encontrado."));

        InventoryMovement purchase = new InventoryMovement();
        purchase.setType(MovementType.PURCHASE);
        purchase.setStore(store);
        purchase.setProduct(product);
        purchase.setSupplier(supplier);
        purchase.setQuantity(validateQuantity(product, dto.quantity()));
        purchase.setUnitCostInCents(validateUnitCost(dto.unitCostInCents()));
        purchase.setBatchExpiresAt(validateBatchExpiration(dto.batchExpiresAt()));

        dao.save(purchase);
        return purchase;
    }

    // Hard delete: é um lançamento feito por engano. O trigger tira a quantidade do estoque; se parte dela já foi
    // vendida ou perdida, o saldo ficaria negativo e a exclusão é recusada.
    @Transactional
    public void deletePurchase(UUID storeUuid, UUID purchaseUuid) {
        InventoryMovement purchase = findInStore(storeService.findByUuid(storeUuid), purchaseUuid);

        try {
            dao.removeAndFlush(purchase);
        } catch (DataIntegrityViolationException e) {
            String reason = NestedExceptionUtils.getMostSpecificCause(e).getMessage();
            if (reason != null && reason.contains(TRIGGER_STOCK_MESSAGE)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "Não é possível apagar a compra de '" + purchase.getProduct().getName()
                        + "': parte da quantidade já saiu do estoque (venda ou perda).");
            }
            throw e;
        }
    }

    private InventoryMovement findInStore(Store store, UUID purchaseUuid) {
        return dao.findPurchaseByUuid(store, purchaseUuid)
                .orElseThrow(() -> new EntityNotFoundException(
                        "Compra com uuid: " + purchaseUuid + " não encontrada na loja '" + store.getName() + "'."));
    }

    private BigDecimal validateQuantity(Product product, BigDecimal quantity) {
        if (quantity == null || quantity.signum() <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A quantidade deve ser maior que zero.");
        }

        // stripTrailingZeros: "24.000" e "24" valem o mesmo; só importa se sobra parte fracionária.
        int scale = quantity.stripTrailingZeros().scale();
        if (scale > MAX_QUANTITY_SCALE) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A quantidade aceita no máximo 3 casas decimais.");
        }
        if (product.getUnitOfMeasure() == MeasurementType.UNIT && scale > 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "O produto '" + product.getName() + "' é comprado por unidade: a quantidade deve ser inteira.");
        }
        if (quantity.compareTo(MAX_QUANTITY) > 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A quantidade é grande demais.");
        }
        return quantity.setScale(MAX_QUANTITY_SCALE, RoundingMode.UNNECESSARY);
    }

    private Long validateUnitCost(Long unitCostInCents) {
        if (unitCostInCents == null || unitCostInCents <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "O custo unitário deve ser maior que zero (em centavos).");
        }
        if (unitCostInCents > MAX_UNIT_COST_IN_CENTS) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "O custo unitário não pode passar de " + MAX_UNIT_COST_IN_CENTS + " centavos.");
        }
        return unitCostInCents;
    }

    // A validade é opcional, mas um lote que já venceu não faz sentido numa compra nova.
    private LocalDate validateBatchExpiration(LocalDate batchExpiresAt) {
        if (batchExpiresAt != null && batchExpiresAt.isBefore(LocalDate.now(CriteriaQueryService.STORE_ZONE))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A validade do lote não pode ser uma data que já passou.");
        }
        return batchExpiresAt;
    }
}
