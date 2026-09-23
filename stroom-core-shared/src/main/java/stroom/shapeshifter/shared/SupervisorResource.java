/*
 * Copyright 2026 Crown Copyright
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

import stroom.util.shared.PageRequest;
import stroom.util.shared.ResourcePaths;
import stroom.util.shared.RestResource;
import stroom.util.shared.ResultPage;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import org.fusesource.restygwt.client.DirectRestService;

import java.util.List;

/**
 * The Supervisor of ruling A28: attempts across every Shapeshifter AI document, not a tab on one. A row
 * opens to the dialogue turn by turn, and what a person may do to an attempt they are reading — answer a
 * turn instead, edit one and run it again, approve or reject what it drafted, or send its shape back to
 * be learned afresh — is here rather than spread across the documents.
 * <p>
 * Editing the routing table a rule sits in — widening a selector, removing a rule — is the document's
 * own resource, since a rule outlives the attempt that wrote it.
 */
@Tag(name = "Shapeshifter AI Supervisor")
@Path("/shapeshifterAiSupervisor" + ResourcePaths.V1)
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public interface SupervisorResource extends RestResource, DirectRestService {

    @POST
    @Path("/find")
    @Operation(summary = "Find attempts across every Shapeshifter AI document",
            operationId = "findShapeshifterAiAttempts")
    ResultPage<SupervisorAttempt> find(AttemptCriteria criteria);

    @GET
    @Path("/{id}")
    @Operation(summary = "Read one attempt with its turns",
            operationId = "fetchShapeshifterAiAttempt")
    SupervisorAttempt fetch(@PathParam("id") long id);

    @POST
    @Path("/{id}/turns/{number}")
    @Operation(summary = "Answer a turn instead, or edit one and run the attempt again from there",
            operationId = "amendShapeshifterAiTurn")
    SupervisorAttempt amend(@PathParam("id") long id,
                            @PathParam("number") int number,
                            AmendTurnRequest request);

    @POST
    @Path("/{id}/approve")
    @Operation(summary = "Approve what an attempt drafted",
            operationId = "approveShapeshifterAiAttempt")
    SupervisorAttempt approve(@PathParam("id") long id);

    @POST
    @Path("/{id}/reject")
    @Operation(summary = "Reject what an attempt drafted, and give up on its shape",
            operationId = "rejectShapeshifterAiAttempt")
    SupervisorAttempt reject(@PathParam("id") long id, RejectRequest request);

    @POST
    @Path("/ledger")
    @Operation(
            summary = "What is waiting on the ledger, a row per shape",
            operationId = "findShapeshifterAiLedger")
    ResultPage<LedgerShape> ledger(@QueryParam("docUuid") String docUuid,
                                   @Parameter(description = "page", required = true) PageRequest pageRequest);

    @POST
    @Path("/{id}/relearn")
    @Operation(summary = "Mark the attempt's shape to be learned afresh",
            operationId = "relearnShapeshifterAiShape")
    SupervisorAttempt relearn(@PathParam("id") long id);
}
