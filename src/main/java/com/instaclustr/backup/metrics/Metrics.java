package com.instaclustr.backup.metrics;

import com.instaclustr.backup.BaseArguments;
import io.prometheus.client.Counter;
import io.prometheus.client.Histogram;
import io.prometheus.client.exporter.HTTPServer;
import io.prometheus.client.hotspot.DefaultExports;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Metrics {
    private static final Logger logger = LoggerFactory.getLogger(Metrics.class);

    private HTTPServer server;

    public Metrics(BaseArguments arguments) {
        DefaultExports.initialize();
        try {
            server = new HTTPServer(arguments.prometheusMetricsPort, true);
        } catch (Exception e) {
            logger.error("Failed to initalize HTTPServer", e);
        }
    }

    private static final Counter incomingCounter = Counter.build()
            .name("http_requests_total")
            .help("Total incoming requests")
            .register();

    private static final Counter notFoundCounter = Counter.build()
            .name("http_url_not_found_total")
            .help("Total requests to invalid url")
            .register();

    private static final Counter responses = Counter.build()
            .name("http_response_status_codes_total")
            .help("Count of different http status codes returned")
            .labelNames("datatype", "status")
            .register();

    private static final Histogram requestLatency = Histogram.build()
            .name("http_request_latency_seconds")
            .help("request latency in seconds")
            .labelNames("datatype")
            .register();
//
//    public Response monitor(String name, Supplier<Response> f) {
//        String verifiedName = contentList.containsName(name) ? name : UNKNOWN;
//        Histogram.Timer timer = requestLatency.labels(verifiedName).startTimer();
//        try {
//            Response response = f.get();
//            responses.labels(verifiedName, Integer.toString(response.getStatus())).inc();
//            return response;
//        } finally {
//            timer.observeDuration();
//        }
//    }

    public void incIncomingCounter() {
        incomingCounter.inc();
    }

    public void incNotFoundCounter() {
        notFoundCounter.inc();
    }
}
