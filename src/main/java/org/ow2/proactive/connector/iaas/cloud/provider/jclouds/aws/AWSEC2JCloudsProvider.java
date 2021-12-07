/*
 * ProActive Parallel Suite(TM):
 * The Open Source library for parallel and distributed
 * Workflows & Scheduling, Orchestration, Cloud Automation
 * and Big Data Analysis on Enterprise Grids & Clouds.
 *
 * Copyright (c) 2007 - 2017 ActiveEon
 * Contact: contact@activeeon.com
 *
 * This library is free software: you can redistribute it and/or
 * modify it under the terms of the GNU Affero General Public License
 * as published by the Free Software Foundation: version 3 of
 * the License.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 *
 * If needed, contact us to obtain a release under GPL Version 2 or 3
 * or a different license than the AGPL.
 */
package org.ow2.proactive.connector.iaas.cloud.provider.jclouds.aws;

import java.util.*;
import java.util.AbstractMap.SimpleImmutableEntry;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.apache.commons.lang3.StringUtils;
import org.jclouds.aws.ec2.AWSEC2Api;
import org.jclouds.aws.ec2.compute.AWSEC2TemplateOptions;
import org.jclouds.aws.ec2.options.RequestSpotInstancesOptions;
import org.jclouds.compute.ComputeService;
import org.jclouds.compute.config.ComputeServiceProperties;
import org.jclouds.compute.domain.NodeMetadata;
import org.jclouds.compute.domain.Template;
import org.jclouds.compute.domain.TemplateBuilder;
import org.jclouds.compute.domain.internal.NodeMetadataImpl;
import org.jclouds.compute.options.RunScriptOptions;
import org.jclouds.domain.Location;
import org.jclouds.ec2.EC2Api;
import org.jclouds.ec2.domain.KeyPair;
import org.jclouds.ec2.domain.PublicIpInstanceIdPair;
import org.jclouds.ec2.features.ElasticIPAddressApi;
import org.jclouds.ec2.features.KeyPairApi;
import org.jclouds.ec2.features.SecurityGroupApi;
import org.jclouds.net.domain.IpProtocol;
import org.json.JSONObject;
import org.ow2.proactive.connector.iaas.cloud.TagManager;
import org.ow2.proactive.connector.iaas.cloud.provider.jclouds.JCloudsComputeServiceBuilder;
import org.ow2.proactive.connector.iaas.cloud.provider.jclouds.JCloudsProvider;
import org.ow2.proactive.connector.iaas.model.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.google.common.collect.Sets;

import lombok.Getter;
import lombok.extern.log4j.Log4j2;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.pricing.PricingClient;
import software.amazon.awssdk.services.pricing.model.*;


@Component
@Log4j2
public class AWSEC2JCloudsProvider extends JCloudsProvider {

    @Getter
    private final String type = "aws-ec2";

    private static final String INSTANCE_ID_REGION_SEPARATOR = "/";

    private static final String CIDR_ALL = "0.0.0.0/0";

    private static Map<String, String> awsPricingRegionName = null;

    /**
     * This field stores the couple (AWS key pair name, private key) for each 
     * AWS region in which a key pair has already been generated by the 
     * connector-iaas. At maximum, the length of this map is the number of
     * regions in AWS.
     */
    private Map<String, SimpleImmutableEntry<String, String>> generatedKeyPairsPerAwsRegion = new HashMap<>();

    // Store the auto-generated security groups for each infrastructure, so that they can be removed when deleting the infrastructure.
    public Map<String, Set<String>> autoGeneratedSecurityGroups = new HashMap<>();

    @Autowired
    private TagManager tagManager;

    @Autowired
    private JCloudsComputeServiceBuilder computeServiceBuilder;

    @Override
    public Set<Instance> createInstance(Infrastructure infrastructure, Instance instance) {

        ComputeService computeService = getComputeServiceFromInfastructure(infrastructure);

        String region = getRegionFromImage(instance);
        TemplateBuilder templateBuilder = computeService.templateBuilder()
                                                        .locationId(region)
                                                        .imageId(instance.getImage());
        if (Optional.ofNullable(instance.getHardware())
                    .map(Hardware::getType)
                    .filter(StringUtils::isNoneBlank)
                    .isPresent()) {
            templateBuilder.hardwareId(instance.getHardware().getType());
        } else {
            templateBuilder.minRam(Integer.parseInt(instance.getHardware().getMinRam()))
                           .minCores(Double.parseDouble(instance.getHardware().getMinCores()));
        }

        Template template = templateBuilder.build();

        Optional.ofNullable(instance.getOptions())
                .ifPresent(options -> addOptions(template, options, infrastructure, region));

        // Add tags
        addTags(template, tagManager.retrieveAllTags(infrastructure.getId(), instance.getOptions()));

        addCredential(template,
                      Optional.ofNullable(instance.getCredentials())
                              .orElseGet(() -> createCredentialsIfNotExist(infrastructure, instance)));

        Set<? extends NodeMetadata> createdNodeMetaData = Sets.newHashSet();

        try {
            createdNodeMetaData = computeService.createNodesInGroup(instance.getTag(),
                                                                    Integer.parseInt(instance.getNumber()),
                                                                    template);

        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        return createdNodeMetaData.stream()
                                  .map(computeMetadata -> (NodeMetadataImpl) computeMetadata)
                                  .map(this::createInstanceFromNode)
                                  .collect(Collectors.toSet());

    }

    // Structure to map AWS region names to their labels used in the pricing API.
    private static Map<String, String> initAwsPricingRegionsMap() {
        Map<String, String> result = Stream.of(new String[][] { { "af-south-1", "Africa (Cape Town)" },
                                                                { "ap-east-1", "Asia Pacific (Hong Kong)" },
                                                                { "ap-south-1", "Asia Pacific (Mumbai)" },
                                                                { "ap-northeast-3", "Asia Pacific (Osaka-Local)" },
                                                                { "ap-northeast-2", "Asia Pacific (Seoul)" },
                                                                { "ap-southeast-1", "Asia Pacific (Singapore)" },
                                                                { "ap-southeast-2", "Asia Pacific (Sydney)" },
                                                                { "ap-northeast-1", "Asia Pacific (Tokyo)" },
                                                                { "ca-central-1", "Canada (Central)" },
                                                                { "eu-central-1", "EU (Frankfurt)" },
                                                                { "eu-west-1", "EU (Ireland)" },
                                                                { "eu-west-2", "EU (London)" },
                                                                { "eu-south-1", "EU (Milan)" },
                                                                { "eu-west-3", "EU (Paris)" },
                                                                { "eu-north-1", "EU (Stockholm)" },
                                                                { "me-south-1", "Middle East (Bahrain)" },
                                                                { "sa-east-1", "South America (Sao Paulo)" },
                                                                { "us-east-1", "US East (N. Virginia)" },
                                                                { "us-east-2", "US East (Ohio)" },
                                                                { "us-west-2", "US West (Los Angeles)" },
                                                                { "us-west-1", "US West (N. California)" }, })
                                           .collect(Collectors.toMap(data -> data[0], data -> data[1]));
        return result;
    }

    private InstanceCredentials createCredentialsIfNotExist(Infrastructure infrastructure, Instance instance) {
        String regionOfCredentials = getRegionFromImage(instance);

        // we keep in memory a default key pair to use in each of the region
        // where EC2 instances are deployed using default credentials
        if (!generatedKeyPairsPerAwsRegion.containsKey(regionOfCredentials)) {
            SimpleImmutableEntry<String, String> keyPair = createKeyPair(infrastructure, instance);
            generatedKeyPairsPerAwsRegion.put(regionOfCredentials, keyPair);
        } else {
            // we have a key pair entry in memory, but we are going to check
            // in addition whether this key exists in AWS
            KeyPairApi keyPairApi = getKeyPairApi(infrastructure);
            String inMemoryKeyPairName = generatedKeyPairsPerAwsRegion.get(regionOfCredentials).getKey();
            Set<KeyPair> awsKeyPairsInRegionWithName = keyPairApi.describeKeyPairsInRegion(regionOfCredentials,
                                                                                           inMemoryKeyPairName);
            // if we have the key in memory but not in AWS, we need to create
            // one in AWS and replace it in memory
            if (awsKeyPairsInRegionWithName.stream()
                                           .noneMatch(keyPair -> keyPair.getKeyName().equals(inMemoryKeyPairName))) {
                SimpleImmutableEntry<String, String> keyPair = createKeyPair(infrastructure, instance);
                generatedKeyPairsPerAwsRegion.put(regionOfCredentials, keyPair);
            }
        }

        // we are now sure that we have a key pair both in memory and in AWS
        // for the given region: so retrieve it and use it to create default
        // credentials
        String keyPairName = generatedKeyPairsPerAwsRegion.get(regionOfCredentials).getKey();
        return new InstanceCredentials(getVmUserLogin(), null, keyPairName, null, null);
    }

    private void addCredential(Template template, InstanceCredentials credentials) {
        log.info("Username given for instance creation: " + credentials.getUsername());
        Optional.ofNullable(credentials.getUsername())
                .filter(StringUtils::isNotEmpty)
                .filter(StringUtils::isNotBlank)
                .ifPresent(username -> template.getOptions()
                                               .as(AWSEC2TemplateOptions.class)
                                               .overrideLoginUser(credentials.getUsername()));

        log.info("Public key name given for instance creation: " + credentials.getPublicKeyName());
        Optional.ofNullable(credentials.getPublicKeyName())
                .filter(keyName -> !keyName.isEmpty())
                .ifPresent(keyName -> template.getOptions()
                                              .as(AWSEC2TemplateOptions.class)
                                              .keyPair(credentials.getPublicKeyName()));
    }

    private void addOptions(Template template, Options options, Infrastructure infrastructure, String region) {
        Optional.ofNullable(options.getSpotPrice())
                .filter(spotPrice -> !spotPrice.isEmpty())
                .ifPresent(spotPrice -> template.getOptions()
                                                .as(AWSEC2TemplateOptions.class)
                                                .spotPrice(Float.valueOf(options.getSpotPrice())));

        if (options.getSpotPrice() != null && !options.getSpotPrice().isEmpty()) {
            // jclouds use the property nodeRunningTimeout to check whether the instance is correctly started,
            // if the instance is not yet running after this timeout, jclouds will throw a RunNodesException.
            // Therefore, we use it as the validUntil time for the spot request.
            // The spot request is expected to be auto-cancelled after the instance creation timeout.
            long nodeRunningTimeout = Long.parseLong(computeServiceBuilder.getDefinedProperties(infrastructure)
                                                                          .getProperty(ComputeServiceProperties.TIMEOUT_NODE_RUNNING));
            Date spotRequestValidUntil = new Date(System.currentTimeMillis() + nodeRunningTimeout);
            RequestSpotInstancesOptions spotInstancesOptions = RequestSpotInstancesOptions.Builder.validUntil(spotRequestValidUntil);
            template.getOptions().as(AWSEC2TemplateOptions.class).spotOptions(spotInstancesOptions);
        }

        List<String> securityGroupNames = options.getSecurityGroupNames();
        if (securityGroupNames != null && !securityGroupNames.isEmpty()) {
            template.getOptions().as(AWSEC2TemplateOptions.class).securityGroupIds(securityGroupNames);
        } else {

            log.info(String.format("The infrastructure [%s] is using the auto-generated security group.",
                                   infrastructure.getId()));
            // Have we defined an explicit list of ports to be be opened ?
            int[] ports = options.getPortsToOpen();
            String securityGroupName = getAutoGeneratedSecurityGroupName(infrastructure.getId());
            if (ports != null) {
                securityGroupName += "-" + UUID.randomUUID();
                String sgDescription = "Auto generated security group to authorize the ports " + Arrays.toString(ports);
                SecurityGroupApi securityGroupApi = getSecurityGroupApi(infrastructure);
                securityGroupApi.createSecurityGroupInRegion(region, securityGroupName, sgDescription);
                String finalSecurityGroupName = securityGroupName;
                Arrays.stream(ports)
                      .forEach(port -> allowPortInSecurityGroup(port,
                                                                finalSecurityGroupName,
                                                                region,
                                                                securityGroupApi));
                template.getOptions().as(AWSEC2TemplateOptions.class).securityGroupIds(securityGroupName);
            }
            Set<String> infraSg = autoGeneratedSecurityGroups.getOrDefault(infrastructure.getId(), new HashSet<>());
            infraSg.add(securityGroupName);
            autoGeneratedSecurityGroups.put(infrastructure.getId(), infraSg);
        }

        Optional.ofNullable(options.getSubnetId())
                .filter(subnetId -> !subnetId.isEmpty())
                .ifPresent(subnetId -> template.getOptions()
                                               .as(AWSEC2TemplateOptions.class)
                                               .subnetId(options.getSubnetId()));

    }

    private void allowPortInSecurityGroup(int port, String securityGroup, String region,
            SecurityGroupApi securityGroupApi) {
        if (port == -1) {
            securityGroupApi.authorizeSecurityGroupIngressInRegion(region,
                                                                   securityGroup,
                                                                   IpProtocol.ICMP,
                                                                   port,
                                                                   port,
                                                                   CIDR_ALL);
        } else {
            securityGroupApi.authorizeSecurityGroupIngressInRegion(region,
                                                                   securityGroup,
                                                                   IpProtocol.TCP,
                                                                   port,
                                                                   port,
                                                                   CIDR_ALL);
            securityGroupApi.authorizeSecurityGroupIngressInRegion(region,
                                                                   securityGroup,
                                                                   IpProtocol.UDP,
                                                                   port,
                                                                   port,
                                                                   CIDR_ALL);
        }
    }

    private void addTags(Template template, List<Tag> tags) {
        template.getOptions()
                .as(AWSEC2TemplateOptions.class)
                .userMetadata(tags.stream().collect(Collectors.toMap(Tag::getKey, Tag::getValue)));
    }

    private String getRegionFromNode(ComputeService computeService, NodeMetadata node) {
        Location nodeLocation = node.getLocation();
        Set<? extends Location> assignableLocations = computeService.listAssignableLocations();
        while (!assignableLocations.contains(nodeLocation)) {
            nodeLocation = nodeLocation.getParent();
        }
        return nodeLocation.getId();
    }

    @Override
    public String addToInstancePublicIp(Infrastructure infrastructure, String instanceId, String optionalDesiredIp) {

        ComputeService computeService = getComputeServiceFromInfastructure(infrastructure);
        NodeMetadata node = computeService.getNodeMetadata(instanceId);
        ElasticIPAddressApi elasticIPAddressApi = computeService.getContext()
                                                                .unwrapApi(AWSEC2Api.class)
                                                                .getElasticIPAddressApi()
                                                                .get();

        // Get the region
        String region;
        if (instanceId.contains(INSTANCE_ID_REGION_SEPARATOR)) {
            region = instanceId.split(INSTANCE_ID_REGION_SEPARATOR)[0];
        } else {
            region = getRegionFromNode(computeService, node);
        }

        String id = node.getProviderId();

        // Try to assign existing IP
        if (Optional.ofNullable(optionalDesiredIp).isPresent()) {
            elasticIPAddressApi.associateAddressInRegion(region, optionalDesiredIp, id);
            return optionalDesiredIp;
        }

        // Try to associate to an existing IP
        String ip = null;
        Set<PublicIpInstanceIdPair> unassignedIps = elasticIPAddressApi.describeAddressesInRegion(region)
                                                                       .stream()
                                                                       .filter(address -> address.getInstanceId() == null)
                                                                       .collect(Collectors.toSet());
        boolean associated;
        for (PublicIpInstanceIdPair unassignedIp : unassignedIps) {
            associated = false;
            try {
                elasticIPAddressApi.associateAddressInRegion(region, unassignedIp.getPublicIp(), id);
                associated = true;
            } catch (RuntimeException e) {
                log.warn("Cannot associate address " + unassignedIp.getPublicIp() + " in region " + region, e);
            }
            if (associated) {
                ip = unassignedIp.getPublicIp();
                break;
            }
        }
        // Allocate a new IP otherwise
        if (ip == null) {
            try {
                ip = elasticIPAddressApi.allocateAddressInRegion(region);
            } catch (Exception e) {
                throw new RuntimeException("Failed to allocate a new IP address. All IP addresses are in use.", e);
            }
            elasticIPAddressApi.associateAddressInRegion(region, ip, id);
        }
        return ip;
    }

    @Override
    public void removeInstancePublicIp(Infrastructure infrastructure, String instanceId, String optionalDesiredIp) {

        ComputeService computeService = getComputeServiceFromInfastructure(infrastructure);
        NodeMetadata node = computeService.getNodeMetadata(instanceId);
        String region = node.getLocation().getId();
        ElasticIPAddressApi elasticIPAddressApi = computeService.getContext()
                                                                .unwrapApi(AWSEC2Api.class)
                                                                .getElasticIPAddressApi()
                                                                .get();
        // Try to dissociate the specified IP
        if (Optional.ofNullable(optionalDesiredIp).isPresent()) {
            elasticIPAddressApi.disassociateAddressInRegion(region, optionalDesiredIp);
            return;
        }
        // Dissociate one of the IP associated to the instance
        node.getPublicAddresses().stream().findAny().ifPresent(ip -> {
            elasticIPAddressApi.disassociateAddressInRegion(region, ip);
        });
    }

    @Override
    public SimpleImmutableEntry<String, String> createKeyPair(Infrastructure infrastructure, Instance instance) {
        KeyPairApi keyPairApi = getKeyPairApi(infrastructure);
        String region = getRegionFromImage(instance);
        String keyPairName = "default-" + region + "-" + UUID.randomUUID();
        try {
            KeyPair keyPair = keyPairApi.createKeyPairInRegion(region, keyPairName);
            log.info("Created key pair '" + keyPairName + "' in region '" + region + "'");
            return new SimpleImmutableEntry<>(keyPairName, keyPair.getKeyMaterial());
        } catch (RuntimeException e) {
            log.warn("Cannot create key pair in region '" + region, e);
            return null;
        }
    }

    @Override
    public void deleteKeyPair(Infrastructure infrastructure, String keyPairName, String region) {
        KeyPairApi keyPairApi = getKeyPairApi(infrastructure);
        keyPairApi.deleteKeyPairInRegion(region, keyPairName);
        log.info("Removed the key pair [{}] in the region [{}]", keyPairName, region);
    }

    @Override
    public PagedNodeCandidates getNodeCandidate(Infrastructure infra, String region, String osReq, String token) {
        if (awsPricingRegionName == null) {
            // If the structure is not yet initialized, I prepare it.
            awsPricingRegionName = initAwsPricingRegionsMap();
        }
        // Preparing the request to the API. Only two regions provide an endpoint for the pricing API. We arbitrarily set it to US-EAST-1.
        PricingClient pc = PricingClient.builder()
                                        .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(infra.getCredentials()
                                                                                                                              .getUsername(),
                                                                                                                         infra.getCredentials()
                                                                                                                              .getPassword())))
                                        .region(Region.US_EAST_1)
                                        .build();
        // Effectively proceed to the API call
        GetProductsResponse pricesListResponse;
        try {
            pricesListResponse = getProducts(pc, region, osReq, token);
        } catch (InvalidNextTokenException inte) {
            return PagedNodeCandidates.builder().nextToken("").nodeCandidates(new HashSet<NodeCandidate>()).build();
        }

