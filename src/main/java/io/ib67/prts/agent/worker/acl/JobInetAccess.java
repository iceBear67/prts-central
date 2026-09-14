package io.ib67.prts.agent.worker.acl;

import java.util.regex.Pattern;

/**
 * Internet access rules for job execution containers.
 */
public sealed interface JobInetAccess {
    /**
     * Grants unrestricted internet access.
     */
    record FullAccess() implements JobInetAccess {
    }

    /**
     * Restricts traffic to domains matching the specified pattern.
     */
    record DomainAccess(Pattern pattern) implements JobInetAccess {
    }

    /**
     * Restricts traffic to IP addresses within the specified CIDR block.
     */
    record IPAccess(String cidrNotation) implements JobInetAccess {
    }
}
