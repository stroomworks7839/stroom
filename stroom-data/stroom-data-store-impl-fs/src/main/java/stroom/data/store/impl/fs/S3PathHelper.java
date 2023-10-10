/*
 * Copyright 2016 Crown Copyright
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package stroom.data.store.impl.fs;

import stroom.data.shared.StreamTypeNames;
import stroom.meta.shared.SimpleMeta;
import stroom.util.io.FileUtil;

import io.vavr.Tuple;
import io.vavr.Tuple2;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

class S3PathHelper {

//    private static final LambdaLogger LOGGER = LambdaLoggerFactory.getLogger(S3PathHelper.class);

    private static final Map<String, String> EXTENSION_MAP = new HashMap<>();
    private static final Map<String, String> CHILD_TYPES_REVERSE_MAP;

    static {
        EXTENSION_MAP.put(InternalStreamTypeNames.SEGMENT_INDEX, "seg");
        EXTENSION_MAP.put(InternalStreamTypeNames.BOUNDARY_INDEX, "bdy");

        // Child types
        EXTENSION_MAP.put(InternalStreamTypeNames.MANIFEST, "mf");
        EXTENSION_MAP.put(StreamTypeNames.META, "meta");
        EXTENSION_MAP.put(StreamTypeNames.CONTEXT, "ctx");

        CHILD_TYPES_REVERSE_MAP = Stream.of(
                        StreamTypeNames.META,
                        StreamTypeNames.CONTEXT,
                        InternalStreamTypeNames.MANIFEST)
                .map(type -> {
                    final String ext = EXTENSION_MAP.get(type);
                    if (ext == null) {
                        return null;
                    } else {
                        return Tuple.of(type, ext);
                    }
                })
                .filter(Objects::nonNull)
                .collect(Collectors.toMap(Tuple2::_2, Tuple2::_1));
    }

    /**
     * We use this rather than the File.separator as we need to be standard
     * across Windows and UNIX.
     */
//    static final String FILE_SEPARATOR_CHAR = "=";
//    private static final String STORE_NAME = "store";

    private static final String[] CHILD_STREAM_TYPES = new String[]{
            InternalStreamTypeNames.SEGMENT_INDEX,
            InternalStreamTypeNames.BOUNDARY_INDEX,
            InternalStreamTypeNames.MANIFEST,
            StreamTypeNames.META,
            StreamTypeNames.CONTEXT};
//    private final StreamTypeExtensions streamTypeExtensions;
//
//    @Inject
//    S3PathHelper(final StreamTypeExtensions streamTypeExtensions) {
//        this.streamTypeExtensions = streamTypeExtensions;
//    }

//    static boolean isStreamFile(final Path path) {
//        // A simple test to avoid hitting the fs to distinguish between a file and a directory
//        return NullSafe.test(path, path2 ->
//                path2.getFileName().toString().contains(FILE_SEPARATOR_CHAR));
//    }
//
//    static long getId(final Path path) {
//        Objects.requireNonNull(path);
//        final String fileName = path.getFileName().toString();
//        final int start = fileName.indexOf(FILE_SEPARATOR_CHAR);
//        if (start != -1) {
//            final int end = fileName.indexOf(".", start);
//            if (end != -1) {
//                final String fullIdString = fileName.substring(start + 1, end);
//                if (fullIdString.length() > 0) {
//                    return FsPrefixUtil.dePadId(fullIdString);
//                }
//            }
//        }
//        return -1;
//    }
//
//    static DecodedPath decodedPath(final Path path) {
//        Objects.requireNonNull(path);
//        return DecodedPath.fromPath(path);
//    }

    /**
     * Return back a input stream for a given stream type and file.
     */
    public InputStream getInputStream(final String streamTypeName, final Path file) throws IOException {
        if (streamTypeName == null) {
            throw new IllegalArgumentException("Must Have a non-null stream type");
        }
        if (FileStoreType.bgz.equals(getFileStoreType(streamTypeName))) {
            return new BlockGZIPInputFile(file);
        }
        return new UncompressedInputStream(file, isStreamTypeLazy(streamTypeName));
    }

    /**
     * <p>
     * Find all existing child files of this parent.
     * </p>
     */
    private List<Path> findChildStreamFileList(final Path parent) {
        final List<Path> kids = new ArrayList<>();
        for (final String type : CHILD_STREAM_TYPES) {
            final Path child = getChildPath(parent, type);
            if (Files.isRegularFile(child)) {
                kids.add(child);
            }
        }
        return kids;
    }

    /**
     * Return back a output stream for a given stream type and file.
     */
    public OutputStream getOutputStream(final String streamTypeName, final Path file)
            throws IOException {
        if (streamTypeName == null) {
            throw new IllegalArgumentException("Must Have a non-null stream type");
        }
        IOException ioEx = null;
        OutputStream outputStream = null;
        if (FileStoreType.bgz.equals(getFileStoreType(streamTypeName))) {
            try {
                outputStream = new BlockGZIPOutputFile(file);
            } catch (IOException e) {
                ioEx = e;
            }
        } else {
            try {
                outputStream = new LockingFileOutputStream(file, isStreamTypeLazy(streamTypeName));
            } catch (IOException e) {
                ioEx = e;
            }
        }
        if (ioEx != null) {
            throw ioEx;
        }
        return outputStream;
    }

