package org.ow2.proactive.connector.iaas.rest;

import com.aol.micro.server.rest.jackson.JacksonUtil;
import org.ow2.proactive.connector.iaas.model.Instance;
import org.ow2.proactive.connector.iaas.service.InstanceService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import javax.ws.rs.Path;
import javax.ws.rs.Consumes;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.PathParam;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.DELETE;
import javax.ws.rs.ClientErrorException;
import javax.ws.rs.core.Response;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;


@Path("/infrastructures")
@Component
public class InstanceRest {

    @Autowired
    private InstanceService instanceService;

    @POST
    @Consumes("application/json")
    @Produces("application/json")
    @Path("{infrastructureId}/instances")
    public Response createInstance(@PathParam("infrastructureId") String infrastructureId,
                                   final String instanceJson) {
        Instance instance = JacksonUtil.convertFromJson(instanceJson, Instance.class);
        return Response.ok(instanceService.createInstance(infrastructureId, instance)).build();
    }

    @GET
    @Path("{infrastructureId}/instances")
    @Produces("application/json")
    public Response getInstances(@PathParam("infrastructureId") String infrastructureId,
                                 @QueryParam("instanceId") String instanceId, @QueryParam("instanceTag") String instanceTag) {

        if (Optional.ofNullable(instanceId).isPresent()) {
            return Response.ok(instanceService.getInstanceById(infrastructureId, instanceId)).build();
        } else if (Optional.ofNullable(instanceTag).isPresent()) {
            return Response.ok(instanceService.getInstanceByTag(infrastructureId, instanceTag)).build();
        } else {
            return Response.ok(instanceService.getAllInstances(infrastructureId)).build();
        }
    }

    @DELETE
    @Path("{infrastructureId}/instances")
    @Produces("application/json")
    public Response deleteInstance(@PathParam("infrastructureId") String infrastructureId,
                                   @QueryParam("instanceId") String instanceId, @QueryParam("instanceTag") String instanceTag) {

        if (Optional.ofNullable(instanceId).isPresent()) {
            instanceService.deleteInstance(infrastructureId, instanceId);
        } else {
            instanceService.deleteInstanceByTag(infrastructureId, instanceTag);
        }

        return Response.ok().build();
    }

    @POST
    @Path("{infrastructureId}/instances/publicIp")
    @Produces("application/json")
    public Response createPublicIp(@PathParam("infrastructureId") String infrastructureId,
                                   @QueryParam("instanceId") String instanceId,
                                   @QueryParam("instanceTag") String instanceTag) {
        Map response = new HashMap();
        if (Optional.ofNullable(instanceId).isPresent()) {
            response.put("publicIp", instanceService.addToInstancePublicIp(infrastructureId, instanceId));
        } else if (Optional.ofNullable(instanceTag).isPresent()) {
            instanceService.addInstancePublicIpByTag(infrastructureId, instanceTag);
        } else {
            throw new ClientErrorException("The parameter \"instanceId\" and \"instanceTag\" are  missing.",
                    Response.Status.BAD_REQUEST);
        }
        return Response.ok(response).build();
    }

    @DELETE
    @Path("{infrastructureId}/instances/publicIp")
    @Produces("application/json")
    public Response removePublicIp(@PathParam("infrastructureId") String infrastructureId,
                                   @QueryParam("instanceId") String instanceId,
                                   @QueryParam("instanceTag") String instanceTag) {

        if (Optional.ofNullable(instanceId).isPresent()) {
            instanceService.removeInstancePublicIp(infrastructureId, instanceId);
        } else if (Optional.ofNullable(instanceTag).isPresent()) {
            instanceService.removeInstancePublicIpByTag(infrastructureId, instanceTag);
        } else {
            throw new ClientErrorException("The parameters \"instanceId\" and \"instanceTag\" are missing.",
                    Response.Status.BAD_REQUEST);
        }

        return Response.ok().build();
    }


}
