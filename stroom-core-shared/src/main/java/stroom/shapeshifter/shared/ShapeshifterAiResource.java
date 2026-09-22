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
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import org.fusesource.restygwt.client.DirectRestService;

import java.util.List;

@Tag(name = "Shapeshifter AI")
@Path("/shapeshifterAi" + ResourcePaths.V1)
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public interface ShapeshifterAiResource
        extends RestResource, DirectRestService, FetchWithUuid<ShapeshifterAiDoc> {

    @GET
    @Path("/{uuid}")
    @Operation(
            summary = "Fetch a Shapeshifter AI document by its UUID",
            operationId = "fetchShapeshifterAi")
    ShapeshifterAiDoc fetch(@PathParam("uuid") String uuid);

    @GET
    @Path("/templates")
    @Operation(
            summary = "The built-in text of the plan's templates and its version",
            operationId = "fetchShapeshifterAiTemplates")
    BuiltInTemplates templates();

    @PUT
    @Path("/{uuid}")
    @Operation(
            summary = "Update a Shapeshifter AI document",
            operationId = "updateShapeshifterAi")
    ShapeshifterAiDoc update(
            @PathParam("uuid") String uuid,
            @Parameter(description = "doc", required = true) ShapeshifterAiDoc doc);

    @GET
    @Path("/{uuid}/rules")
    @Operation(
            summary = "The document's routing rules, which are rows and not part of the document (A41)",
            operationId = "fetchShapeshifterAiRules")
    List<RoutingRule> rules(@PathParam("uuid") String uuid);

    @POST
    @Path("/{uuid}/rules")
    @Operation(
            summary = "Add a rule at a position in the document's table; every call answers with the table",
            operationId = "addShapeshifterAiRule")
    List<RoutingRule> addRule(
            @PathParam("uuid") String uuid,
            @QueryParam("at") Integer at,
            @Parameter(description = "rule", required = true) RoutingRule rule);

    @PUT
    @Path("/{uuid}/rules/{ruleUuid}")
    @Operation(
            summary = "Replace one rule, keeping its position",
            operationId = "updateShapeshifterAiRule")
    List<RoutingRule> updateRule(
            @PathParam("uuid") String uuid,
            @PathParam("ruleUuid") String ruleUuid,
            @Parameter(description = "rule", required = true) RoutingRule rule);

    @PUT
    @Path("/{uuid}/rules/{ruleUuid}/position")
    @Operation(
            summary = "Move one rule to a position, the others closing behind it",
            operationId = "moveShapeshifterAiRule")
    List<RoutingRule> moveRule(
            @PathParam("uuid") String uuid,
            @PathParam("ruleUuid") String ruleUuid,
            @QueryParam("to") int to);

    @DELETE
    @Path("/{uuid}/rules/{ruleUuid}")
    @Operation(
            summary = "Remove one rule",
            operationId = "deleteShapeshifterAiRule")
    List<RoutingRule> deleteRule(
            @PathParam("uuid") String uuid,
            @PathParam("ruleUuid") String ruleUuid);
}
