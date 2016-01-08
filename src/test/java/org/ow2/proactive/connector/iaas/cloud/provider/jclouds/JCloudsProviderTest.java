package org.ow2.proactive.connector.iaas.cloud.provider.jclouds;

import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Set;

import org.jclouds.compute.ComputeService;
import org.jclouds.compute.RunNodesException;
import org.jclouds.compute.domain.Hardware;
import org.jclouds.compute.domain.NodeMetadata.Status;
import org.jclouds.compute.domain.Template;
import org.jclouds.compute.domain.TemplateBuilder;
import org.jclouds.compute.domain.internal.ImageImpl;
import org.jclouds.compute.domain.internal.NodeMetadataImpl;
import org.junit.Before;
import org.junit.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.ow2.proactive.connector.iaas.fixtures.InfrastructureFixture;
import org.ow2.proactive.connector.iaas.fixtures.InstanceFixture;
import org.ow2.proactive.connector.iaas.model.Image;
import org.ow2.proactive.connector.iaas.model.Infrastructure;
import org.ow2.proactive.connector.iaas.model.Instance;

import com.beust.jcommander.internal.Lists;

import jersey.repackaged.com.google.common.collect.Sets;


public class JCloudsProviderTest {

    @InjectMocks
    private JCloudsProvider jcloudsProvider;

    @Mock
    private JCloudsComputeServiceCache computeServiceCache;

    @Mock
    private ComputeService computeService;

    @Mock
    private TemplateBuilder templateBuilder;

    @Mock
    private Template template;

    @Before
    public void init() {
        MockitoAnnotations.initMocks(this);

    }

    @Test
    public void testCreateInstance() throws NumberFormatException, RunNodesException {

        Infrastructure infratructure = InfrastructureFixture.getInfrastructure("id-aws", "aws", "endPoint",
                "userName", "credential");

        when(computeServiceCache.getComputeService(infratructure)).thenReturn(computeService);

        when(computeService.templateBuilder()).thenReturn(templateBuilder);

        Instance instance = InstanceFixture.getInstance("instance-id", "instance-name", "image", "2", "512",
                "cpu", "running");

        when(templateBuilder.minRam(Integer.parseInt(instance.getRam()))).thenReturn(templateBuilder);

        when(templateBuilder.imageId(instance.getImage())).thenReturn(templateBuilder);

        when(templateBuilder.build()).thenReturn(template);

        Set nodes = Sets.newHashSet();
        NodeMetadataImpl node = mock(NodeMetadataImpl.class);
        when(node.getId()).thenReturn("RegionOne/1cde5a56-27a6-46ce-bdb7-8b01b8fe2592");
        when(node.getName()).thenReturn("someName");
        Hardware hardware = mock(Hardware.class);
        when(hardware.getProcessors()).thenReturn(Lists.newArrayList());
        when(node.getHardware()).thenReturn(hardware);
        when(node.getStatus()).thenReturn(Status.RUNNING);
        nodes.add(node);
        when(computeService.listNodes()).thenReturn(nodes);

        when(computeService.createNodesInGroup(instance.getName(), Integer.parseInt(instance.getNumber()),
                template)).thenReturn(nodes);

        Set<Instance> created = jcloudsProvider.createInstance(infratructure, instance);

        assertThat(created.size(), is(1));

        assertThat(created.stream().findAny().get().getId(),
                is("RegionOne/1cde5a56-27a6-46ce-bdb7-8b01b8fe2592"));

        verify(computeService, times(1)).createNodesInGroup(instance.getName(),
                Integer.parseInt(instance.getNumber()), template);

    }

    @Test(expected = RuntimeException.class)
    public void testCreateInstanceWithFailure() throws NumberFormatException, RunNodesException {

        Infrastructure infratructure = InfrastructureFixture.getInfrastructure("id-aws", "aws", "endPoint",
                "userName", "credential");

        when(computeServiceCache.getComputeService(infratructure)).thenReturn(computeService);

        when(computeService.templateBuilder()).thenReturn(templateBuilder);

        Instance instance = InstanceFixture.getInstance("instance-id", "instance-name", "image", "2", "512",
                "cpu", "running");

        when(templateBuilder.minRam(Integer.parseInt(instance.getRam()))).thenReturn(templateBuilder);

        when(templateBuilder.imageId(instance.getImage())).thenReturn(templateBuilder);

        when(templateBuilder.build()).thenReturn(template);

        Set nodesMetaData = Sets.newHashSet();
        NodeMetadataImpl nodeMetadataImpl = mock(NodeMetadataImpl.class);
        when(nodeMetadataImpl.getId()).thenReturn("RegionOne/1cde5a56-27a6-46ce-bdb7-8b01b8fe2592");
        nodesMetaData.add(nodeMetadataImpl);

        when(computeService.createNodesInGroup(instance.getName(), Integer.parseInt(instance.getNumber()),
                template)).thenThrow(new RuntimeException());

        jcloudsProvider.createInstance(infratructure, instance);

    }

    @Test
    public void testDeleteInstance() throws NumberFormatException, RunNodesException {

        Infrastructure infratructure = InfrastructureFixture.getInfrastructure("id-aws", "aws", "endPoint",
                "userName", "credential");

        when(computeServiceCache.getComputeService(infratructure)).thenReturn(computeService);

        jcloudsProvider.deleteInstance(infratructure, "instanceID");

        verify(computeService, times(1)).destroyNode("instanceID");

    }

    @Test
    public void testGetAllInfrastructureInstances() throws NumberFormatException, RunNodesException {

        Infrastructure infratructure = InfrastructureFixture.getInfrastructure("id-aws", "aws", "endPoint",
                "userName", "credential");

        when(computeServiceCache.getComputeService(infratructure)).thenReturn(computeService);

        Set nodes = Sets.newHashSet();
        NodeMetadataImpl node = mock(NodeMetadataImpl.class);
        when(node.getId()).thenReturn("someId");
        when(node.getName()).thenReturn("someName");
        Hardware hardware = mock(Hardware.class);
        when(hardware.getProcessors()).thenReturn(Lists.newArrayList());
        when(node.getHardware()).thenReturn(hardware);
        when(node.getStatus()).thenReturn(Status.RUNNING);
        nodes.add(node);
        when(computeService.listNodes()).thenReturn(nodes);

        Set<Instance> allNodes = jcloudsProvider.getAllInfrastructureInstances(infratructure);

        assertThat(allNodes.iterator().next().getId(), is("someId"));

    }

    @Test
    public void testGetAllImages() {
        Infrastructure infrastructure = InfrastructureFixture.getInfrastructure("id-aws", "aws", "endPoint",
                "userName", "credential");

        when(computeServiceCache.getComputeService(infrastructure)).thenReturn(computeService);

        Set images = Sets.newHashSet();
        ImageImpl image = mock(ImageImpl.class);
        when(image.getId()).thenReturn("someId");
        when(image.getName()).thenReturn("someName");
        images.add(image);
        when(computeService.listImages()).thenReturn(images);

        Set<Image> allImages = jcloudsProvider.getAllImages(infrastructure);

        assertThat(allImages.iterator().next().getId(), is("someId"));
        assertThat(allImages.iterator().next().getName(), is("someName"));

    }

    @Test
    public void testGetAllImagesEmptySet() {
        Infrastructure infratructure = InfrastructureFixture.getInfrastructure("id-aws", "aws", "endPoint",
                "userName", "credential");

        when(computeServiceCache.getComputeService(infratructure)).thenReturn(computeService);

        Set images = Sets.newHashSet();
        when(computeService.listImages()).thenReturn(images);

        Set<Image> allImages = jcloudsProvider.getAllImages(infratructure);

        assertThat(allImages.isEmpty(), is(true));

    }
}
