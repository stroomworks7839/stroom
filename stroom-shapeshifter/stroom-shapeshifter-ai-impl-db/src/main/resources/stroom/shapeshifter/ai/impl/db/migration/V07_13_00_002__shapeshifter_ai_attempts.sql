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
-- One attempt at learning a shape (A28): the same record whichever mode produced it, and since A45 the
-- claim on the shape itself — one open attempt per (doc, shape) is what one learner means, and an attempt
-- parked awaiting a person or the model is still learning. The shape row's lease columns are what hold
-- that claim until the dialogue can be resumed (slice 25); until then an attempt is a record of what
-- happened.
--
CREATE TABLE IF NOT EXISTS shapeshifter_attempt (
    id                    bigint NOT NULL AUTO_INCREMENT,
    version               int NOT NULL,
    create_time_ms        bigint NOT NULL,
    update_time_ms        bigint NOT NULL,
    doc_uuid              varchar(255) NOT NULL,
    shape_hash            varchar(64) NOT NULL,
    shape_id              longtext NOT NULL,
    feed_name             varchar(255) DEFAULT NULL,
    type_name             varchar(255) DEFAULT NULL,
    input_meta_id         bigint DEFAULT NULL,
    node_name             varchar(255) DEFAULT NULL,
    execution_mode        varchar(32) NOT NULL,
    promotion_mode        varchar(32) NOT NULL,
    status                varchar(32) NOT NULL,
    -- What it came to, in the words the decision itself uses, and the rule it wrote where it wrote one
    decision              longtext DEFAULT NULL,
    rule_uuid             varchar(255) DEFAULT NULL,
    score                 double DEFAULT NULL,
    tokens_spent          bigint NOT NULL DEFAULT 0,
    -- When a claim on the shape lapses if nothing heartbeats it (A45), for the worker to abandon
    expiry_ms             bigint DEFAULT NULL,
    PRIMARY KEY           (id),
    KEY                   shapeshifter_attempt_doc_shape (doc_uuid, shape_hash),
    KEY                   shapeshifter_attempt_status (status),
    KEY                   shapeshifter_attempt_create_time_ms (create_time_ms)
) ENGINE=InnoDB DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;

--
-- One turn of an attempt's dialogue (A28): the question as it was put, the answer as it came back, who
-- answered it, and what the answer scored. A person replacing an answer (A28's "answer instead") writes a
-- turn of their own, so the transcript says who said what.
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
    -- The answerer: the model a document names, or the user who answered instead
    answered_by           varchar(255) NOT NULL,
    outcome               varchar(32) DEFAULT NULL,
    PRIMARY KEY           (id),
    UNIQUE KEY            shapeshifter_turn_attempt_turn (fk_attempt_id, turn_number),
    CONSTRAINT            shapeshifter_turn_attempt_fk FOREIGN KEY (fk_attempt_id)
                              REFERENCES shapeshifter_attempt (id)
) ENGINE=InnoDB DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;

SET SQL_NOTES=@OLD_SQL_NOTES;

-- vim: set shiftwidth=4 tabstop=4 expandtab:
