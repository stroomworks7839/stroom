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
-- The learning lease of A42 is now the attempt's own claim (A45): the open attempt for a shape is what
-- says a node is learning it, and it is the row the Supervisor shows. Two ways of saying the same thing
-- can disagree — a lease released while its attempt still ran would let a second node learn the shape —
-- so the shape keeps only the second.
--
ALTER TABLE shapeshifter_shape
    DROP COLUMN lease_node,
    DROP COLUMN lease_expiry_ms;

SET SQL_NOTES=@OLD_SQL_NOTES;

-- vim: set shiftwidth=4 tabstop=4 expandtab:
