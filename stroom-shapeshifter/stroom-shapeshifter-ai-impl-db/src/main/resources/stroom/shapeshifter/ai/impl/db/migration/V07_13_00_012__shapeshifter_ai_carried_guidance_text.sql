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

-- An unbounded list does not go in a bounded column.
--
-- V07_13_00_011 gave the turn a varchar(1024) for the ids of the guidance it carried, and nothing caps
-- how much guidance a shape may have: past about ninety standing hints the list is longer than the
-- column, and under strict mode every turn insert then fails — losing the whole attempt over a
-- bookkeeping field. Capping the list would silently shorten an audit trail instead, which is worse.
--
-- A migration of its own rather than an edit to 011, because 011 has been applied and a migration that
-- has run is not a migration to change.
ALTER TABLE shapeshifter_turn
    MODIFY COLUMN carried_guidance text DEFAULT NULL;

SET SQL_NOTES=@OLD_SQL_NOTES;

-- vim: set shiftwidth=4 tabstop=4 expandtab:
