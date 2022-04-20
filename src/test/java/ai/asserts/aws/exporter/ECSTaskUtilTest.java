/*
 *  Copyright © 2020.
 *  Asserts, Inc. - All Rights Reserved
 */
package ai.asserts.aws.exporter;

import ai.asserts.aws.RateLimiter;
import ai.asserts.aws.config.ECSTaskDefScrapeConfig;
import ai.asserts.aws.config.ScrapeConfig;
import ai.asserts.aws.exporter.ECSServiceDiscoveryExporter.StaticConfig;
import ai.asserts.aws.resource.Resource;
import ai.asserts.aws.resource.ResourceMapper;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import org.easymock.EasyMockSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.ecs.EcsClient;
import software.amazon.awssdk.services.ecs.model.Attachment;
import software.amazon.awssdk.services.ecs.model.ContainerDefinition;
import software.amazon.awssdk.services.ecs.model.DescribeTaskDefinitionRequest;
import software.amazon.awssdk.services.ecs.model.DescribeTaskDefinitionResponse;
import software.amazon.awssdk.services.ecs.model.KeyValuePair;
import software.amazon.awssdk.services.ecs.model.PortMapping;
import software.amazon.awssdk.services.ecs.model.Task;
import software.amazon.awssdk.services.ecs.model.TaskDefinition;

import java.util.List;
import java.util.Optional;

import static ai.asserts.aws.MetricNameUtil.SCRAPE_LATENCY_METRIC;
import static ai.asserts.aws.exporter.ECSTaskUtil.ENI;
import static ai.asserts.aws.exporter.ECSTaskUtil.PRIVATE_IPv4ADDRESS;
import static ai.asserts.aws.exporter.ECSTaskUtil.PROMETHEUS_METRIC_PATH_DOCKER_LABEL;
import static ai.asserts.aws.exporter.ECSTaskUtil.PROMETHEUS_PORT_DOCKER_LABEL;
import static org.easymock.EasyMock.anyLong;
import static org.easymock.EasyMock.anyObject;
import static org.easymock.EasyMock.eq;
import static org.easymock.EasyMock.expect;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ECSTaskUtilTest extends EasyMockSupport {
    private ResourceMapper resourceMapper;
    private BasicMetricCollector metricCollector;
    private EcsClient ecsClient;
    private ScrapeConfig scrapeConfig;
    private ECSTaskDefScrapeConfig taskDefScrapeConfig;
    private ECSTaskUtil testClass;
    private Resource cluster;
    private Resource service;
    private Resource task;
    private Resource taskDef;

    @BeforeEach
    public void setup() {
        resourceMapper = mock(ResourceMapper.class);
        metricCollector = mock(BasicMetricCollector.class);
        ecsClient = mock(EcsClient.class);
        scrapeConfig = mock(ScrapeConfig.class);
        taskDefScrapeConfig = mock(ECSTaskDefScrapeConfig.class);
        testClass = new ECSTaskUtil(resourceMapper, new RateLimiter(metricCollector));

        cluster = Resource.builder()
                .name("cluster")
                .region("us-west-2")
                .build();
        service = Resource.builder()
                .name("service")
                .region("us-west-2")
                .build();
        task = Resource.builder()
                .name("task-id")
                .region("us-west-2")
                .build();
        taskDef = Resource.builder()
                .name("task-def")
                .version("5")
                .region("us-west-2")
                .build();
    }

    @Test
    public void hasAllInfo_false() {
        replayAll();
        assertFalse(testClass.hasAllInfo(Task.builder().build()));
        verifyAll();
    }

    @Test
    public void hasAllInfo_true() {
        replayAll();
        assertTrue(testClass.hasAllInfo(Task.builder()
                .lastStatus("RUNNING")
                .attachments(Attachment.builder()
                        .type(ENI)
                        .details(KeyValuePair.builder()
                                .name(PRIVATE_IPv4ADDRESS)
                                .value("10.20.30.40")
                                .build())
                        .build())
                .build()));
        verifyAll();
    }

    @Test
    public void containerWithDockerLabels() {
        expect(resourceMapper.map("task-def-arn")).andReturn(Optional.of(taskDef));
        expect(resourceMapper.map("task-arn")).andReturn(Optional.of(task));
        expect(scrapeConfig.getECSConfigByNameAndPort()).andReturn(ImmutableMap.of());

        TaskDefinition taskDefinition = TaskDefinition.builder()
                .containerDefinitions(ContainerDefinition.builder()
                        .name("model-builder")
                        .dockerLabels(ImmutableMap.of(
                                PROMETHEUS_METRIC_PATH_DOCKER_LABEL, "/metric/path",
                                PROMETHEUS_PORT_DOCKER_LABEL, "8080"
                        ))
                        .build())
                .build();

        expect(ecsClient.describeTaskDefinition(DescribeTaskDefinitionRequest.builder()
                .taskDefinition("task-def-arn")
                .build())).andReturn(DescribeTaskDefinitionResponse.builder()
                .taskDefinition(taskDefinition)
                .build());
        metricCollector.recordLatency(eq(SCRAPE_LATENCY_METRIC), anyObject(), anyLong());

        replayAll();
        List<StaticConfig> staticConfigs = testClass.buildScrapeTargets(scrapeConfig, ecsClient, cluster,
                service, Task.builder()
                        .taskArn("task-arn")
                        .taskDefinitionArn("task-def-arn")
                        .lastStatus("RUNNING")
                        .attachments(Attachment.builder()
                                .type(ENI)
                                .details(KeyValuePair.builder()
                                        .name(PRIVATE_IPv4ADDRESS)
                                        .value("10.20.30.40")
                                        .build())
                                .build())
                        .build());
        assertEquals(1, staticConfigs.size());
        StaticConfig staticConfig = staticConfigs.get(0);
        assertAll(
                () -> assertEquals("cluster", staticConfig.getLabels().getCluster()),
                () -> assertEquals("service", staticConfig.getLabels().getJob()),
                () -> assertEquals("task-def", staticConfig.getLabels().getTaskDefName()),
                () -> assertEquals("5", staticConfig.getLabels().getTaskDefVersion()),
                () -> assertEquals("task-id", staticConfig.getLabels().getTaskId()),
                () -> assertEquals("/metric/path", staticConfig.getLabels().getMetricsPath()),
                () -> assertEquals("model-builder", staticConfig.getLabels().getContainer()),
                () -> assertEquals(ImmutableSet.of("10.20.30.40:8080"), staticConfig.getTargets())
        );
        verifyAll();
    }

    @Test
    public void discoverAllTasksNoConfig() {
        expect(resourceMapper.map("task-def-arn")).andReturn(Optional.of(taskDef));
        expect(resourceMapper.map("task-arn")).andReturn(Optional.of(task));
        expect(scrapeConfig.isDiscoverAllECSTasksByDefault()).andReturn(true).anyTimes();
        expect(scrapeConfig.getECSConfigByNameAndPort()).andReturn(ImmutableMap.of());

        expect(taskDefScrapeConfig.getMetricPath()).andReturn("/prometheus/metrics").anyTimes();

        TaskDefinition taskDefinition = TaskDefinition.builder()
                .containerDefinitions(
                        ContainerDefinition.builder()
                                .name("model-builder")
                                .portMappings(PortMapping.builder()
                                        .hostPort(52341)
                                        .containerPort(8080)
                                        .build())
                                .build(),
                        ContainerDefinition.builder()
                                .name("api-server")
                                .portMappings(
                                        PortMapping.builder()
                                                .hostPort(52342)
                                                .containerPort(8081)
                                                .build(),
                                        PortMapping.builder()
                                                .hostPort(52343)
                                                .containerPort(8082)
                                                .build()
                                ).build()
                ).build();

        expect(ecsClient.describeTaskDefinition(DescribeTaskDefinitionRequest.builder()
                .taskDefinition("task-def-arn")
                .build())).andReturn(DescribeTaskDefinitionResponse.builder()
                .taskDefinition(taskDefinition)
                .build());
        metricCollector.recordLatency(eq(SCRAPE_LATENCY_METRIC), anyObject(), anyLong());

        replayAll();
        List<StaticConfig> staticConfigs = testClass.buildScrapeTargets(scrapeConfig, ecsClient, cluster,
                service, Task.builder()
                        .taskArn("task-arn")
                        .taskDefinitionArn("task-def-arn")
                        .lastStatus("RUNNING")
                        .attachments(Attachment.builder()
                                .type(ENI)
                                .details(KeyValuePair.builder()
                                        .name(PRIVATE_IPv4ADDRESS)
                                        .value("10.20.30.40")
                                        .build())
                                .build())
                        .build());
        assertEquals(2, staticConfigs.size());
        assertAll(
                () -> assertEquals("cluster", staticConfigs.get(0).getLabels().getCluster()),
                () -> assertEquals("service", staticConfigs.get(0).getLabels().getJob()),
                () -> assertEquals("task-def", staticConfigs.get(0).getLabels().getTaskDefName()),
                () -> assertEquals("5", staticConfigs.get(0).getLabels().getTaskDefVersion()),
                () -> assertEquals("task-id", staticConfigs.get(0).getLabels().getTaskId()),
                () -> assertEquals("/metrics", staticConfigs.get(0).getLabels().getMetricsPath()),
                () -> assertEquals("api-server", staticConfigs.get(0).getLabels().getContainer()),
                () -> assertEquals(ImmutableSet.of("10.20.30.40:8081", "10.20.30.40:8082"),
                        staticConfigs.get(0).getTargets())
        );
        assertAll(
                () -> assertEquals("cluster", staticConfigs.get(1).getLabels().getCluster()),
                () -> assertEquals("service", staticConfigs.get(1).getLabels().getJob()),
                () -> assertEquals("task-def", staticConfigs.get(1).getLabels().getTaskDefName()),
                () -> assertEquals("5", staticConfigs.get(1).getLabels().getTaskDefVersion()),
                () -> assertEquals("task-id", staticConfigs.get(1).getLabels().getTaskId()),
                () -> assertEquals("/metrics", staticConfigs.get(1).getLabels().getMetricsPath()),
                () -> assertEquals("model-builder", staticConfigs.get(1).getLabels().getContainer()),
                () -> assertEquals(ImmutableSet.of("10.20.30.40:8080"), staticConfigs.get(1).getTargets())
        );
        verifyAll();
    }

    @Test
    public void discoverAllTasksSomeWithConfig() {
        expect(resourceMapper.map("task-def-arn")).andReturn(Optional.of(taskDef));
        expect(resourceMapper.map("task-arn")).andReturn(Optional.of(task));
        expect(scrapeConfig.isDiscoverAllECSTasksByDefault()).andReturn(true).anyTimes();
        expect(scrapeConfig.getECSConfigByNameAndPort()).andReturn(
                ImmutableMap.of("api-server",
                        ImmutableMap.of(-1, taskDefScrapeConfig)));

        expect(taskDefScrapeConfig.getMetricPath()).andReturn("/prometheus/metrics").anyTimes();

        TaskDefinition taskDefinition = TaskDefinition.builder()
                .containerDefinitions(
                        ContainerDefinition.builder()
                                .name("model-builder")
                                .portMappings(PortMapping.builder()
                                        .hostPort(52341)
                                        .containerPort(8080)
                                        .build())
                                .build(),
                        ContainerDefinition.builder()
                                .name("api-server")
                                .portMappings(
                                        PortMapping.builder()
                                                .hostPort(52342)
                                                .containerPort(8081)
                                                .build(),
                                        PortMapping.builder()
                                                .hostPort(52343)
                                                .containerPort(8082)
                                                .build()
                                ).build()
                ).build();

        expect(ecsClient.describeTaskDefinition(DescribeTaskDefinitionRequest.builder()
                .taskDefinition("task-def-arn")
                .build())).andReturn(DescribeTaskDefinitionResponse.builder()
                .taskDefinition(taskDefinition)
                .build());
        metricCollector.recordLatency(eq(SCRAPE_LATENCY_METRIC), anyObject(), anyLong());

        replayAll();
        List<StaticConfig> staticConfigs = testClass.buildScrapeTargets(scrapeConfig, ecsClient, cluster,
                service, Task.builder()
                        .taskArn("task-arn")
                        .taskDefinitionArn("task-def-arn")
                        .lastStatus("RUNNING")
                        .attachments(Attachment.builder()
                                .type(ENI)
                                .details(KeyValuePair.builder()
                                        .name(PRIVATE_IPv4ADDRESS)
                                        .value("10.20.30.40")
                                        .build())
                                .build())
                        .build());
        assertEquals(2, staticConfigs.size());
        assertAll(
                () -> assertEquals("cluster", staticConfigs.get(0).getLabels().getCluster()),
                () -> assertEquals("service", staticConfigs.get(0).getLabels().getJob()),
                () -> assertEquals("task-def", staticConfigs.get(0).getLabels().getTaskDefName()),
                () -> assertEquals("5", staticConfigs.get(0).getLabels().getTaskDefVersion()),
                () -> assertEquals("task-id", staticConfigs.get(0).getLabels().getTaskId()),
                () -> assertEquals("/prometheus/metrics", staticConfigs.get(0).getLabels().getMetricsPath()),
                () -> assertEquals("api-server", staticConfigs.get(0).getLabels().getContainer()),
                () -> assertEquals(ImmutableSet.of("10.20.30.40:8081", "10.20.30.40:8082"),
                        staticConfigs.get(0).getTargets())
        );
        assertAll(
                () -> assertEquals("cluster", staticConfigs.get(1).getLabels().getCluster()),
                () -> assertEquals("service", staticConfigs.get(1).getLabels().getJob()),
                () -> assertEquals("task-def", staticConfigs.get(1).getLabels().getTaskDefName()),
                () -> assertEquals("5", staticConfigs.get(1).getLabels().getTaskDefVersion()),
                () -> assertEquals("task-id", staticConfigs.get(1).getLabels().getTaskId()),
                () -> assertEquals("/metrics", staticConfigs.get(1).getLabels().getMetricsPath()),
                () -> assertEquals("model-builder", staticConfigs.get(1).getLabels().getContainer()),
                () -> assertEquals(ImmutableSet.of("10.20.30.40:8080"), staticConfigs.get(1).getTargets())
        );
        verifyAll();
    }

    @Test
    public void discoverAllTasksSpecificConfigForSpecificPorts() {
        expect(resourceMapper.map("task-def-arn")).andReturn(Optional.of(taskDef));
        expect(resourceMapper.map("task-arn")).andReturn(Optional.of(task));
        expect(scrapeConfig.isDiscoverAllECSTasksByDefault()).andReturn(true).anyTimes();
        expect(scrapeConfig.getECSConfigByNameAndPort()).andReturn(
                ImmutableMap.of("api-server",
                        ImmutableMap.of(8081, taskDefScrapeConfig)));

        expect(taskDefScrapeConfig.getMetricPath()).andReturn("/prometheus/metrics").anyTimes();

        TaskDefinition taskDefinition = TaskDefinition.builder()
                .containerDefinitions(
                        ContainerDefinition.builder()
                                .name("model-builder")
                                .portMappings(PortMapping.builder()
                                        .hostPort(52341)
                                        .containerPort(8080)
                                        .build())
                                .build(),
                        ContainerDefinition.builder()
                                .name("api-server")
                                .portMappings(
                                        PortMapping.builder()
                                                .hostPort(52342)
                                                .containerPort(8081)
                                                .build(),
                                        PortMapping.builder()
                                                .hostPort(52343)
                                                .containerPort(8082)
                                                .build()
                                ).build()
                ).build();

        expect(ecsClient.describeTaskDefinition(DescribeTaskDefinitionRequest.builder()
                .taskDefinition("task-def-arn")
                .build())).andReturn(DescribeTaskDefinitionResponse.builder()
                .taskDefinition(taskDefinition)
                .build());
        metricCollector.recordLatency(eq(SCRAPE_LATENCY_METRIC), anyObject(), anyLong());

        replayAll();
        List<StaticConfig> staticConfigs = testClass.buildScrapeTargets(scrapeConfig, ecsClient, cluster,
                service, Task.builder()
                        .taskArn("task-arn")
                        .taskDefinitionArn("task-def-arn")
                        .lastStatus("RUNNING")
                        .attachments(Attachment.builder()
                                .type(ENI)
                                .details(KeyValuePair.builder()
                                        .name(PRIVATE_IPv4ADDRESS)
                                        .value("10.20.30.40")
                                        .build())
                                .build())
                        .build());
        assertEquals(3, staticConfigs.size());
        assertAll(
                () -> assertEquals("cluster", staticConfigs.get(0).getLabels().getCluster()),
                () -> assertEquals("service", staticConfigs.get(0).getLabels().getJob()),
                () -> assertEquals("task-def", staticConfigs.get(0).getLabels().getTaskDefName()),
                () -> assertEquals("5", staticConfigs.get(0).getLabels().getTaskDefVersion()),
                () -> assertEquals("task-id", staticConfigs.get(0).getLabels().getTaskId()),
                () -> assertEquals("/prometheus/metrics", staticConfigs.get(0).getLabels().getMetricsPath()),
                () -> assertEquals("api-server", staticConfigs.get(0).getLabels().getContainer()),
                () -> assertEquals(ImmutableSet.of("10.20.30.40:8081"),
                        staticConfigs.get(0).getTargets())
        );
        assertAll(
                () -> assertEquals("cluster", staticConfigs.get(1).getLabels().getCluster()),
                () -> assertEquals("service", staticConfigs.get(1).getLabels().getJob()),
                () -> assertEquals("task-def", staticConfigs.get(1).getLabels().getTaskDefName()),
                () -> assertEquals("5", staticConfigs.get(1).getLabels().getTaskDefVersion()),
                () -> assertEquals("task-id", staticConfigs.get(1).getLabels().getTaskId()),
                () -> assertEquals("/metrics", staticConfigs.get(1).getLabels().getMetricsPath()),
                () -> assertEquals("api-server", staticConfigs.get(1).getLabels().getContainer()),
                () -> assertEquals(ImmutableSet.of("10.20.30.40:8082"),
                        staticConfigs.get(1).getTargets())
        );
        assertAll(
                () -> assertEquals("cluster", staticConfigs.get(2).getLabels().getCluster()),
                () -> assertEquals("service", staticConfigs.get(2).getLabels().getJob()),
                () -> assertEquals("task-def", staticConfigs.get(2).getLabels().getTaskDefName()),
                () -> assertEquals("5", staticConfigs.get(2).getLabels().getTaskDefVersion()),
                () -> assertEquals("task-id", staticConfigs.get(2).getLabels().getTaskId()),
                () -> assertEquals("/metrics", staticConfigs.get(2).getLabels().getMetricsPath()),
                () -> assertEquals("model-builder", staticConfigs.get(2).getLabels().getContainer()),
                () -> assertEquals(ImmutableSet.of("10.20.30.40:8080"), staticConfigs.get(2).getTargets())
        );
        verifyAll();
    }

    @Test
    public void discoverOnlyConfiguredTasks() {
        expect(resourceMapper.map("task-def-arn")).andReturn(Optional.of(taskDef));
        expect(resourceMapper.map("task-arn")).andReturn(Optional.of(task));
        expect(scrapeConfig.isDiscoverAllECSTasksByDefault()).andReturn(false).anyTimes();
        expect(scrapeConfig.getECSConfigByNameAndPort()).andReturn(
                ImmutableMap.of("api-server", ImmutableMap.of(8081, taskDefScrapeConfig)));

        expect(taskDefScrapeConfig.getMetricPath()).andReturn("/prometheus/metrics").anyTimes();

        TaskDefinition taskDefinition = TaskDefinition.builder()
                .containerDefinitions(
                        ContainerDefinition.builder()
                                .name("model-builder")
                                .portMappings(PortMapping.builder()
                                        .hostPort(52341)
                                        .containerPort(8080)
                                        .build())
                                .build(),
                        ContainerDefinition.builder()
                                .name("api-server")
                                .portMappings(
                                        PortMapping.builder()
                                                .hostPort(52342)
                                                .containerPort(8081)
                                                .build(),
                                        PortMapping.builder()
                                                .hostPort(52343)
                                                .containerPort(8082)
                                                .build()
                                ).build()
                ).build();

        expect(ecsClient.describeTaskDefinition(DescribeTaskDefinitionRequest.builder()
                .taskDefinition("task-def-arn")
                .build())).andReturn(DescribeTaskDefinitionResponse.builder()
                .taskDefinition(taskDefinition)
                .build());
        metricCollector.recordLatency(eq(SCRAPE_LATENCY_METRIC), anyObject(), anyLong());

        replayAll();
        List<StaticConfig> staticConfigs = testClass.buildScrapeTargets(scrapeConfig, ecsClient, cluster,
                service, Task.builder()
                        .taskArn("task-arn")
                        .taskDefinitionArn("task-def-arn")
                        .lastStatus("RUNNING")
                        .attachments(Attachment.builder()
                                .type(ENI)
                                .details(KeyValuePair.builder()
                                        .name(PRIVATE_IPv4ADDRESS)
                                        .value("10.20.30.40")
                                        .build())
                                .build())
                        .build());
        assertEquals(1, staticConfigs.size());
        assertAll(
                () -> assertEquals("cluster", staticConfigs.get(0).getLabels().getCluster()),
                () -> assertEquals("service", staticConfigs.get(0).getLabels().getJob()),
                () -> assertEquals("task-def", staticConfigs.get(0).getLabels().getTaskDefName()),
                () -> assertEquals("5", staticConfigs.get(0).getLabels().getTaskDefVersion()),
                () -> assertEquals("task-id", staticConfigs.get(0).getLabels().getTaskId()),
                () -> assertEquals("/prometheus/metrics", staticConfigs.get(0).getLabels().getMetricsPath()),
                () -> assertEquals("api-server", staticConfigs.get(0).getLabels().getContainer()),
                () -> assertEquals(ImmutableSet.of("10.20.30.40:8081"),
                        staticConfigs.get(0).getTargets())
        );
        verifyAll();
    }

}
