package br.edu.ucsal.bd2.dto.response;

import java.util.UUID;

import br.edu.ucsal.bd2.model.Category;

public record CategoryResponse(
        UUID uuid,
        String name
) {

    public static CategoryResponse convertToDTO(Category category) {
        return new CategoryResponse(
                category.getUuid(),
                category.getName()
        );
    }
}
