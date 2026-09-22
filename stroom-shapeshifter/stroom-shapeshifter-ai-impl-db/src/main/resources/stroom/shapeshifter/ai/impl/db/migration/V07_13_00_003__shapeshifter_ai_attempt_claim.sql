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
-- A45: the attempt row is the claim on its shape, and "one open attempt per (doc, shape)" is a rule the
-- database must hold, not one two transactions can talk themselves out of. claim_key carries the shape's
-- hash while the attempt is open and nothing once it has finished, so the unique key admits exactly one
-- open attempt per shape and any number of finished ones.
--
ALTER TABLE shapeshifter_attempt
    ADD COLUMN claim_key varchar(64) DEFAULT NULL,
    ADD UNIQUE KEY shapeshifter_attempt_claim (doc_uuid, claim_key);

SET SQL_NOTES=@OLD_SQL_NOTES;

-- vim: set shiftwidth=4 tabstop=4 expandtab:
