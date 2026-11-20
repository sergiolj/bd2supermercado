package br.edu.ucsal.bd2.dto.response;

import java.util.UUID;

import br.edu.ucsal.bd2.model.Address;
import br.edu.ucsal.bd2.model.Staff;
import br.edu.ucsal.bd2.model.Store;

// O id interno do banco nunca sai pela API: a loja e o gerente são identificados pelo uuid.
// managerUuid e managerName são nulos quando a loja não tem gerente.
public record StoreResponse(
        UUID uuid,
        String name,
        UUID managerUuid,
        String managerName,
        String street,
        String number,
        String district,
        String city,
        String state,
        String zipCode
) {

    public static StoreResponse convertToDTO(Store store) {
        // Se todas as colunas do endereço forem nulas, o Hibernate devolve address = null
        Address address = store.getAddress();
        if (address == null) {
            address = new Address();
        }
        Staff manager = store.getManager();

        return new StoreResponse(
                store.getUuid(),
                store.getName(),
                manager == null ? null : manager.getUuid(),
                manager == null ? null : manager.getName(),
                address.getStreet(),
                address.getNumber(),
                address.getDistrict(),
                address.getCity(),
                address.getState(),
                address.getZipCode()
        );
    }
}
