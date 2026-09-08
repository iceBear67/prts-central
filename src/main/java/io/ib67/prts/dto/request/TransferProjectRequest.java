package io.ib67.prts.dto.request;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/**
 * Request payload to transfer project ownership to another member.
 *
 * @param userId The user ID of the new owner.
 */
public record TransferProjectRequest(@NotNull(message = "userId is required") UUID userId) {
}
