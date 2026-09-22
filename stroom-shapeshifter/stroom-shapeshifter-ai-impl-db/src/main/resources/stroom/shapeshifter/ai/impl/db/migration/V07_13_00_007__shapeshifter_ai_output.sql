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
-- What each rule produced (design 01 §7.3 rule 3, A26): one row per output stream, naming the input it
-- was made from, the rule that bound it and the pipeline that ran. Retracting a rule asks for exactly
-- these inputs to be processed again (§6), and the pipeline is part of the answer: one document may be
-- used by several pipelines, and an input replayed through a pipeline that never saw it produces
-- something nobody asked for.
--
-- The bindings are on the output stream's attributes as well, where a person reads them; a custom stream
-- attribute is not a field stroom can query, so what a retraction must find is a row of its own.
--
CREATE TABLE IF NOT EXISTS shapeshifter_output (
    id                    bigint NOT NULL AUTO_INCREMENT,
    create_time_ms        bigint NOT NULL,
    doc_uuid              varchar(255) NOT NULL,
    rule_uuid             varchar(255) NOT NULL,
    input_meta_id         bigint NOT NULL,
    pipeline_uuid         varchar(255) DEFAULT NULL,
    fragment_uuid         varchar(255) NOT NULL,
    provisional           tinyint(1) NOT NULL DEFAULT 0,
    score                 double DEFAULT NULL,
    PRIMARY KEY           (id),
    -- One row per input per rule: a stream processed twice under one rule is one thing to replay
    UNIQUE KEY            shapeshifter_output_rule_input (rule_uuid, input_meta_id),
    KEY                   shapeshifter_output_doc_uuid (doc_uuid)
) ENGINE=InnoDB DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;

SET SQL_NOTES=@OLD_SQL_NOTES;

-- vim: set shiftwidth=4 tabstop=4 expandtab:
