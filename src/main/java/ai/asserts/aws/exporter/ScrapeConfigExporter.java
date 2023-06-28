/*
 *  Copyright © 2020.
 *  Asserts, Inc. - All Rights Reserved
 */
package ai.asserts.aws.exporter;

import ai.asserts.aws.EnvironmentConfig;
import ai.asserts.aws.ScrapeConfigProvider;
import ai.asserts.aws.account.AccountProvider;
import com.google.common.collect.ImmutableMap;
import io.prometheus.client.Collector;
import io.prometheus.client.Collector.MetricFamilySamples.Sample;
import io.prometheus.client.CollectorRegistry;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

import static ai.asserts.aws.MetricNameUtil.SCRAPE_NAMESPACE_LABEL;
import static ai.asserts.aws.MetricNameUtil.TENANT;

@AllArgsConstructor
@Component
@Slf4j
public class ScrapeConfigExporter extends Collector implements InitializingBean {
    private final EnvironmentConfig environmentConfig;
    private final AccountProvider accountProvider;
    private final ScrapeConfigProvider scrapeConfigProvider;
    private final MetricSampleBuilder sampleBuilder;
    private final CollectorRegistry collectorRegistry;

    @Override
    public void afterPropertiesSet() {
        register(collectorRegistry);
    }

    @Override
    public List<MetricFamilySamples> collect() {
        List<MetricFamilySamples> metricFamilySamples = new ArrayList<>();
        if (environmentConfig.isProcessingOn()) {
            try {
                List<Sample> samples = new ArrayList<>();
                accountProvider.getAccounts().forEach(awsAccount ->
                        scrapeConfigProvider.getScrapeConfig(awsAccount.getTenant())
                                .getNamespaces()
                                .forEach(namespaceConfig ->
                                        scrapeConfigProvider.getStandardNamespace(namespaceConfig.getName())
                                                .flatMap(cwNamespace -> sampleBuilder.buildSingleSample(
                                                        "aws_exporter_scrape_interval",
                                                        ImmutableMap.of(
                                                                SCRAPE_NAMESPACE_LABEL,
                                                                cwNamespace.getNormalizedNamespace(),
                                                                TENANT,
                                                                awsAccount.getTenant()
                                                        ),
                                                        namespaceConfig.getEffectiveScrapeInterval() * 1.0D))
                                                .ifPresent(samples::add)));

                if (samples.size() > 0) {
                    sampleBuilder.buildFamily(samples).ifPresent(metricFamilySamples::add);
                }
            } catch (Exception e) {
                log.error("Failed to build metric samples", e);
            }
        }
        return metricFamilySamples;
    }
}
