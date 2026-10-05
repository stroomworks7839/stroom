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

-- The streams that are waiting, and on what (design 04 §3, §6): one row per stream a document has put
-- back. Nothing about which of its records failed and none of its text (A69).
CREATE TABLE IF NOT EXISTS shapeshifter_deferral (
    id                    bigint NOT NULL AUTO_INCREMENT,
    doc_uuid              varchar(255) NOT NULL,
    meta_id               bigint NOT NULL,
    feed_name             varchar(255) NOT NULL,
    type_name             varchar(255) NOT NULL,
    pipeline_uuid         varchar(255) DEFAULT NULL,
    -- The task to wake and the filter it belongs to; null for a stream nothing is processing.
    task_id               bigint DEFAULT NULL,
    filter_id             int DEFAULT NULL,
    -- What the stream was run through and fell short of; null where there was no rule to run it through.
    failed_against        varchar(255) DEFAULT NULL,
    reason                longtext NOT NULL,
    failing_records       int NOT NULL,
    first_deferred_ms     bigint NOT NULL,
    update_time_ms        bigint NOT NULL,
    attempts              int NOT NULL,
    PRIMARY KEY           (id),
    UNIQUE KEY            shapeshifter_deferral_doc_meta (doc_uuid, meta_id),
    KEY                   shapeshifter_deferral_doc_feed_type (doc_uuid, feed_name, type_name)
) ENGINE=InnoDB DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;

SET SQL_NOTES=@OLD_SQL_NOTES;
