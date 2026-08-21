package io.ib67.prts;

import lombok.experimental.UtilityClass;

@UtilityClass
public class Perms {
    public static final String ADMIN_OF_ALL = "admin:all";

    public static final String JOB_CREATE = "job:create";
    public static final String JOB_CANCEL = "job:cancel";
    public static final String JOB_SPEC_IMAGE = "job:spec:image";
    public static final String JOB_SPEC_ENVIRONMENT = "job:spec:environment";
    public static final String JOB_SPEC_SECRETS = "job:spec:secrets";
    public static final String JOB_SPEC_LABELS = "job:spec:labels";
    public static final String JOB_SPEC_COMMAND = "job:spec:command";
    public static final String JOB_SPEC_VOLUMES = "job:spec:volumes";
    public static final String JOB_SPEC_TIMEOUT = "job:spec:timeout";
    public static final String JOB_SPEC_LOCK = "job:spec:lock";
    public static final String JOB_RESOURCE_CLASS = "job:resource-class";
}