//    /**
//     * <p>
//     * Build a file base name.
//     * </p>
//     * <p>
//     * <p>
//     * [feedid]_[streamid]
//     * </p>
//     */
//    String getBaseName(Meta meta) {
//        final String feedPath = fileSystemFeedPaths.getOrCreatePath(meta.getFeedName());
//        return feedPath +
//                FILE_SEPARATOR_CHAR +
//                FsPrefixUtil.padId(meta.getId());
//    }

    /**
     * Find all the descendants to this file.
     */
    List<Path> findAllDescendantStreamFileList(final Path parent) {
        List<Path> rtn = new ArrayList<>();
        List<Path> kids = findChildStreamFileList(parent);
        for (Path kid : kids) {
            rtn.add(kid);
            rtn.addAll(findAllDescendantStreamFileList(kid));
        }
        return rtn;
    }

//    Path getRootPath(final Path volumePath,
//                     final SimpleMeta meta) {
//        return getRootPath(volumePath, meta, meta.getTypeName());
//    }

    /**
     * Return a File IO object.
     */
    Path getRootPath(final Path volumePath,
                     final SimpleMeta meta,
                     final String streamTypeName) {
//        final String utcDate = DateUtil.createNormalDateTimeString(meta.getCreateMs());
//        final String typePath = fileSystemTypePaths.getOrCreatePath(streamTypeName);
//        final String feedPath = fileSystemFeedPaths.getOrCreatePath(meta.getFeedName());
//        final String paddedId = FsPrefixUtil.padId(meta.getId());
//
//        final String fileName = "" +
//                feedPath +
//                FILE_SEPARATOR_CHAR +
//                paddedId +
//                "." +
//                buildRootExtension(streamTypeName);
//
//        Path result = volumePath;
//        result = result
//                .resolve(STORE_NAME)
//                .resolve(typePath)
//                .resolve(utcDate.substring(0, 4))
//                .resolve(utcDate.substring(5, 7))
//                .resolve(utcDate.substring(8, 10));
//
//        result = FsPrefixUtil.appendIdPath(result, paddedId);
//
//        return result.resolve(fileName);

        final String fileName = meta.getId() + "." + buildRootExtension(streamTypeName);

        return volumePath.resolve(fileName);
    }

    private String getExtension(final String streamTypeName) {
        final String extension = EXTENSION_MAP.get(streamTypeName);
        if (extension == null) {
            return "dat";
        }
        return extension;
    }

    private String buildRootExtension(final String streamTypeName) {
        return getExtension(streamTypeName) +
                "." +
                getFileStoreType(streamTypeName);
    }

//    Set<Path> findRootStreamFiles(final String streamTypeName, final Path parentPath) {
//        if (parentPath == null) {
//            return Collections.emptySet();
//        } else {
//            final String rootExtension = buildRootExtension(streamTypeName);
//            try (Stream<Path> stream = Files.list(parentPath)) {
//
//                // Get all the files
//                final Set<Path> rootFilePaths = stream
//                        .filter(childPath ->
//                                childPath.toString().endsWith(rootExtension))
//                        .collect(Collectors.toSet());
//                LOGGER.trace(() -> LogUtil.message("found {} rootFilePaths for {}",
//                        rootFilePaths.size(), parentPath));
//                return rootFilePaths;
//            } catch (IOException e) {
//                throw new RuntimeException(LogUtil.message(
//                        "Error getting directory listing for '{}': {}", parentPath, e.getMessage()), e);
//            }
//        }
//    }

    /**
     * Create a child file for a parent.
     */
    Path getChildPath(final Path parent, final String streamTypeName) {
        StringBuilder builder = new StringBuilder(FileUtil.getCanonicalPath(parent));
        // Drop ".dat" or ".bgz"
        builder.setLength(builder.lastIndexOf("."));
        builder.append(".");
        builder.append(getExtension(streamTypeName));
        builder.append(".");
        builder.append(getFileStoreType(streamTypeName));
        return Paths.get(builder.toString());
    }

    String decodeChildStreamType(final Path path) {
        Objects.requireNonNull(path);

        final Pattern internalTypesPattern = Pattern.compile("\\.(" +
                getExtension(InternalStreamTypeNames.BOUNDARY_INDEX) + "|" +
                getExtension(InternalStreamTypeNames.SEGMENT_INDEX) + "|" +
                getExtension(InternalStreamTypeNames.MANIFEST) + ")");

        // remove the internal types e.g. .revt.bdy. => .revt.
        final String pathStr = internalTypesPattern.matcher(path.toString())
                .replaceFirst("");

        final String[] splits = pathStr.split("\\.");

        if (splits.length >= 3) {
            final String typeExt = splits[splits.length - 2];
            return getChildType(typeExt);
        } else {
            throw new RuntimeException("Unable to extract type from " + pathStr);
        }
    }

    String getChildType(final String extension) {
        return CHILD_TYPES_REVERSE_MAP.get(extension);
    }

    private FileStoreType getFileStoreType(final String streamTypeName) {
        return switch (streamTypeName) {
            case InternalStreamTypeNames.SEGMENT_INDEX,
                    InternalStreamTypeNames.BOUNDARY_INDEX,
                    InternalStreamTypeNames.MANIFEST -> FileStoreType.dat;
            default -> FileStoreType.bgz;
        };
    }

    boolean isStreamTypeLazy(final String streamTypeName) {
        return InternalStreamTypeNames.SEGMENT_INDEX.equals(streamTypeName)
                || InternalStreamTypeNames.BOUNDARY_INDEX.equals(streamTypeName);
    }


    // --------------------------------------------------------------------------------


    /**
     * Types of file we are dealing with.
     */
    private enum FileStoreType {
        dat, // The cached uncompressed file.
        bgz // Block GZIP Compressed File.
    }

