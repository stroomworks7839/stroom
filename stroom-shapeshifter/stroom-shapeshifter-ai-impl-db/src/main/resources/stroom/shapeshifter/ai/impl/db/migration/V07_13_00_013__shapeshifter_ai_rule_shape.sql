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

-- Which shape a rule was learned for (A46, design 01 §12 item 29).
--
-- The rule's selector already holds the shape's values, but it holds them as a matcher expression: the
-- values are escaped so that ExpressionMatcher takes them literally, so reading a shape back out of one
-- is guesswork. A rule that is asked to be *improved* needs its shape exactly — to claim the attempt
-- (A42), to carry the supervisor's message (A46), and to be shown in a view of serving rules at all.
--
-- Null for a rule written before this column, and for one an operator wrote by hand: neither came from
-- a shape. Improving those is refused rather than guessed at, and relearning is what they have instead.
ALTER TABLE shapeshifter_rule
    ADD COLUMN shape_id longtext DEFAULT NULL;

SET SQL_NOTES=@OLD_SQL_NOTES;

-- vim: set shiftwidth=4 tabstop=4 expandtab:
