/*
 *  Copyright © 2020.
 *  Asserts, Inc. - All Rights Reserved
 */
package ai.asserts.aws.cloudwatch;

import ai.asserts.aws.exporter.OpenTelemetryMetricConverter;
import io.opentelemetry.proto.collector.metrics.v1.ExportMetricsServiceRequest;
import io.prometheus.client.Collector;
import io.prometheus.client.CollectorRegistry;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@Component
@Setter
@Slf4j
public class MetricStreamProcessor extends Collector implements InitializingBean {
    private CollectorRegistry collectorRegistry;
    private OpenTelemetryMetricConverter openTelemetryMetricConverter;
    private volatile List<Collector.MetricFamilySamples> metricFamilySamplesList;

    public MetricStreamProcessor(CollectorRegistry collectorRegistry,
                                 OpenTelemetryMetricConverter openTelemetryMetricConverter) {
        this.collectorRegistry = collectorRegistry;
        this.openTelemetryMetricConverter = openTelemetryMetricConverter;
        metricFamilySamplesList = new ArrayList<>();
        log.info("Created MetricStreamController!");
    }

    @Override
    public void afterPropertiesSet() {
        collectorRegistry.register(this);
    }

    public void process(ExportMetricsServiceRequest request) {
        log.info("Received metrics payload from CloudWatch");
        metricFamilySamplesList.addAll(openTelemetryMetricConverter.buildSamplesFromOT(request));
    }

    @Override
    public List<MetricFamilySamples> collect() {
        List<MetricFamilySamples> returnValue = metricFamilySamplesList;
        metricFamilySamplesList = new ArrayList<>();
        return CollectionUtils.isEmpty(returnValue) ? Collections.emptyList() : returnValue;
    }
}
