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
-- The runtime state of a shape (A26): what the stage knows about one learning-key value of one document
-- — feed and type by default, the record shape signature where the key includes it. The lease of A42
-- lives here too: one learner per shape, held by a node until it expires.
--
CREATE TABLE IF NOT EXISTS shapeshifter_shape (
    id                    bigint NOT NULL AUTO_INCREMENT,
    version               int NOT NULL,
    create_time_ms        bigint NOT NULL,
    update_time_ms        bigint NOT NULL,
    doc_uuid              varchar(255) NOT NULL,
    shape_id              varchar(500) NOT NULL,
    given_up_reason       longtext DEFAULT NULL,
    relearn_reason        longtext DEFAULT NULL,
    awaiting_rule_uuid    varchar(255) DEFAULT NULL,
    rolling_score         double DEFAULT NULL,
    rolling_records       int NOT NULL DEFAULT 0,
    lease_node            varchar(255) DEFAULT NULL,
    lease_expiry_ms       bigint DEFAULT NULL,
    PRIMARY KEY           (id),
    UNIQUE KEY            shapeshifter_shape_doc_uuid_shape_id (doc_uuid, shape_id)
) ENGINE=InnoDB DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;

--
-- One sentinelled input (§5.2): a stream of a shape that had no binding when it arrived, kept so that
-- promotion can release it as a reprocess filter. Nothing is held — this names what to replay.
--
CREATE TABLE IF NOT EXISTS shapeshifter_ledger (
    id                    bigint NOT NULL AUTO_INCREMENT,
    create_time_ms        bigint NOT NULL,
    doc_uuid              varchar(255) NOT NULL,
    shape_id              varchar(500) NOT NULL,
    input_meta_id         bigint NOT NULL,
    reason                longtext NOT NULL,
    PRIMARY KEY           (id),
    KEY                   shapeshifter_ledger_doc_uuid_shape_id (doc_uuid, shape_id),
    KEY                   shapeshifter_ledger_input_meta_id (input_meta_id)
) ENGINE=InnoDB DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;

--
-- The error-mode state of one feed under one document (A24): the failure streak, and since when.
--
CREATE TABLE IF NOT EXISTS shapeshifter_feed_state (
    id                    bigint NOT NULL AUTO_INCREMENT,
    version               int NOT NULL,
    update_time_ms        bigint NOT NULL,
    doc_uuid              varchar(255) NOT NULL,
    feed_name             varchar(255) NOT NULL,
    failure_streak        int NOT NULL DEFAULT 0,
    error_mode_since_ms   bigint DEFAULT NULL,
    error_mode_reason     longtext DEFAULT NULL,
    reset_time_ms         bigint DEFAULT NULL,
    reset_user            varchar(255) DEFAULT NULL,
    PRIMARY KEY           (id),
    UNIQUE KEY            shapeshifter_feed_state_doc_uuid_feed_name (doc_uuid, feed_name)
) ENGINE=InnoDB DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;

--
-- A routing rule (A41): learned or written by an operator, a row rather than a field on the document,
-- so that two nodes promoting two shapes write two rows. Order decides which rule the router takes
-- first, and is kept in sort_order rather than in the row's id, since an operator may move a rule.
--
CREATE TABLE IF NOT EXISTS shapeshifter_rule (
    id                    bigint NOT NULL AUTO_INCREMENT,
    version               int NOT NULL,
    create_time_ms        bigint NOT NULL,
    update_time_ms        bigint NOT NULL,
    doc_uuid              varchar(255) NOT NULL,
    rule_uuid             varchar(255) NOT NULL,
    sort_order            int NOT NULL,
    expression            longtext DEFAULT NULL,
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
    PRIMARY KEY           (id),
    UNIQUE KEY            shapeshifter_rule_doc_uuid_rule_uuid (doc_uuid, rule_uuid),
    KEY                   shapeshifter_rule_doc_uuid_sort_order (doc_uuid, sort_order)
) ENGINE=InnoDB DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;

--
-- The spend of one document (A44): a cluster-wide token bucket, since a budget divided by node count
-- is not a budget and a feed burning spend on one node is invisible to the others.
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

SET SQL_NOTES=@OLD_SQL_NOTES;

-- vim: set shiftwidth=4 tabstop=4 expandtab:
