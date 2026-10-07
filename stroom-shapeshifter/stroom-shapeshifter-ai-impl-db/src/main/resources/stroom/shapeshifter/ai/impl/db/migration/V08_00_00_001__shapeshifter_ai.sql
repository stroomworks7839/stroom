-- ------------------------------------------------------------------------
-- Copyright 2026 Crown Copyright
--
-- Licensed under the Apache License, Version 2.0 (the "License");
-- you may not use this file except in compliance with the License.
-- You may obtain a copy of the License at
--
--     http://www.apache.org/licenses/LICENSE-2.0
--
-- Unless required by applicable law or agreed to in writing, software
-- distributed under the License is distributed on an "AS IS" BASIS,
-- WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
-- See the License for the specific language governing permissions and
-- limitations under the License.
-- ------------------------------------------------------------------------

-- Stop NOTE level warnings about objects (not)? existing
SET @OLD_SQL_NOTES=@@SQL_NOTES, SQL_NOTES=0;

--
-- The runtime state of Shapeshifter AI (A26), as one migration for Stroom 8.0 (A111). What is learned
-- belongs to a learner: a Shapeshifter AI element in a pipeline, named by the pipeline's UUID and the
-- element's id (design 06, A114). The document a learner runs under is recorded beside it, for the
-- Supervisor to show and to decide who may see a row, but is not part of its key.
--

--
-- What the stage knows about one shape of a document: given up, marked to learn again, a draft waiting
-- for review, and its rolling score. Keyed by the document until design 06 phase J slice 2 replaces it
-- and the rule table with one record per learner. The shape's id has no bound, so it is found by its
-- hash; shape_id carries it as written, for a person reading the row.
--
CREATE TABLE IF NOT EXISTS shapeshifter_shape (
    id                    bigint NOT NULL AUTO_INCREMENT,
    version               int NOT NULL,
    create_time_ms        bigint NOT NULL,
    update_time_ms        bigint NOT NULL,
    doc_uuid              varchar(255) NOT NULL,
    shape_hash            varchar(64) NOT NULL,
    shape_id              longtext NOT NULL,
    given_up_reason       longtext DEFAULT NULL,
    relearn_reason        longtext DEFAULT NULL,
    awaiting_rule_uuid    varchar(255) DEFAULT NULL,
    rolling_score         double DEFAULT NULL,
    rolling_records       int NOT NULL DEFAULT 0,
    PRIMARY KEY           (id),
    UNIQUE KEY            shapeshifter_shape_doc_uuid_shape_hash (doc_uuid, shape_hash)
) ENGINE=InnoDB DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;

--
-- The streams of a given-up shape, kept to be put through again if it is learned after all. One row per
-- stream per shape, so a stream recorded twice is not replayed twice. Keyed by the document and shape
-- until phase J slice 2. pipeline_uuid is the pipeline that was processing the stream.
--
CREATE TABLE IF NOT EXISTS shapeshifter_ledger (
    id                    bigint NOT NULL AUTO_INCREMENT,
    create_time_ms        bigint NOT NULL,
    doc_uuid              varchar(255) NOT NULL,
    shape_hash            varchar(64) NOT NULL,
    shape_id              longtext NOT NULL,
    input_meta_id         bigint NOT NULL,
    reason                longtext NOT NULL,
    pipeline_uuid         varchar(255) DEFAULT NULL,
    PRIMARY KEY           (id),
    UNIQUE KEY            shapeshifter_ledger_doc_shape_input (doc_uuid, shape_hash, input_meta_id),
    KEY                   shapeshifter_ledger_input_meta_id (input_meta_id)
) ENGINE=InnoDB DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;

--
-- What serves a document's shapes: one row per rule, each the binding of the shape it was learned for
-- (design 06 phase J slice 2a). The pipeline_* columns name the fragment a rule binds. Keyed by the
-- document until phase J slice 2b.
CREATE TABLE IF NOT EXISTS shapeshifter_rule (
    id                    bigint NOT NULL AUTO_INCREMENT,
    version               int NOT NULL,
    create_time_ms        bigint NOT NULL,
    update_time_ms        bigint NOT NULL,
    doc_uuid              varchar(255) NOT NULL,
    rule_uuid             varchar(255) NOT NULL,
    pipeline_type         varchar(255) DEFAULT NULL,
    pipeline_uuid         varchar(255) DEFAULT NULL,
    pipeline_name         varchar(255) DEFAULT NULL,
    pinned                tinyint NOT NULL DEFAULT 0,
    draft                 tinyint NOT NULL DEFAULT 0,
    provisional           tinyint NOT NULL DEFAULT 0,
    promoted_time_ms      bigint DEFAULT NULL,
    score                 double DEFAULT NULL,
    boundary_element      varchar(255) DEFAULT NULL,
    boundary_array        varchar(255) DEFAULT NULL,
    boundary_depth        int DEFAULT NULL,
    shape_id              longtext DEFAULT NULL,
    shape_hash            varchar(64) DEFAULT NULL,
    snapshot_id           varchar(255) DEFAULT NULL,
    PRIMARY KEY           (id),
    UNIQUE KEY            shapeshifter_rule_doc_uuid_rule_uuid (doc_uuid, rule_uuid),
    KEY                   shapeshifter_rule_doc_shape (doc_uuid, shape_hash)
) ENGINE=InnoDB DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;

--
-- What a document has spent on its model, counted across the cluster (A44). A budget is the document's
-- setting, so this stays the document's.
--
CREATE TABLE IF NOT EXISTS shapeshifter_spend (
    id                    bigint NOT NULL AUTO_INCREMENT,
    version               int NOT NULL,
    update_time_ms        bigint NOT NULL,
    doc_uuid              varchar(255) NOT NULL,
    window_start_ms       bigint NOT NULL,
    tokens_spent          bigint NOT NULL DEFAULT 0,
    calls_made            int NOT NULL DEFAULT 0,
    breaker_open_since_ms bigint DEFAULT NULL,
    breaker_reason        longtext DEFAULT NULL,
    PRIMARY KEY           (id),
    UNIQUE KEY            shapeshifter_spend_doc_uuid (doc_uuid)
) ENGINE=InnoDB DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;

--
-- One learning session (A28). An open attempt is its learner's claim on the shape it learns (A45): the
-- claim key is the shape's hash while the attempt is open and null once it closes, so one learner has
-- at most one open attempt per shape. The document is the one the session ran under.
--
CREATE TABLE IF NOT EXISTS shapeshifter_attempt (
    id                    bigint NOT NULL AUTO_INCREMENT,
    version               int NOT NULL,
    create_time_ms        bigint NOT NULL,
    update_time_ms        bigint NOT NULL,
    pipeline_uuid         varchar(255) NOT NULL,
    element_id            varchar(255) NOT NULL,
    doc_uuid              varchar(255) NOT NULL,
    shape_hash            varchar(64) NOT NULL,
    shape_id              longtext NOT NULL,
    feed_name             varchar(255) DEFAULT NULL,
    type_name             varchar(255) DEFAULT NULL,
    input_meta_id         bigint DEFAULT NULL,
    node_name             varchar(255) DEFAULT NULL,
    promotion_mode        varchar(32) NOT NULL,
    status                varchar(32) NOT NULL,
    -- What it came to, in the words the decision itself uses, and the rule it wrote where it wrote one
    decision              longtext DEFAULT NULL,
    rule_uuid             varchar(255) DEFAULT NULL,
    score                 double DEFAULT NULL,
    tokens_spent          bigint NOT NULL DEFAULT 0,
    -- When a claim lapses if nothing heartbeats it (A45), for the worker to take up
    expiry_ms             bigint DEFAULT NULL,
    claim_key             varchar(64) DEFAULT NULL,
    granted_tokens        bigint NOT NULL DEFAULT 0,
    granted_ms            bigint NOT NULL DEFAULT 0,
    exhaust_next          tinyint NOT NULL DEFAULT 0,
    PRIMARY KEY           (id),
    UNIQUE KEY            shapeshifter_attempt_claim (pipeline_uuid, element_id, claim_key),
    KEY                   shapeshifter_attempt_learner_shape (pipeline_uuid, element_id, shape_hash),
    KEY                   shapeshifter_attempt_doc_uuid (doc_uuid),
    KEY                   shapeshifter_attempt_status (status),
    KEY                   shapeshifter_attempt_create_time_ms (create_time_ms)
) ENGINE=InnoDB DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;

--
-- One question of a session and its answer.
--
CREATE TABLE IF NOT EXISTS shapeshifter_turn (
    id                    bigint NOT NULL AUTO_INCREMENT,
    create_time_ms        bigint NOT NULL,
    fk_attempt_id         bigint NOT NULL,
    turn_number           int NOT NULL,
    step_id               varchar(255) DEFAULT NULL,
    candidate             int NOT NULL DEFAULT 1,
    question_kind         varchar(32) NOT NULL,
    question              longtext NOT NULL,
    answer                longtext DEFAULT NULL,
    -- The answerer: the model a document names, or the user who answered instead; null while unanswered
    answered_by           varchar(255) DEFAULT NULL,
    outcome               varchar(32) DEFAULT NULL,
    -- The guidance the question carried (A45), so a re-walk replays what was used
    carried_guidance      text DEFAULT NULL,
    PRIMARY KEY           (id),
    UNIQUE KEY            shapeshifter_turn_attempt_turn (fk_attempt_id, turn_number),
    CONSTRAINT            shapeshifter_turn_attempt_fk FOREIGN KEY (fk_attempt_id)
                              REFERENCES shapeshifter_attempt (id)
) ENGINE=InnoDB DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;

--
-- What a rule produced from each stream, so a stream can be reprocessed as it was processed (design 01
-- §7.3). One row per input per rule per pipeline. Keyed by the rule until phase J slice 2.
--
CREATE TABLE IF NOT EXISTS shapeshifter_output (
    id                    bigint NOT NULL AUTO_INCREMENT,
    create_time_ms        bigint NOT NULL,
    doc_uuid              varchar(255) NOT NULL,
    rule_uuid             varchar(255) NOT NULL,
    input_meta_id         bigint NOT NULL,
    pipeline_uuid         varchar(255) NOT NULL DEFAULT '',
    fragment_uuid         varchar(255) NOT NULL,
    provisional           tinyint(1) NOT NULL DEFAULT 0,
    score                 double DEFAULT NULL,
    boundary_element      varchar(255) DEFAULT NULL,
    boundary_array        varchar(255) DEFAULT NULL,
    boundary_depth        int DEFAULT NULL,
    produce_time_ms       bigint NOT NULL DEFAULT 0,
    record_spans          mediumtext DEFAULT NULL,
    snapshot_id           varchar(255) DEFAULT NULL,
    PRIMARY KEY           (id),
    UNIQUE KEY            shapeshifter_output_rule_input_pipeline (rule_uuid, input_meta_id, pipeline_uuid),
    KEY                   shapeshifter_output_doc_uuid (doc_uuid)
) ENGINE=InnoDB DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;

--
-- What a person has said about a shape (A46), carried into every question about it from then on. Keyed
-- by the document and shape until phase J slice 2.
--
CREATE TABLE IF NOT EXISTS shapeshifter_guidance (
    id                    bigint NOT NULL AUTO_INCREMENT,
    create_time_ms        bigint NOT NULL,
    doc_uuid              varchar(255) NOT NULL,
    shape_hash            varchar(64) NOT NULL,
    shape_id              longtext NOT NULL,
    message               longtext NOT NULL,
    -- Who said it. A hint is somebody's, and a person reading one a year later needs to know whose.
    author                varchar(255) NOT NULL,
    PRIMARY KEY           (id),
    KEY                   shapeshifter_guidance_doc_shape (doc_uuid, shape_hash)
) ENGINE=InnoDB DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;

