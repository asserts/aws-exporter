/*
 *  Copyright © 2020.
 *  Asserts, Inc. - All Rights Reserved
 */
package ai.asserts.aws.resource;

import ai.asserts.aws.AWSClientProvider;
import ai.asserts.aws.RateLimiter;
import ai.asserts.aws.cloudwatch.config.NamespaceConfig;
import ai.asserts.aws.cloudwatch.config.ScrapeConfig;
import ai.asserts.aws.cloudwatch.config.ScrapeConfigProvider;
import ai.asserts.aws.cloudwatch.model.CWNamespace;
import ai.asserts.aws.exporter.BasicMetricCollector;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import org.easymock.EasyMockSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.resourcegroupstaggingapi.ResourceGroupsTaggingApiClient;
import software.amazon.awssdk.services.resourcegroupstaggingapi.model.GetResourcesRequest;
import software.amazon.awssdk.services.resourcegroupstaggingapi.model.GetResourcesResponse;
import software.amazon.awssdk.services.resourcegroupstaggingapi.model.ResourceTagMapping;
import software.amazon.awssdk.services.resourcegroupstaggingapi.model.Tag;
import software.amazon.awssdk.services.resourcegroupstaggingapi.model.TagFilter;

import java.util.Optional;

import static ai.asserts.aws.cloudwatch.model.CWNamespace.kafka;
import static ai.asserts.aws.cloudwatch.model.CWNamespace.lambda;
import static org.easymock.EasyMock.anyLong;
import static org.easymock.EasyMock.anyObject;
import static org.easymock.EasyMock.expect;
import static org.junit.jupiter.api.Assertions.assertEquals;

public class TagFilterResourceProviderTest extends EasyMockSupport {
    private ScrapeConfigProvider scrapeConfigProvider;
    private ScrapeConfig scrapeConfig;
    private AWSClientProvider awsClientProvider;
    private ResourceGroupsTaggingApiClient apiClient;
    private ResourceMapper resourceMapper;
    private Resource resource;
    private NamespaceConfig namespaceConfig;
    private BasicMetricCollector metricCollector;
    private TagFilterResourceProvider testClass;

    @BeforeEach
    public void setup() {
        scrapeConfigProvider = mock(ScrapeConfigProvider.class);
        scrapeConfig = mock(ScrapeConfig.class);
        awsClientProvider = mock(AWSClientProvider.class);
        resourceMapper = mock(ResourceMapper.class);
        namespaceConfig = mock(NamespaceConfig.class);
        apiClient = mock(ResourceGroupsTaggingApiClient.class);
        resource = mock(Resource.class);
        metricCollector = mock(BasicMetricCollector.class);

        ScrapeConfig scrapeConfig = mock(ScrapeConfig.class);
        expect(scrapeConfigProvider.getScrapeConfig()).andReturn(scrapeConfig).anyTimes();
        expect(scrapeConfig.getGetResourcesResultCacheTTLMinutes()).andReturn(15);
        replayAll();
        testClass = new TagFilterResourceProvider(scrapeConfigProvider, awsClientProvider, resourceMapper,
                new RateLimiter(metricCollector));
        verifyAll();
        resetAll();
    }

