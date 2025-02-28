-- ------------------------------------------------------------------------
-- Copyright 2023 Crown Copyright
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

CREATE TABLE IF NOT EXISTS annotation_group (
  id                    int(11) NOT NULL AUTO_INCREMENT,
  uuid                  varchar(255) NOT NULL,
  name                  varchar(255) NOT NULL,
  deleted               tinyint NOT NULL DEFAULT '0',
  PRIMARY KEY           (id),
  UNIQUE KEY            `annotation_group_uuid_idx` (`uuid`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8;

CREATE TABLE IF NOT EXISTS annotation_tag (
  id                    int(11) NOT NULL AUTO_INCREMENT,
  uuid                  varchar(255) NOT NULL,
  name                  varchar(255) NOT NULL,
  colour                varchar(255) NOT NULL,
  deleted               tinyint NOT NULL DEFAULT '0',
  PRIMARY KEY           (id),
  UNIQUE KEY            `annotation_tag_uuid_idx` (`uuid`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8;

CREATE TABLE IF NOT EXISTS annotation_tag_link (
  id                    bigint(20) NOT NULL AUTO_INCREMENT,
  fk_annotation_id      bigint(20) NOT NULL,
  fk_annotation_tag_id  int(11) NOT NULL,
  PRIMARY KEY           (id),
  UNIQUE KEY            fk_annotation_id_fk_annotation_tag_id (fk_annotation_id, fk_annotation_tag_id),
  CONSTRAINT            annotation_tag_link_fk_annotation_id FOREIGN KEY (fk_annotation_id) REFERENCES annotation (id),
  CONSTRAINT            annotation_tag_link_fk_annotation_tag_id FOREIGN KEY (fk_annotation_tag_id) REFERENCES annotation_tag (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8;

DROP PROCEDURE IF EXISTS V07_09_00_001_annotation;

DELIMITER $$

CREATE PROCEDURE V07_09_00_001_annotation ()
BEGIN
    DECLARE object_count integer;

    --
    -- Add logical delete
    --
    SELECT COUNT(1)
    INTO object_count
    FROM information_schema.columns
    WHERE table_schema = database()
    AND table_name = 'annotation'
    AND column_name = 'deleted';

    IF object_count = 0 THEN
        ALTER TABLE `annotation` ADD COLUMN `deleted` tinyint NOT NULL DEFAULT '0';
    END IF;

    --
    -- Add description
    --
    SELECT COUNT(1)
    INTO object_count
    FROM information_schema.columns
    WHERE table_schema = database()
    AND table_name = 'annotation'
    AND column_name = 'description';

    IF object_count = 0 THEN
        ALTER TABLE `annotation`
        ADD COLUMN `description` longtext;
    END IF;

    --
    -- Add data retention time
    --
    SELECT COUNT(1)
    INTO object_count
    FROM information_schema.columns
    WHERE table_schema = database()
    AND table_name = 'annotation'
    AND column_name = 'retention_time';

    IF object_count = 0 THEN
        ALTER TABLE `annotation` ADD COLUMN `retention_time` bigint(20) DEFAULT NULL;
    END IF;

    --
    -- Add data retention unit
    --
    SELECT COUNT(1)
    INTO object_count
    FROM information_schema.columns
    WHERE table_schema = database()
    AND table_name = 'annotation'
    AND column_name = 'retention_unit';

    IF object_count = 0 THEN
        ALTER TABLE `annotation` ADD COLUMN `retention_unit` tinyint DEFAULT NULL;
    END IF;

    --
    -- Add data retention until
    --
    SELECT COUNT(1)
    INTO object_count
    FROM information_schema.columns
    WHERE table_schema = database()
    AND table_name = 'annotation'
    AND column_name = 'retain_until_ms';

    IF object_count = 0 THEN
        ALTER TABLE `annotation`
        ADD COLUMN `retain_until_ms` bigint DEFAULT NULL;
    END IF;

    --
    -- Add group id
    --
    SELECT COUNT(1)
    INTO object_count
    FROM information_schema.columns
    WHERE table_schema = database()
    AND table_name = 'annotation'
    AND column_name = 'group_id';

    IF object_count = 0 THEN
        ALTER TABLE `annotation`
        ADD COLUMN `group_id` int(11) DEFAULT NULL;
    END IF;

    --
    -- Add parent id
    --
    SELECT COUNT(1)
    INTO object_count
    FROM information_schema.columns
    WHERE table_schema = database()
    AND table_name = 'annotation'
    AND column_name = 'parent_id';

    IF object_count = 0 THEN
        ALTER TABLE `annotation`
        ADD COLUMN `parent_id` bigint(20) DEFAULT NULL;
    END IF;

    --
    -- Add entry type id
    --
    SELECT COUNT(1)
    INTO object_count
    FROM information_schema.columns
    WHERE table_schema = database()
    AND table_name = 'annotation_entry'
    AND column_name = 'type_id';

    IF object_count = 0 THEN
        ALTER TABLE annotation_entry ADD COLUMN type_id tinyint NOT NULL;
        UPDATE annotation_entry SET type_id = 0 WHERE type = "Title";
        UPDATE annotation_entry SET type_id = 1 WHERE type = "Subject";
        UPDATE annotation_entry SET type_id = 2 WHERE type = "Status";
        UPDATE annotation_entry SET type_id = 3 WHERE type = "Assigned";
        UPDATE annotation_entry SET type_id = 4 WHERE type = "Comment";
        UPDATE annotation_entry SET type_id = 5 WHERE type = "Link";
        UPDATE annotation_entry SET type_id = 6 WHERE type = "Unlink";
        ALTER TABLE annotation_entry DROP COLUMN type;
    END IF;

    --
    -- Add entry logical delete
    --
    SELECT COUNT(1)
    INTO object_count
    FROM information_schema.columns
    WHERE table_schema = database()
    AND table_name = 'annotation_entry'
    AND column_name = 'deleted';

    IF object_count = 0 THEN
        ALTER TABLE `annotation_entry` ADD COLUMN `deleted` tinyint NOT NULL DEFAULT '0';
    END IF;

END $$

DELIMITER ;

CALL V07_09_00_001_annotation;

DROP PROCEDURE IF EXISTS V07_09_00_001_annotation;

SET SQL_NOTES=@OLD_SQL_NOTES;

-- vim: set shiftwidth=4 tabstop=4 expandtab:
