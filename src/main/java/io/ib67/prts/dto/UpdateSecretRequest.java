package io.ib67.prts.dto;

/**
 * @param description null or blank clears it. There is no value field: a value is replaced by a
 *                    delete and a create, so that fixing a description cannot touch one.
 */
public record UpdateSecretRequest(
        String description
) {
}
