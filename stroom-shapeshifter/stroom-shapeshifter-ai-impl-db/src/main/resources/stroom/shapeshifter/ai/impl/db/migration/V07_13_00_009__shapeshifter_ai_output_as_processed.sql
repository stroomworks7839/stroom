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

-- Stop others slating us for having a warning in the log
SET @OLD_SQL_NOTES=@@SQL_NOTES, SQL_NOTES=0;

-- What an as-processed reprocess needs to run a stream as it ran (design 01 §7.3).
--
-- Three things were missing. The record boundary, because the fragment alone does not say how its
-- chain is run: the same fragment under another boundary produces other events, and the rule's
-- boundary is today's, not the one that produced this output. The pipeline in the unique key, because
-- one document may be used by two pipelines and the second run was overwriting the first's row, which
-- made the first pipeline's streams unreprocessable. And a produced time, because the row is updated
-- in place when a stream is served again, so the id does not say which row was written last.
ALTER TABLE shapeshifter_output
    ADD COLUMN boundary_element varchar(255) DEFAULT NULL,
    ADD COLUMN boundary_array varchar(255) DEFAULT NULL,
    ADD COLUMN boundary_depth int DEFAULT NULL,
    ADD COLUMN produce_time_ms bigint NOT NULL DEFAULT 0;

UPDATE shapeshifter_output SET produce_time_ms = create_time_ms WHERE produce_time_ms = 0;

-- An output with no pipeline is an output of no pipeline rather than of any: the empty string says so
-- and, unlike NULL, is one value to a unique key.
UPDATE shapeshifter_output SET pipeline_uuid = '' WHERE pipeline_uuid IS NULL;

ALTER TABLE shapeshifter_output
    MODIFY COLUMN pipeline_uuid varchar(255) NOT NULL DEFAULT '';

ALTER TABLE shapeshifter_output
    DROP INDEX shapeshifter_output_rule_input,
    ADD UNIQUE KEY shapeshifter_output_rule_input_pipeline (rule_uuid, input_meta_id, pipeline_uuid);

SET SQL_NOTES=@OLD_SQL_NOTES;

-- vim: set shiftwidth=4 tabstop=4 expandtab:
