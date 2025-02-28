package stroom.annotation.shared;

import stroom.docstore.shared.Doc;
import stroom.docstore.shared.DocumentType;
import stroom.docstore.shared.DocumentTypeRegistry;
import stroom.util.shared.UserRef;
import stroom.util.shared.time.SimpleDuration;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonInclude(Include.NON_NULL)
public class Annotation extends Doc {

    public static final String TYPE = "Annotation";
    public static final DocumentType DOCUMENT_TYPE = DocumentTypeRegistry.ANNOTATION_DOCUMENT_TYPE;

    @JsonProperty
    private final Long id;
    @JsonProperty
    private final String subject;
    @JsonProperty
    private final String status;
    @JsonProperty
    private final UserRef assignedTo;
    @JsonProperty
    private final AnnotationGroup annotationGroup;
    @JsonProperty
    private final String comment;
    @JsonProperty
    private final String history;
    @JsonProperty
    private final String description;
    @JsonProperty
    private final SimpleDuration retentionPeriod;
    @JsonProperty
    private final Long retainUntilTimeMs;

    @JsonCreator
    public Annotation(@JsonProperty("type") final String type,
                      @JsonProperty("uuid") final String uuid,
                      @JsonProperty("name") final String name,
                      @JsonProperty("version") final String version,
                      @JsonProperty("createTimeMs") final Long createTimeMs,
                      @JsonProperty("updateTimeMs") final Long updateTimeMs,
                      @JsonProperty("createUser") final String createUser,
                      @JsonProperty("updateUser") final String updateUser,
                      @JsonProperty("id") final Long id,
                      @JsonProperty("subject") final String subject,
                      @JsonProperty("status") final String status,
                      @JsonProperty("assignedTo") final UserRef assignedTo,
                      @JsonProperty("annotationGroup") final AnnotationGroup annotationGroup,
                      @JsonProperty("comment") final String comment,
                      @JsonProperty("history") final String history,
                      @JsonProperty("description") final String description,
                      @JsonProperty("retentionPeriod") final SimpleDuration retentionPeriod,
                      @JsonProperty("retainUntilTimeMs") final Long retainUntilTimeMs) {
        super(type, uuid, name, version, createTimeMs, updateTimeMs, createUser, updateUser);
        this.id = id;
        this.subject = subject;
        this.status = status;
        this.assignedTo = assignedTo;
        this.annotationGroup = annotationGroup;
        this.comment = comment;
        this.history = history;
        this.description = description;
        this.retentionPeriod = retentionPeriod;
        this.retainUntilTimeMs = retainUntilTimeMs;
    }

    public Long getId() {
        return id;
    }

    public String getSubject() {
        return subject;
    }

    public String getStatus() {
        return status;
    }

    public UserRef getAssignedTo() {
        return assignedTo;
    }

    public AnnotationGroup getAnnotationGroup() {
        return annotationGroup;
    }

    public String getComment() {
        return comment;
    }

    public String getHistory() {
        return history;
    }

    public String getDescription() {
        return description;
    }

    public SimpleDuration getRetentionPeriod() {
        return retentionPeriod;
    }

    public Long getRetainUntilTimeMs() {
        return retainUntilTimeMs;
    }

    public static Builder builder() {
        return new Builder();
    }

    public Builder copy() {
        return new Builder(this);
    }

    public static class Builder extends AbstractBuilder<Annotation, Annotation.Builder> {

        private Long id;
        private String subject;
        private String status;
        private UserRef assignedTo;
        private AnnotationGroup annotationGroup;
        private String comment;
        private String history;
        private String description;
        private SimpleDuration retentionPeriod;
        private Long retainUntilTimeMs;

        public Builder() {
        }

        public Builder(final Annotation doc) {
            super(doc);
            this.id = doc.id;
            this.subject = doc.subject;
            this.status = doc.status;
            this.assignedTo = doc.assignedTo;
            this.annotationGroup = doc.annotationGroup;
            this.comment = doc.comment;
            this.history = doc.history;
            this.description = doc.description;
            this.retentionPeriod = doc.retentionPeriod;
            this.retainUntilTimeMs = doc.retainUntilTimeMs;
        }

        public Builder id(final Long id) {
            this.id = id;
            return self();
        }

        public Builder subject(final String subject) {
            this.subject = subject;
            return self();
        }

        public Builder status(final String status) {
            this.status = status;
            return self();
        }

        public Builder assignedTo(final UserRef assignedTo) {
            this.assignedTo = assignedTo;
            return self();
        }

        public Builder annotationGroup(final AnnotationGroup annotationGroup) {
            this.annotationGroup = annotationGroup;
            return self();
        }

        public Builder comment(final String comment) {
            this.comment = comment;
            return self();
        }

        public Builder history(final String history) {
            this.history = history;
            return self();
        }

        public Builder description(final String description) {
            this.description = description;
            return self();
        }

        public Builder retentionPeriod(final SimpleDuration retentionPeriod) {
            this.retentionPeriod = retentionPeriod;
            return self();
        }

        public Builder retainUntilTimeMs(final Long retainUntilTimeMs) {
            this.retainUntilTimeMs = retainUntilTimeMs;
            return self();
        }

        @Override
        protected Builder self() {
            return this;
        }

        @Override
        public Annotation build() {
            return new Annotation(
                    type,
                    uuid,
                    name,
                    version,
                    createTimeMs,
                    updateTimeMs,
                    createUser,
                    updateUser,
                    id,
                    subject,
                    status,
                    assignedTo,
                    annotationGroup,
                    comment,
                    history,
                    description,
                    retentionPeriod,
                    retainUntilTimeMs);
        }
    }
}
