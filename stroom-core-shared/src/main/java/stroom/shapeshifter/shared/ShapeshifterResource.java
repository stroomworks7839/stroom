/*
 * Copyright 2016-2026 Crown Copyright
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

package stroom.shapeshifter.shared;

import stroom.util.shared.FetchWithUuid;
import stroom.util.shared.ResourcePaths;
import stroom.util.shared.RestResource;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import org.fusesource.restygwt.client.DirectRestService;

@Tag(name = "Shapeshifter")
@Path("/shapeshifter" + ResourcePaths.V1)
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public interface ShapeshifterResource extends RestResource, DirectRestService, FetchWithUuid<ShapeshifterDoc> {

    @GET
    @Path("/{uuid}")
    @Operation(
            summary = "Fetch a Shapeshifter doc by its UUID",
            operationId = "fetchShapeshifter")
    ShapeshifterDoc fetch(@PathParam("uuid") String uuid);

    @PUT
    @Path("/{uuid}")
    @Operation(
            summary = "Update a Shapeshifter doc",
            operationId = "updateShapeshifter")
    ShapeshifterDoc update(
            @PathParam("uuid") String uuid, @Parameter(description = "doc", required = true) ShapeshifterDoc doc);

    @POST
    @Operation(
            summary = "Create a Shapeshifter doc",
            operationId = "createShapeshifter")
    ShapeshifterDoc create(@Parameter(description = "name", required = true) String name);

    @POST
    @Path("/validate")
    @Operation(
            summary = "Read and compile a project's text without running it, returning the engine's messages",
            operationId = "validateShapeshifter")
    ShapeshifterValidation validate(@Parameter(description = "project", required = true) String project);

    @POST
    @Path("/patternInfo")
    @Operation(
            summary = "What the engine says about a regex: validity, groups, how it would run",
            operationId = "shapeshifterPatternInfo")
    ShapeshifterPatternInfo patternInfo(
            @Parameter(description = "request", required = true) ShapeshifterPatternRequest request);

    @POST
    @Path("/explode")
    @Operation(
            summary = "A regex as the pattern tree it is, in the tree's wire form",
            operationId = "shapeshifterExplode")
    ShapeshifterText explode(@Parameter(description = "request", required = true) ShapeshifterPatternRequest request);

    @GET
    @Path("/library")
    @Operation(
            summary = "The standard library a pattern tree's ref names, each entry as the regex it means",
            operationId = "shapeshifterLibrary")
    ShapeshifterLibrary library();

    @POST
    @Path("/print")
    @Operation(
            summary = "A pattern tree, given in its wire form, as the regex it means",
            operationId = "shapeshifterPrint")
    ShapeshifterText print(@Parameter(description = "request", required = true) ShapeshifterPatternRequest request);
}
