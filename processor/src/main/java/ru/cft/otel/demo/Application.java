package ru.cft.otel.demo;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.metrics.LongCounter;
import io.opentelemetry.api.metrics.Meter;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.hc.client5.http.classic.HttpClient;
import org.apache.hc.client5.http.classic.methods.HttpPatch;
import org.apache.hc.client5.http.entity.UrlEncodedFormEntity;
import org.apache.hc.client5.http.impl.classic.BasicHttpClientResponseHandler;
import org.apache.hc.client5.http.impl.classic.HttpClientBuilder;
import org.apache.hc.core5.http.ClassicHttpRequest;
import org.apache.hc.core5.http.message.BasicNameValuePair;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.boot.Banner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;

import static io.opentelemetry.api.common.AttributeKey.stringKey;

@SpringBootApplication
public class Application {

    @Configuration
    static class OpenTelemetryConfiguration {
        @Bean
        public OpenTelemetry openTelemetry() {
            return GlobalOpenTelemetry.get();
        }
    }

    @Slf4j
    @Component
    @RequiredArgsConstructor
    static class EventProcessor {
        private static final AttributeKey<String> STATUS_KEY = stringKey("status");
        private final OpenTelemetry openTelemetry;
        private final EventServiceHttpClient eventServiceHttpClient;

        @RabbitListener(queues = "events")
        public void process(Message message) {
            String id = message.getMessageProperties().getMessageId();
            String body = new String(message.getBody());

            boolean processed = doProcess(id, body);

            eventServiceHttpClient.update(id, processed ? "processing_succeeded" : "processing_failed");
        }

        private boolean doProcess(String id, String body) {
            Tracer tracer = openTelemetry.getTracer("processor");
            Meter meter = openTelemetry.getMeter("processor");

            Span span = tracer.spanBuilder("process event").startSpan();
            LongCounter counter = meter.counterBuilder("event_processing_operation_count")
                    .setDescription("Number of processed events")
                    .build();

            try (Scope ignore = span.makeCurrent()) {
                try {
                    // do some stuff
                    if (id.hashCode() % 2 == 0) {
                        throw new ProcessingException("Some exception occured");
                    }
                    counter.add(1, Attributes.of(STATUS_KEY, "succeeded"));
                    log.info("Event processed successfully: id = {}", id);
                    return true;
                } catch (Exception e) {
                    counter.add(1, Attributes.of(STATUS_KEY, "failed"));
                    span.recordException(e);
                    span.setStatus(StatusCode.ERROR);
                    log.warn("Event processed with error: id = {}", id, e);
                    return false;
                }
            } finally {
                span.end();
            }
        }
    }

    @Component
    @RequiredArgsConstructor
    static class EventServiceHttpClient {
        private final HttpClient httpClient = HttpClientBuilder.create().build();

        public void update(String id, String status) {
            try {
                httpClient.execute(createRequest(id, status), new BasicHttpClientResponseHandler());
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        private ClassicHttpRequest createRequest(String id, String status) {
            HttpPatch httpPatch = new HttpPatch("http://localhost:8080/events");
            httpPatch.setEntity(
                    new UrlEncodedFormEntity(
                            List.of(
                                    new BasicNameValuePair("id", id),
                                    new BasicNameValuePair("status", status)
                            )
                    )
            );
            return httpPatch;
        }
    }

    static class ProcessingException extends RuntimeException {
        public ProcessingException(String message) {
            super(message);
        }
    }

    public static void main(String[] args) {
        SpringApplication application = new SpringApplication(Application.class);
        application.setBannerMode(Banner.Mode.OFF);
        application.run(args);
    }
}