//
//    // --------------------------------------------------------------------------------
//
//
//    /**
//     * The parts of a stream store file/dir paths like
//     * {@code .../default_stream_volume/store/RAW_EVENTS/2022/12/14/TEST_REFERENCE_DATA-EVENTS=414.revt.mf.dat}
//     * or
//     * {@code .../default_stream_volume/store/RAW_EVENTS/2023/03/15/005/TEST_FEED=999999.revt.meta.bgz.yml}
//     * or
//     * {@code default_stream_volume/store/ERROR/2023/02/11/005}
//     */
//    static class DecodedPath {
//
//        private static final LambdaLogger LOGGER = LambdaLoggerFactory.getLogger(DecodedPath.class);
//
//        private final String feedName;
//        private final String typeName;
//        private final LocalDate localDate;
//        private final Long metaId;
//        private final boolean isDirectory;
//
//        private DecodedPath(final String typeName,
//                            final LocalDate localDate,
//                            final String feedName,
//                            final Long metaId,
//                            final boolean isDirectory) {
//            this.feedName = feedName;
//            this.typeName = typeName;
//            this.localDate = localDate;
//            this.metaId = metaId;
//            this.isDirectory = isDirectory;
//        }
//
//        private static DecodedPath fromPath(final Path path) {
//            LOGGER.trace("Decoding path {}", path);
//
//            // TODO: 16/03/2023 We probably ought to be using Fs(Feed|Type)Dao to map the path part
//            //  back to a value understood in stoom. Currently this is only used in orphaned file finder
//            //  so we can live with it.
//
//            // This could be a directory in which case there will be no
//            final String fileName = path.getFileName().toString();
//            final int fileSeparatorIdx = fileName.lastIndexOf(FILE_SEPARATOR_CHAR);
//            final String feedName;
//            final Long metaId;
//            final boolean isDirectory = fileSeparatorIdx == -1;
//            if (fileSeparatorIdx != -1) {
//                feedName = fileName.substring(0, fileSeparatorIdx);
//                final int dotIdx = fileName.indexOf(".");
//                final String paddedMetaId = dotIdx != -1
//                        ? fileName.substring(fileSeparatorIdx + 1, dotIdx)
//                        : null;
//                final long id = FsPrefixUtil.dePadId(paddedMetaId);
//                metaId = id != -1
//                        ? id
//                        : null;
//            } else {
//                feedName = null;
//                metaId = null;
//            }
//
//            for (int i = path.getNameCount() - 5; i >= 0; i--) {
//                if (i + 4 < path.getNameCount() && "store".equals(path.getName(i).toString())) {
//                    final String type = path.getName(i + 1).toString();
//                    final int year;
//                    final int month;
//                    final int day;
//                    try {
//                        year = Integer.parseInt(path.getName(i + 2).toString());
//                        month = Integer.parseInt(path.getName(i + 3).toString());
//                        day = Integer.parseInt(path.getName(i + 4).toString());
//                    } catch (NumberFormatException e) {
//                        throw new IllegalArgumentException("Unable to extract date from path " + path, e);
//                    }
//                    final LocalDate localDate = LocalDate.of(year, month, day);
//
//                    return new DecodedPath(type, localDate, feedName, metaId, isDirectory);
//                }
//            }
//            throw new IllegalArgumentException("Unable to parse path " + path);
//        }
//
//        /**
//         * @return Feed name or null if a directory
//         */
//        public String getFeedName() {
//            return feedName;
//        }
//
//        public String getTypeName() {
//            return typeName;
//        }
//
//        public LocalDate getDate() {
//            return localDate;
//        }
//
//        /**
//         * @return Meta ID or null if a directory
//         */
//        public Long getMetaId() {
//            return metaId;
//        }
//
//        public boolean isDirectory() {
//            return isDirectory;
//        }
//    }
}