--
-- A stream waiting for its learner (design 04 §3): one row per stream per learner. The document is kept
-- for the Supervisor's backlog, which lists what waits on the documents a person may see, and for what a
-- promotion wakes.
--
CREATE TABLE IF NOT EXISTS shapeshifter_deferral (
    id                    bigint NOT NULL AUTO_INCREMENT,
    pipeline_uuid         varchar(255) NOT NULL,
    element_id            varchar(255) NOT NULL,
    doc_uuid              varchar(255) NOT NULL,
    meta_id               bigint NOT NULL,
    feed_name             varchar(255) NOT NULL,
    type_name             varchar(255) NOT NULL,
    -- The task to wake and the filter it belongs to; null for a stream nothing is processing.
    task_id               bigint DEFAULT NULL,
    filter_id             int DEFAULT NULL,
    -- What the stream was run through and fell short of; null where there was nothing to run it through.
    failed_against        varchar(255) DEFAULT NULL,
    reason                longtext NOT NULL,
    failing_records       int NOT NULL,
    first_deferred_ms     bigint NOT NULL,
    update_time_ms        bigint NOT NULL,
    attempts              int NOT NULL,
    PRIMARY KEY           (id),
    UNIQUE KEY            shapeshifter_deferral_learner_meta (pipeline_uuid, element_id, meta_id),
    -- What a promotion wakes: a document's streams of one feed and type (until phase J slice 2).
    KEY                   shapeshifter_deferral_doc_feed_type (doc_uuid, feed_name, type_name)
) ENGINE=InnoDB DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;

--
-- The records a rule has been held to (design 04 phase 3): what each became when it was accepted.
--
CREATE TABLE IF NOT EXISTS shapeshifter_corpus (
    id                    bigint NOT NULL AUTO_INCREMENT,
    doc_uuid              varchar(255) NOT NULL,
    rule_uuid             varchar(255) NOT NULL,
    meta_id               bigint NOT NULL,
    segment               int NOT NULL,
    -- TRANSLATION, UNKNOWN or NONE: what the record became when it was accepted.
    kind                  varchar(20) NOT NULL,
    score                 double NOT NULL,
    attempt_id            bigint DEFAULT NULL,
    accept_time_ms        bigint NOT NULL,
    PRIMARY KEY           (id),
    KEY                   shapeshifter_corpus_rule (rule_uuid, id),
    KEY                   shapeshifter_corpus_meta (meta_id)
) ENGINE=InnoDB DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;

--
-- An immutable copy of a fragment's configuration as it was promoted (A61): what a rule points at and
-- what an as-processed reprocess runs.
--
CREATE TABLE IF NOT EXISTS shapeshifter_snapshot (
    id                    bigint NOT NULL AUTO_INCREMENT,
    snapshot_id           varchar(255) NOT NULL,
    doc_uuid              varchar(255) NOT NULL,
    rule_uuid             varchar(255) NOT NULL,
    fragment_uuid         varchar(255) NOT NULL,
    -- The fragment's PipelineData, as JSON.
    structure             longtext NOT NULL,
    -- Element id to the code that element ran with, as a JSON object.
    code                  longtext NOT NULL,
    -- Document uuid to the version of it the snapshot was taken from, as a JSON object: how a hand edit
    -- is noticed (A72).
    versions              longtext NOT NULL,
    create_time_ms        bigint NOT NULL,
    PRIMARY KEY           (id),
    UNIQUE KEY            shapeshifter_snapshot_id (snapshot_id),
    KEY                   shapeshifter_snapshot_rule (rule_uuid, id)
) ENGINE=InnoDB DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;

SET SQL_NOTES=@OLD_SQL_NOTES;

-- vim: set shiftwidth=4 tabstop=4 expandtab:
