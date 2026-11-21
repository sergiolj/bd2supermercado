package br.edu.ucsal.bd2.dto.response;

import java.util.UUID;

import br.edu.ucsal.bd2.model.Address;
import br.edu.ucsal.bd2.model.Staff;
import br.edu.ucsal.bd2.model.Store;
import br.edu.ucsal.bd2.util.StaffRole;

// O id interno do banco nunca sai pela API: o funcionário e a loja são identificados pelo uuid.
// storeUuid e storeName são nulos só para o ADMIN, que é da rede.
public record StaffResponse(
        UUID uuid,
        String taxNumber,
        String name,
        StaffRole role,
        UUID storeUuid,
        String storeName,
        String email,
        String phone,
        String street,
        String number,
        String district,
        String city,
        String state,
        String zipCode
) {

    public static StaffResponse convertToDTO(Staff staff) {
        // Se todas as colunas do endereço forem nulas, o Hibernate devolve address = null
        Address address = staff.getAddress();
        if (address == null) {
            address = new Address();
        }
        Store store = staff.getStore();

        return new StaffResponse(
                staff.getUuid(),
                staff.getTaxNumber(),
                staff.getName(),
                staff.getRole(),
                store == null ? null : store.getUuid(),
                store == null ? null : store.getName(),
                staff.getEmail(),
                staff.getPhone(),
                address.getStreet(),
                address.getNumber(),
                address.getDistrict(),
                address.getCity(),
                address.getState(),
                address.getZipCode()
        );
    }
}
