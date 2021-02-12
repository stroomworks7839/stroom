package stroom.security.impl.event;

import stroom.util.shared.ResourcePaths;
import stroom.util.shared.RestResource;

import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import io.swagger.annotations.ApiParam;

import javax.ws.rs.Consumes;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;

@Api(tags = "Application Permissions")
@Path(PermissionChangeResource.BASE_PATH)
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public interface PermissionChangeResource extends RestResource {
    String BASE_PATH = "/permission/changeEvent" + ResourcePaths.V1;
    String FIRE_CHANGE_PATH_PART = "/fireChange";
    String NODE_NAME_PATH_PARAM = "/{nodeName}";

    @POST
    @Path(FIRE_CHANGE_PATH_PART + NODE_NAME_PATH_PARAM)
    @ApiOperation(
            value = "Fires a permission change event")
    Boolean fireChange(@PathParam("nodeName") String nodeName, 
                       @ApiParam("request") PermissionChangeRequest request);
}
