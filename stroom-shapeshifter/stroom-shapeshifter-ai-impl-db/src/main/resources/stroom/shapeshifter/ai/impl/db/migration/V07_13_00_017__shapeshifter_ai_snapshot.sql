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
-- Stop NOTE level warnings about objects (not)? existing
SET @OLD_SQL_NOTES=@@SQL_NOTES, SQL_NOTES=0;

-- A snapshot of a fragment's configuration at a promotion (A61, A70): the fragment's structure and each
-- element's code as text, keyed by a version id. What a rule points at, what a deferral row is stamped
-- with, what an output records, and what an as-processed reprocess runs. Lives as long as its rule (A71).
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

-- The snapshot a rule points at, and the one an output was produced under. Null for rows written before
-- snapshots existed; the stage takes one the first time such a rule serves (A72).
ALTER TABLE shapeshifter_rule
    ADD COLUMN snapshot_id varchar(255) DEFAULT NULL;

ALTER TABLE shapeshifter_output
    ADD COLUMN snapshot_id varchar(255) DEFAULT NULL;

SET SQL_NOTES=@OLD_SQL_NOTES;
