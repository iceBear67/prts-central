package io.ib67.prts.dto.request;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/**
 * Request payload to hand a project's ownership to another member.
 *
 * @param userId The member to make owner.
 */
public record TransferProjectRequest(@NotNull(message = "userId is required") UUID userId) {
}