        // Interpreting response
        if (!pricesListResponse.hasPriceList()) {
            // No pricing result.
            log.info("No node candidate found");
            return PagedNodeCandidates.builder().nextToken("").nodeCandidates(new HashSet<NodeCandidate>()).build();
        } else {
            // We have pricing results => We parse the Stringified-JSON structure from the API
            Set<NodeCandidate> result = productResponseToSet(pricesListResponse, region);
            log.info(String.format("%d node candidates were found.", result.stream().count()));
            return PagedNodeCandidates.builder()
                                      .nextToken(pricesListResponse.nextToken())
                                      .nodeCandidates(result)
                                      .build();
        }
    }

    private Set<NodeCandidate> productResponseToSet(GetProductsResponse pricesListResponse, String region) {
        return pricesListResponse.priceList().parallelStream().map(priceResponse -> {
            JSONObject terms = new JSONObject(priceResponse).getJSONObject("terms");
            JSONObject productAttributes = new JSONObject(priceResponse).getJSONObject("product")
                                                                        .getJSONObject("attributes");

            // Hardware spec.
            Hardware.HardwareBuilder hwb = Hardware.builder()
                                                   .minRam(fromAwsGioToparseableMB(productAttributes.getString("memory")) +
                                                           "")
                                                   .minCores(productAttributes.getString("vcpu"))
                                                   .type(productAttributes.getString("instanceType"));

            if (productAttributes.has("clockSpeed")) {
                hwb.minFreq(fromAwsGioToparseableMB(productAttributes.getString("clockSpeed")) + "");
            } else {
                hwb.minFreq("0");
            }

            Hardware hw = hwb.build();
            // The minimal price of the cheaper on-demand-offer
            double price = parseAwsPriceOnDemandInstance(terms);
            // Image spec - No strict reference toa system image is provided by the pricing API. Instead,
            // we re-use their label system to identified system type. We left to association between
            // system image and those label to an external process.
            Image operatingSystem = Image.builder()
                                         .name(productAttributes.getString("operatingSystem"))
                                         .operatingSystem(OperatingSystem.builder()
                                                                         .family(productAttributes.getString("operatingSystem"))
                                                                         .build())
                                         .location(region)
                                         .build();
            // We build the structure encapsulating the result.
            return NodeCandidate.builder()
                                .cloud(this.getType())
                                .region(region)
                                .hw(hw)
                                .price(price)
                                .img(operatingSystem)
                                .build();
        }).collect(Collectors.toSet());
    }

    private GetProductsResponse getProducts(PricingClient pc, String region, String osReq, String token) {
        return pc.getProducts(GetProductsRequest.builder()
                                                .serviceCode("AmazonEC2")
                                                .filters(Filter.builder()
                                                               .field("location")
                                                               .type(FilterType.TERM_MATCH)
                                                               .value(awsPricingRegionName.get(region))
                                                               .build(),
                                                         Filter.builder()
                                                               .field("operatingSystem")
                                                               .type(FilterType.TERM_MATCH)
                                                               .value(osReq)
                                                               .build(),
                                                         Filter.builder()
                                                               .field("capacitystatus")
                                                               .type(FilterType.TERM_MATCH)
                                                               .value("Used")
                                                               .build(),
                                                         Filter.builder()
                                                               .field("Tenancy")
                                                               .type(FilterType.TERM_MATCH)
                                                               .value("Shared")
                                                               .build(),
                                                         Filter.builder()
                                                               .field("preInstalledSw")
                                                               .type(FilterType.TERM_MATCH)
                                                               .value("NA")
                                                               .build())
                                                .nextToken(token)
                                                .build());
    }

    private int fromAwsGioToparseableMB(String s) {
        // The pricing API providing Strings for to describe the spec. of the infra resources. We need to parse it, to work with MB.
        try {
            String[] splitValue = s.split(" ");
            Float floatNumber = 0f;
            switch (splitValue.length) {
                case 1:
                    // The value contains just NA:
                    return 0;
                case 2:
                    // If the value is traditionnaly formed (i.e. XX Ghz)
                    floatNumber = Float.parseFloat(splitValue[0]);
                    break;
                case 4:
                    // If the value defined a maximum
                    floatNumber = Float.parseFloat(splitValue[2]);
                    break;
            }
            return Math.round(floatNumber * 1024);
        } catch (NumberFormatException e) {
            log.error(String.format("Error while parsing integer answer %s from AWS API: %s", s, e.getMessage()));
            e.printStackTrace();
            throw e;
        }
    }

    private double parseAwsPriceOnDemandInstance(JSONObject terms) {
        // The pricing API use very specific JSON structure to describe the offers, and provide a lot of unexploitable values (for now). We dwarf it.
        // See `$ aws pricing get-products --service-code AmazonEC2 --filter Type=TERM_MATCH,Field=instanceType,Value=t3.small`
        // to get an example of such JSON structure.
        try {
            JSONObject onDemand = terms.getJSONObject("OnDemand");
            OptionalDouble foundPrice = onDemand.keySet()
                                                .parallelStream()
                                                .map(jo1 -> onDemand.getJSONObject(jo1))
                                                .map(anOfferTerm -> anOfferTerm.getJSONObject("priceDimensions"))
                                                .map(priceDimensions -> priceDimensions.keySet()
                                                                                       .parallelStream()
                                                                                       .map(keyName -> priceDimensions.getJSONObject(keyName))
                                                                                       .map(aPriceDimension -> aPriceDimension.getJSONObject("pricePerUnit"))
                                                                                       .mapToDouble(aPricePerUnit -> aPricePerUnit.getDouble("USD"))
                                                                                       .min())
                                                .filter(OptionalDouble::isPresent)
                                                .mapToDouble(OptionalDouble::getAsDouble)
                                                .min();
            return foundPrice.orElse(0);
        } catch (NumberFormatException e) {
            log.error(String.format("Error while parsing double answer %s from AWS API: %s",
                                    terms.toString(),
                                    e.getMessage()));
            e.printStackTrace();
            throw e;
        }
    }

    @Override
    public void deleteInfrastructure(Infrastructure infrastructure) {
        // if the infrastructure has used the auto-generated security group, remove it.
        if (autoGeneratedSecurityGroups.containsKey(infrastructure.getId())) {
            SecurityGroupApi securityGroupApi = getSecurityGroupApi(infrastructure);
            for (String securityGroupName : autoGeneratedSecurityGroups.get(infrastructure.getId())) {
                securityGroupApi.deleteSecurityGroupInRegion(infrastructure.getRegion(), securityGroupName);
                log.info(String.format("Removed the auto-generated security group [%s] for the infrastructure [%s]",
                                       securityGroupName,
                                       infrastructure.getId()));
            }
        }

        super.deleteInfrastructure(infrastructure);
        autoGeneratedSecurityGroups.remove(infrastructure.getId());
    }

    private KeyPairApi getKeyPairApi(Infrastructure infrastructure) {
        ComputeService computeService = getComputeServiceFromInfastructure(infrastructure);
        EC2Api ec2Api = computeService.getContext().unwrapApi(EC2Api.class);
        if (ec2Api.getKeyPairApi().isPresent()) {
            return ec2Api.getKeyPairApi().get();
        } else {
            throw new UnsupportedOperationException("Cannot retrieve AWS key pair API, which enables key pair creation");
        }
    }

    private SecurityGroupApi getSecurityGroupApi(Infrastructure infrastructure) {
        ComputeService computeService = getComputeServiceFromInfastructure(infrastructure);
        EC2Api ec2Api = computeService.getContext().unwrapApi(EC2Api.class);
        if (ec2Api.getSecurityGroupApi().isPresent()) {
            return ec2Api.getSecurityGroupApi().get();
        } else {
            throw new UnsupportedOperationException("Cannot retrieve AWS security group API, which enables security group cleanup");
        }
    }

    private String getRegionFromImage(Instance instance) {
        String image = instance.getImage();
        return image.split(INSTANCE_ID_REGION_SEPARATOR)[0];
    }

    @Override
    public RunScriptOptions getRunScriptOptionsWithCredentials(InstanceCredentials credentials) {
        // retrieve the passed username or read it from the property file
        String username = Optional.ofNullable(credentials.getUsername())
                                  .filter(StringUtils::isNotEmpty)
                                  .filter(StringUtils::isNotBlank)
                                  .orElse(getVmUserLogin());
        log.info("Credentials used to execute script on instance: [username=" + username + "]");
        // Currently in AWS EC2 root login is forbidden, as well as
        // username/password login. So the only way to login to run the script
        // is by giving username/private key credentials
        return RunScriptOptions.Builder.runAsRoot(false)
                                       .overrideLoginUser(username)
                                       .overrideLoginPrivateKey(credentials.getPrivateKey());
    }

    @Override
    protected RunScriptOptions getDefaultRunScriptOptionsUsingInstanceId(String instanceId,
            Infrastructure infrastructure) {
        ComputeService computeService = getComputeServiceFromInfastructure(infrastructure);
        NodeMetadata node = computeService.getNodeMetadata(instanceId);

        String subdividedRegion = getRegionFromNode(computeService, node);
        String keyPairRegion = extractRegionFromSubdividedRegion(subdividedRegion);

        return buildDefaultRunScriptOptions(keyPairRegion);
    }

    @Override
    protected RunScriptOptions getDefaultRunScriptOptionsUsingInstanceTag(String instanceTag,
            Infrastructure infrastructure) {
        // retrieve at least one instance that has the given tag to know in
        // which AWS region we are
        Instance taggedInstance = getCreatedInfrastructureInstances(infrastructure).stream()
                                                                                   .filter(instance -> instance.getTag()
                                                                                                               .equals(instanceTag))
                                                                                   .findAny()
                                                                                   .orElseThrow(() -> new IllegalArgumentException("Unable to create script options: cannot retrieve instance id from tag " +
                                                                                                                                   instanceTag));
        String subdividedRegion = getRegionFromImage(taggedInstance);
        String keyPairRegion = extractRegionFromSubdividedRegion(subdividedRegion);

        return buildDefaultRunScriptOptions(keyPairRegion);
    }

    private RunScriptOptions buildDefaultRunScriptOptions(String keyPairRegion) {
        SimpleImmutableEntry<String, String> defaultKeyPairInRegion = generatedKeyPairsPerAwsRegion.get(keyPairRegion);
        Optional.ofNullable(defaultKeyPairInRegion)
                .ifPresent(keyPair -> log.info("Default script options: username=" + getVmUserLogin()));
        return Optional.ofNullable(defaultKeyPairInRegion)
                       .map(keyPair -> RunScriptOptions.Builder.runAsRoot(false)
                                                               .overrideLoginUser(getVmUserLogin())
                                                               .overrideLoginPrivateKey(keyPair.getValue()))
                       .orElse(RunScriptOptions.NONE);
    }

    private String extractRegionFromSubdividedRegion(String subdividedRegion) {
        // the region returned here contains a subdivision of the region, for
        // example eu-west-1c for the region eu-west-1, so we need to remove
        // the subdivision to have the exact region name of the key
        String region = subdividedRegion.substring(0, subdividedRegion.length() - 1);
        log.debug("Subdivided region=" + subdividedRegion + ", extracted region=" + region);
        return region;
    }

    /**
     * The name of the security group which is auto-generated by jclouds. (It is shared between all the instances of the infrastructure.)
     * 
     * @param infrastructureId infrastructure id
     * @return jclouds used auto-generated security group name
     */
    public static String getAutoGeneratedSecurityGroupName(String infrastructureId) {
        return "jclouds#" + infrastructureId;
    }
}
