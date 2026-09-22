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
-- A12: releasing a shape asks for the streams that waited on it to be processed again, and a stream is
-- processed again by the pipeline that processed it the first time. One Shapeshifter AI document may be
-- used by several pipelines, and a shape settles where it was not necessarily sentinelled — another
-- task, another node, or deferred mode's worker (A5) with no pipeline around it at all — so the ledger
-- remembers where each stream it names was being processed rather than the release guessing.
--
-- Nullable, and rows written before this name no pipeline: releasing one takes it off the ledger and
-- asks for nothing, since there is nothing to say where it would be processed again.
ALTER TABLE shapeshifter_ledger
    ADD COLUMN pipeline_uuid varchar(255) DEFAULT NULL;

SET SQL_NOTES=@OLD_SQL_NOTES;

-- vim: set shiftwidth=4 tabstop=4 expandtab:
