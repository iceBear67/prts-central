CREATE TABLE "project"
(
    id   uuid    NOT NULL PRIMARY KEY, -- auto generated uuid v7
    name varchar NOT NULL
);

CREATE TABLE prts_user
(
    id         uuid        NOT NULL PRIMARY KEY,
    name       varchar     NOT NULL,
    email      varchar     NOT NULL,
    created_at timestamptz NOT NULL
);

CREATE TABLE "job"
(
    id           uuid        NOT NULL PRIMARY KEY, -- auto generated uuid v7
    project_id   uuid        NOT NULL,
    created_at   timestamptz NOT NULL,
    completed_at timestamptz,
    success      bool,                             -- tristate, false -> fail
    FOREIGN KEY (project_id)
        REFERENCES project (id)
        ON DELETE CASCADE,
    CHECK (
        (completed_at IS NULL AND success IS NULL)
            OR
        (completed_at IS NOT NULL AND success IS NOT NULL)
        )
);

CREATE INDEX idx_job_project_id ON job (project_id);

CREATE TABLE "artifact"
(
    id         uuid   NOT NULL PRIMARY KEY, -- auto generated uuid v7
    object_key text   NOT NULL,
    size_bytes bigint NOT NULL,
    job_id     uuid   NOT NULL,
    FOREIGN KEY (job_id) REFERENCES job (id) ON DELETE CASCADE
);

CREATE INDEX idx_artifact_job_id
    ON artifact (job_id);

CREATE TABLE "job_log"
(
    id         bigint      NOT NULL
        GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    job_id     uuid        NOT NULL,
    created_at timestamptz NOT NULL,
    topic      varchar,
    message    text,
    error      boolean,
    FOREIGN KEY (job_id) REFERENCES job (id) ON DELETE CASCADE
);

CREATE INDEX idx_job_log
    ON job_log (job_id, created_at);

CREATE TABLE "user_to_project"
(
    user_id    uuid NOT NULL,
    project_id uuid NOT NULL,
    -- projectRole: OWNER, MEMBER, VIEWER, NONE
    projectRole int  NOT NULL CHECK (projectRole > -1 AND projectRole < 4),
    PRIMARY KEY (user_id, project_id),
    FOREIGN KEY (user_id) REFERENCES prts_user (id) ON DELETE CASCADE,
    FOREIGN KEY (project_id) REFERENCES project (id) ON DELETE CASCADE
);

CREATE TABLE "oauth_identity"
(
    issuer  varchar NOT NULL,
    subject varchar NOT NULL,
    user_id uuid    NOT NULL
        REFERENCES prts_user (id)
            ON DELETE CASCADE,
    PRIMARY KEY (issuer, subject)
);
