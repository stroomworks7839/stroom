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

-- The corpus (A18; design 04 §4.1): one row per record a rule was accepted on, naming the stream and
-- segment that hold the record and its accepted output. The text lives in the stream store, on the
-- source feed, so that the copy inherits the feed's classification and lives under its retention.
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

SET SQL_NOTES=@OLD_SQL_NOTES;
