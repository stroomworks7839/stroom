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

--
-- What a supervisor has told the learning about a shape (ruling A46, design 01 §12 item 29): a hint, a
-- correction, or a fact about the feed the sample does not show.
--
-- Per (document, shape) rather than per turn or per attempt, which is the whole of the ruling's first
-- decision: what a person knows is about the feed, not about turn 7 of attempt 412. Nothing has to be
-- timed, nothing is refused for arriving at the wrong moment, and a hint outlives the attempt that
-- first used it — the relearning of A29, months later, carries it too.
--
-- It is not a turn and not a question (A37 is untouched): it is an input every question carries.
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

-- Which guidance a turn carried when it was asked, as a list of ids.
--
-- So that a re-walk (A45) replays what was actually used rather than what has since been added: an
-- attempt parked at a question and resumed a day later must be re-derivable, and a hint given in
-- between would otherwise change what the earlier turns are held to have been asked with.
ALTER TABLE shapeshifter_turn
    ADD COLUMN carried_guidance varchar(1024) DEFAULT NULL;

SET SQL_NOTES=@OLD_SQL_NOTES;

-- vim: set shiftwidth=4 tabstop=4 expandtab:
