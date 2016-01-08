package org.ow2.proactive.connector.iaas.service;

import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertThat;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Set;

import org.jclouds.compute.RunNodesException;
import org.junit.Before;
import org.junit.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.ow2.proactive.connector.iaas.cloud.CloudManager;
import org.ow2.proactive.connector.iaas.fixtures.InfrastructureFixture;
import org.ow2.proactive.connector.iaas.fixtures.InstanceFixture;
import org.ow2.proactive.connector.iaas.model.Infrastructure;
import org.ow2.proactive.connector.iaas.model.Instance;

import jersey.repackaged.com.google.common.collect.Sets;


public class InstanceServiceTest {

    @InjectMocks
    private InstanceService instanceService;

    @Mock
    private InfrastructureService infrastructureService;

    @Mock
    private CloudManager cloudManager;

    @Before
    public void init() {
        MockitoAnnotations.initMocks(this);

    }

    @Test
    public void testCreateInstance() throws NumberFormatException, RunNodesException {

        Infrastructure infratructure = InfrastructureFixture.getInfrastructure("id-aws", "aws", "endPoint",
                "userName", "credential");
        when(infrastructureService.getInfrastructure(infratructure.getId())).thenReturn(infratructure);

        Instance instance = InstanceFixture.getInstance("instance-id", "instance-name", "image", "2", "512",
                "cpu", "running");

        when(cloudManager.createInstance(infratructure, instance))
                .thenReturn(Sets.newHashSet(InstanceFixture.simpleInstance("id")));

        Set<Instance> created = instanceService.createInstance("id-aws", instance);

        assertThat(created.size(), is(1));

        verify(cloudManager, times(1)).createInstance(infratructure, instance);

    }

    @Test
    public void testDeleteInstance() throws NumberFormatException, RunNodesException {

        Infrastructure infratructure = InfrastructureFixture.getInfrastructure("id-aws", "aws", "endPoint",
                "userName", "credential");
        when(infrastructureService.getInfrastructure(infratructure.getId())).thenReturn(infratructure);

        instanceService.deleteInstance(infratructure.getId(), "instanceID");

        verify(cloudManager, times(1)).deleteInstance(infratructure, "instanceID");

    }

    @Test
    public void testGetAllInstances() throws NumberFormatException, RunNodesException {

        Infrastructure infratructure = InfrastructureFixture.getInfrastructure("id-aws", "aws", "endPoint",
                "userName", "credential");
        when(infrastructureService.getInfrastructure(infratructure.getId())).thenReturn(infratructure);

        when(cloudManager.getAllInfrastructureInstances(infratructure))
                .thenReturn(Sets.newHashSet(InstanceFixture.simpleInstance("id")));

        Set<Instance> created = instanceService.getAllInstances(infratructure.getId());

        assertThat(created.size(), is(1));

        verify(cloudManager, times(1)).getAllInfrastructureInstances(infratructure);

    }
}
