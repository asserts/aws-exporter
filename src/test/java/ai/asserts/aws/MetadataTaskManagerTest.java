/*
 *  Copyright © 2020.
 *  Asserts, Inc. - All Rights Reserved
 */
package ai.asserts.aws;

import ai.asserts.aws.cloudwatch.config.LogScrapeConfig;
import ai.asserts.aws.cloudwatch.config.NamespaceConfig;
import ai.asserts.aws.cloudwatch.config.ScrapeConfig;
import ai.asserts.aws.cloudwatch.config.ScrapeConfigProvider;
import ai.asserts.aws.exporter.BasicMetricCollector;
import ai.asserts.aws.exporter.LambdaCapacityExporter;
import ai.asserts.aws.exporter.LambdaEventSourceExporter;
import ai.asserts.aws.exporter.LambdaInvokeConfigExporter;
import ai.asserts.aws.exporter.LambdaLogMetricScrapeTask;
import ai.asserts.aws.exporter.ResourceTagExporter;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import io.prometheus.client.CollectorRegistry;
import org.easymock.Capture;
import org.easymock.EasyMockSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.AutowireCapableBeanFactory;

import java.util.Optional;
import java.util.concurrent.ExecutorService;

import static org.easymock.EasyMock.capture;
import static org.easymock.EasyMock.expect;
import static org.easymock.EasyMock.newCapture;

public class MetadataTaskManagerTest extends EasyMockSupport {
    private CollectorRegistry collectorRegistry;
    private LambdaCapacityExporter lambdaCapacityExporter;
    private LambdaEventSourceExporter lambdaEventSourceExporter;
    private LambdaInvokeConfigExporter lambdaInvokeConfigExporter;
    private BasicMetricCollector metricCollector;
    private ResourceTagExporter resourceTagExporter;
    private TaskThreadPool taskThreadPool;
    private ExecutorService executorService;
    private AutowireCapableBeanFactory autowireCapableBeanFactory;
    private ScrapeConfigProvider scrapeConfigProvider;
    private ScrapeConfig scrapeConfig;
    private NamespaceConfig namespaceConfig;
    private LogScrapeConfig logScrapeConfig;
    private LambdaLogMetricScrapeTask logMetricScrapeTask;
    private MetadataTaskManager testClass;

    @BeforeEach
    public void setup() {
        collectorRegistry = mock(CollectorRegistry.class);
        lambdaCapacityExporter = mock(LambdaCapacityExporter.class);
        lambdaEventSourceExporter = mock(LambdaEventSourceExporter.class);
        lambdaInvokeConfigExporter = mock(LambdaInvokeConfigExporter.class);
        metricCollector = mock(BasicMetricCollector.class);
        resourceTagExporter = mock(ResourceTagExporter.class);
        taskThreadPool = mock(TaskThreadPool.class);
        executorService = mock(ExecutorService.class);
        scrapeConfigProvider = mock(ScrapeConfigProvider.class);
        scrapeConfig = mock(ScrapeConfig.class);
        namespaceConfig = mock(NamespaceConfig.class);
        logScrapeConfig = mock(LogScrapeConfig.class);
        autowireCapableBeanFactory = mock(AutowireCapableBeanFactory.class);
        logMetricScrapeTask = mock(LambdaLogMetricScrapeTask.class);
        testClass = new MetadataTaskManager(autowireCapableBeanFactory, collectorRegistry, lambdaCapacityExporter,
                lambdaEventSourceExporter, lambdaInvokeConfigExporter, metricCollector, resourceTagExporter,
                taskThreadPool, scrapeConfigProvider) {

            @Override
            LambdaLogMetricScrapeTask newLogScrapeTask(String region) {
                return logMetricScrapeTask;
            }
        };
    }

    @Test
    public void afterPropertiesSet() {
        expect(lambdaCapacityExporter.register(collectorRegistry)).andReturn(null);
        expect(lambdaEventSourceExporter.register(collectorRegistry)).andReturn(null);
        expect(lambdaInvokeConfigExporter.register(collectorRegistry)).andReturn(null);
        expect(metricCollector.register(collectorRegistry)).andReturn(null);
        expect(resourceTagExporter.register(collectorRegistry)).andReturn(null);
        expect(scrapeConfigProvider.getScrapeConfig()).andReturn(scrapeConfig);
        expect(scrapeConfig.getLambdaConfig()).andReturn(Optional.of(namespaceConfig));
        expect(namespaceConfig.getLogs()).andReturn(ImmutableList.of(logScrapeConfig)).anyTimes();
        expect(scrapeConfig.getRegions()).andReturn(ImmutableSet.of("region1"));
        autowireCapableBeanFactory.autowireBean(logMetricScrapeTask);
        expect(logMetricScrapeTask.register(collectorRegistry)).andReturn(null);
        replayAll();
        testClass.afterPropertiesSet();
        verifyAll();
    }

    @Test
    public void updateMetadata() {
        testClass.getLogScrapeTasks().add(logMetricScrapeTask);
        expect(taskThreadPool.getExecutorService()).andReturn(executorService).anyTimes();
        Capture<Runnable> capture1 = newCapture();
        Capture<Runnable> capture2 = newCapture();
        Capture<Runnable> capture3 = newCapture();
        Capture<Runnable> capture4 = newCapture();

        expect(executorService.submit(capture(capture1))).andReturn(null);
        expect(executorService.submit(capture(capture2))).andReturn(null);
        expect(executorService.submit(capture(capture3))).andReturn(null);
        expect(executorService.submit(capture(capture4))).andReturn(null);

        lambdaCapacityExporter.update();
        lambdaEventSourceExporter.update();
        lambdaInvokeConfigExporter.update();
        logMetricScrapeTask.update();

        replayAll();
        testClass.updateMetadata();

        capture1.getValue().run();
        capture2.getValue().run();
        capture3.getValue().run();
        capture4.getValue().run();

        verifyAll();
    }
}
