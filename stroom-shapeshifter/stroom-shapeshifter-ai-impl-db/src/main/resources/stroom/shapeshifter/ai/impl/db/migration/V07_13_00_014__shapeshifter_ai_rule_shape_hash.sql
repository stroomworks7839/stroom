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

-- The shape's hash beside its id on the rule, so that a rule can be joined to its shape's state.
--
-- A46's view of serving rules is ordered by the traffic each carries and filtered by what each has been
-- scoring, and both of those live on shapeshifter_shape. One rule per learned shape means a document
-- with ten thousand shapes has ten thousand rules, so the ordering and the paging belong in the query —
-- and a query that joins on longtext cannot use an index.
--
-- Not on the shared RoutingRule: the hash is how this table finds a row, and nothing outside it needs
-- to know that shapeshifter_shape is keyed that way. The id remains what a person reads.
ALTER TABLE shapeshifter_rule
    ADD COLUMN shape_hash varchar(64) DEFAULT NULL,
    ADD KEY shapeshifter_rule_doc_shape (doc_uuid, shape_hash);

-- The rules already written by the column before this one, so that a rule learned yesterday is in the
-- view as well as one learned tomorrow. SHA2(x, 256) is the same digest over the same UTF-8 bytes as
-- ShapesDao.hash, down to the lower-case hex, which is what shapeshifter_shape is keyed by.
--
-- Blank counts as no shape, as it does in RulesDao.shapeHash and everywhere else that asks whether a
-- rule came from one. A hash of the empty string is a real hash, and a rule carrying one would be
-- listed as serving and then refused when somebody pressed improve.
UPDATE shapeshifter_rule
SET shape_hash = SHA2(shape_id, 256)
WHERE shape_id IS NOT NULL
  AND TRIM(shape_id) <> ''
  AND shape_hash IS NULL;

SET SQL_NOTES=@OLD_SQL_NOTES;

-- vim: set shiftwidth=4 tabstop=4 expandtab:
