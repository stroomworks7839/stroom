/*
 * Copyright 2017 Crown Copyright
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

package stroom.planb.impl.data;

import stroom.util.shared.ResourcePaths;
import stroom.util.shared.RestResource;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.io.InputStream;

/**
 * Used to transfer snapshots and parts between servers.
 */
@Tag(name = "File Transfer")
@Path(FileTransferResource.BASE_PATH)
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public interface FileTransferResource extends RestResource {

    String BASE_PATH = "/fileTransfer" + ResourcePaths.V1;
    String FETCH_SNAPSHOT_PATH_PART = "/fetchSnapshot";
    String SEND_PART_PATH_PART = "/sendPart";

    @POST
    @Path(FETCH_SNAPSHOT_PATH_PART)
    @Produces(MediaType.APPLICATION_OCTET_STREAM)
    @Consumes(MediaType.APPLICATION_JSON)
    @Operation(
            summary = "Fetch Plan B snapshot",
            operationId = "fetchSnapshot")
    Response fetchSnapshot(SnapshotRequest request);

    @POST
    @Path(SEND_PART_PATH_PART)
    @Produces(MediaType.APPLICATION_JSON)
    @Consumes(MediaType.APPLICATION_OCTET_STREAM)
    @Operation(
            summary = "Send Plan B part",
            operationId = "sendPart")
    Response sendPart(@HeaderParam("createTime") long createTime,
                      @HeaderParam("metaId") long metaId,
                      @HeaderParam("fileHash") String fileHash,
                      @HeaderParam("fileName") String fileName,
                      InputStream inputStream);
}
