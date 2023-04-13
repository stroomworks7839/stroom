package stroom.data.zip;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import static java.util.Map.entry;

public enum StroomZipFileType {
    MANIFEST(".mf", new String[]{".mf", ".manifest"}),
    META(".meta", new String[]{".hdr", ".header", ".meta", ".met"}),
    CONTEXT(".ctx", new String[]{".ctx", ".context"}),
    DATA(".dat", new String[]{".dat"});

    private static final Map<String, StroomZipFileType> EXTENSION_MAP = Map.ofEntries(
            entry(".mf", StroomZipFileType.MANIFEST),
            entry(".manifest", StroomZipFileType.MANIFEST),
            entry(".hdr", StroomZipFileType.META),
            entry(".header", StroomZipFileType.META),
            entry(".meta", StroomZipFileType.META),
            entry(".met", StroomZipFileType.META),
            entry(".ctx", StroomZipFileType.CONTEXT),
            entry(".context", StroomZipFileType.CONTEXT),
            entry(".dat", StroomZipFileType.DATA)
    );

    private final String extension;
    private final String[] recognisedExtensions;

    StroomZipFileType(final String extension,
                      final String[] recognisedExtensions) {
        this.extension = extension;
        this.recognisedExtensions = recognisedExtensions;
    }

    /**
     * The official extension for the file type.
     *
     * @return The official extension for the file type.
     */
    public String getExtension() {
        return extension;
    }

    /**
     * There is some variation in the extensions used in source files so allow for some alternatives to be recognised.
     *
     * @return An array of some alternative extension names.
     */
    public String[] getRecognisedExtensions() {
        return recognisedExtensions;
    }

    public static StroomZipFileType fromExtension(final String extension) {
        Optional<StroomZipFileType> optional = Optional.empty();
        if (extension != null && !extension.isEmpty()) {
            optional = Optional.ofNullable(EXTENSION_MAP.get(extension.toLowerCase(Locale.ROOT)));
        }
        return optional.orElse(StroomZipFileType.DATA);
    }
}
