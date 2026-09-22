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
-- §12 item 25: the fragment a rule binds splits its stream into one document per record, and where the
-- records sit is part of the boundary. A boundary is a name, and how deep the records it names sit
-- depends on the document — one for the children of a root, three for the items of an array under a key,
-- since the JSON parser wraps its output in a records root — so the depth is read when the split is
-- settled and kept with the name. A rule stored before this has none and is written without a filter,
-- exactly as it was, until it is learned again.
--
ALTER TABLE shapeshifter_rule
    ADD COLUMN boundary_depth int DEFAULT NULL;

SET SQL_NOTES=@OLD_SQL_NOTES;

-- vim: set shiftwidth=4 tabstop=4 expandtab:
