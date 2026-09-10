package io.ib67.prts.agent.worker.acl;

import java.util.regex.Pattern;

/**
 * This type of data constraints internet access for job workers.
 */
public sealed interface JobInetAccess {
    /**
     * When any of FullAccess is present in a list, the job has access to open internet.
     */
    record FullAccess() implements JobInetAccess {
    }

    /**
     * Only traffics sniffed with this destined domain are allowed.
     * @param pattern regex
     */
    record DomainAccess(Pattern pattern) implements JobInetAccess {
    }

    record IPAccess(String cidrNotation) implements JobInetAccess {
    }
}
