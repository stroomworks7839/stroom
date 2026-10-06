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
-- A77: what a person granted a parked session beyond the document's budgets, recorded against the
-- attempt alone — tokens and milliseconds added to what the document allows it, for as long as the row
-- is kept. Zero is nothing granted.
--
ALTER TABLE shapeshifter_attempt
    ADD COLUMN granted_tokens bigint NOT NULL DEFAULT 0,
    ADD COLUMN granted_ms bigint NOT NULL DEFAULT 0;

SET SQL_NOTES=@OLD_SQL_NOTES;

-- vim: set shiftwidth=4 tabstop=4 expandtab:
