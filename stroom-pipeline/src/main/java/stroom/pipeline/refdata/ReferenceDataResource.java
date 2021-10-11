package stroom.pipeline.refdata;

import stroom.pipeline.refdata.store.ProcessingInfoResponse;
import stroom.pipeline.refdata.store.RefStoreEntry;
import stroom.util.shared.ResourcePaths;
import stroom.util.shared.RestResource;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import java.util.List;
import javax.validation.Valid;
import javax.validation.constraints.Min;
import javax.validation.constraints.NotNull;
import javax.ws.rs.Consumes;
import javax.ws.rs.DELETE;
import javax.ws.rs.DefaultValue;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.MediaType;

@Tag(name = "Reference Data")
@Path(ReferenceDataResource.BASE_PATH)
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public interface ReferenceDataResource extends RestResource {

    String BASE_PATH = "/refData" + ResourcePaths.V1;

    String ENTRIES_SUB_PATH = "/entries";
    String REF_STREAM_INFO_SUB_PATH = "/refStreamInfo";
    String LOOKUP_SUB_PATH = "/lookup";
    String PURGE_BY_AGE_SUB_PATH = "/purgeByAge";
    String PURGE_BY_STREAM_SUB_PATH = "/purgeByStream";
    String CLEAR_BUFFER_POOL_PATH = "/clearBufferPool";

    @GET
    @Path(ENTRIES_SUB_PATH)
    @Operation(
            summary = "List entries from the reference data store on the node called.",
            description = "This is primarily intended  for small scale debugging in non-production environments. If " +
                    "no limit is set a default limit is applied else the results will be limited to limit entries.",
            operationId = "getReferenceStoreEntries")
    List<RefStoreEntry> entries(@QueryParam("limit") final Integer limit,
                                @QueryParam("refStreamId") final Long refStreamId,
                                @QueryParam("mapName") final String mapName);

    @GET
    @Path(REF_STREAM_INFO_SUB_PATH)
    @Operation(
            summary = "List processing info entries for all ref streams",
            description = "This is primarily intended  for small scale debugging in non-production environments. If " +
                    "no limit is set a default limit is applied else the results will be limited to limit entries.",
            operationId = "getReferenceStreamProcessingInfoEntries")
    List<ProcessingInfoResponse> refStreamInfo(@QueryParam("limit") final Integer limit,
                                               @QueryParam("refStreamId") final Long refStreamId,
                                               @QueryParam("mapName") final String mapName);

    @POST
    @Path(LOOKUP_SUB_PATH)
    @Operation(
            summary = "Perform a reference data lookup using the supplied lookup request. " +
                    "Reference data will be loaded if required using the supplied reference pipeline.",
            operationId = "lookupReferenceData")
    String lookup(@Valid @NotNull final RefDataLookupRequest refDataLookupRequest);

    @DELETE
    @Path(PURGE_BY_AGE_SUB_PATH + "/{purgeAge}")
    @Operation(
            summary = "Explicitly delete all entries that are older than purgeAge.",
            operationId = "purgeReferenceDataByAge")
    boolean purge(@NotNull @PathParam("purgeAge") final String purgeAge);

    @DELETE
    @Path(PURGE_BY_STREAM_SUB_PATH + "/{refStreamId}/{partNo}")
    @Operation(
            summary = "Delete all entries for a reference stream and part number (one based)",
            operationId = "purgeReferenceDataByStreamAndPartNo")
    boolean purge(@Min(1) @PathParam("refStreamId") final long refStreamId,
                  @Min(1) @DefaultValue("1") @PathParam("partNo") final long partNo);

    @DELETE
    @Path(CLEAR_BUFFER_POOL_PATH)
    @Operation(
            summary = "Clear all buffers currently available in the buffer pool to reclaim memory.",
            operationId = "clearBufferPool")
    void clearBufferPool();
}