    @Test
    void filterResources() {
        expect(namespaceConfig.getName()).andReturn(lambda.name()).anyTimes();
        expect(scrapeConfigProvider.getStandardNamespace("lambda")).andReturn(Optional.of(lambda));
        expect(scrapeConfigProvider.getScrapeConfig()).andReturn(scrapeConfig).anyTimes();
        expect(namespaceConfig.hasTagFilters()).andReturn(true);
        expect(namespaceConfig.getTagFilters()).andReturn(ImmutableMap.of(
                "tag", ImmutableSortedSet.of("value1", "value2")
        ));
        expect(awsClientProvider.getResourceTagClient("region")).andReturn(apiClient);

        Tag tag1 = Tag.builder()
                .key("tag").value("value1")
                .build();
        expect(scrapeConfig.shouldExportTag(tag1)).andReturn(true);
        Tag tag2 = Tag.builder()
                .key("tag").value("value2")
                .build();
        expect(scrapeConfig.shouldExportTag(tag2)).andReturn(true);

        expect(apiClient.getResources(GetResourcesRequest.builder()
                .resourceTypeFilters(ImmutableList.of("lambda:function"))
                .tagFilters(ImmutableList.of(TagFilter.builder()
                        .key("tag").values(ImmutableSortedSet.of("value1", "value2"))
                        .build()))
                .build())).andReturn(
                GetResourcesResponse.builder()
                        .resourceTagMappingList(ImmutableList.of(ResourceTagMapping.builder()
                                .tags(tag1)
                                .resourceARN("arn1")
                                .build()))
                        .paginationToken("token1")
                        .build()
        );
        metricCollector.recordLatency(anyObject(), anyObject(), anyLong());

        expect(resourceMapper.map("arn1")).andReturn(Optional.of(resource));
        resource.setTags(ImmutableList.of(tag1));

        expect(apiClient.getResources(GetResourcesRequest.builder()
                .paginationToken("token1")
                .resourceTypeFilters(ImmutableList.of("lambda:function"))
                .tagFilters(ImmutableList.of(TagFilter.builder()
                        .key("tag").values(ImmutableSortedSet.of("value1", "value2"))
                        .build()))
                .build())).andReturn(
                GetResourcesResponse.builder()
                        .resourceTagMappingList(ImmutableList.of(ResourceTagMapping.builder()
                                .resourceARN("arn2")
                                .tags(tag2)
                                .build()))
                        .paginationToken(null)
                        .build()
        );
        metricCollector.recordLatency(anyObject(), anyObject(), anyLong());

        expect(resourceMapper.map("arn2")).andReturn(Optional.of(resource));
        resource.setTags(ImmutableList.of(tag2));

        expect(resource.getType()).andReturn(ResourceType.LambdaFunction).anyTimes();
        apiClient.close();
        replayAll();
        assertEquals(ImmutableSet.of(resource), testClass.getFilteredResources("region", namespaceConfig));
        verifyAll();
    }

    @Test
    void filterResources_noResourceTypes() {
        expect(namespaceConfig.getName()).andReturn(CWNamespace.kafka.name()).anyTimes();
        expect(scrapeConfigProvider.getStandardNamespace("kafka")).andReturn(Optional.of(kafka));
        expect(scrapeConfigProvider.getScrapeConfig()).andReturn(scrapeConfig).anyTimes();
        expect(namespaceConfig.hasTagFilters()).andReturn(false);
        expect(awsClientProvider.getResourceTagClient("region")).andReturn(apiClient);

        Tag tag1 = Tag.builder()
                .key("tag").value("value1")
                .build();
        expect(scrapeConfig.shouldExportTag(tag1)).andReturn(true);

        Tag tag2 = Tag.builder()
                .key("tag").value("value2")
                .build();
        expect(scrapeConfig.shouldExportTag(tag2)).andReturn(true);

        expect(apiClient.getResources(GetResourcesRequest.builder()
                .resourceTypeFilters(ImmutableList.of("kafka"))
                .build())).andReturn(
                GetResourcesResponse.builder()
                        .resourceTagMappingList(ImmutableList.of(ResourceTagMapping.builder()
                                .tags(tag1)
                                .resourceARN("arn1")
                                .build()))
                        .paginationToken("token1")
                        .build()
        );
        metricCollector.recordLatency(anyObject(), anyObject(), anyLong());
        expect(resourceMapper.map("arn1")).andReturn(Optional.of(resource));
        resource.setTags(ImmutableList.of(tag1));

        expect(apiClient.getResources(GetResourcesRequest.builder()
                .paginationToken("token1")
                .resourceTypeFilters(ImmutableList.of("kafka"))
                .build())).andReturn(
                GetResourcesResponse.builder()
                        .resourceTagMappingList(ImmutableList.of(ResourceTagMapping.builder()
                                .resourceARN("arn2")
                                .tags(tag2)
                                .build()))
                        .paginationToken(null)
                        .build()
        );
        metricCollector.recordLatency(anyObject(), anyObject(), anyLong());
        expect(resourceMapper.map("arn2")).andReturn(Optional.of(resource));
        resource.setTags(ImmutableList.of(tag2));

        expect(resource.getType()).andReturn(ResourceType.LambdaFunction).anyTimes();
        apiClient.close();
        replayAll();
        assertEquals(ImmutableSet.of(resource), testClass.getFilteredResources("region", namespaceConfig));
        verifyAll();
    }

    @Test
    void filterResources_customNamespace() {
        expect(namespaceConfig.getName()).andReturn("lambda");
        expect(scrapeConfigProvider.getStandardNamespace("lambda")).andReturn(Optional.empty());
        expect(scrapeConfigProvider.getScrapeConfig()).andReturn(scrapeConfig).anyTimes();
        replayAll();
        assertEquals(ImmutableSet.of(), testClass.getFilteredResources("region", namespaceConfig));
        verifyAll();
    }
}
