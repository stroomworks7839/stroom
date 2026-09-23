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

-- Where each record of an output began and ended in the stream it was cut from (design 01 §10.1, §12
-- item 21), so that a fault found at an event can be put to the model with the record that made it
-- rather than by running the parser over the stream again.
--
-- One column rather than a row per record: a stream of a million records is a million spans, and a row
-- apiece would make the table that carries a stream's history bigger than the history. They are written
-- as one list, and what a node keeps is capped in code — past the cap a record has no span, which reads
-- as "not recorded" and never as a wrong one.
ALTER TABLE shapeshifter_output
    ADD COLUMN record_spans mediumtext DEFAULT NULL;

SET SQL_NOTES=@OLD_SQL_NOTES;

-- vim: set shiftwidth=4 tabstop=4 expandtab:
