package ru.cft.otel.demo;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.AmqpTemplate;
import org.springframework.amqp.core.Message;
import org.springframework.boot.Banner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.stereotype.Repository;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@SpringBootApplication
public class Application {

    @Slf4j
    @RestController
    @RequestMapping("/events")
    @RequiredArgsConstructor
    static class EventController {
        private final EventRepository eventRepository;
        private final AmqpTemplate amqpTemplate;

        @PostMapping(consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
        public void add(@RequestParam("body") String body) {
            String id = UUID.randomUUID().toString();
            String status = "new";

            eventRepository.add(id, status, body);
            log.info("Event added: id = {}, status = {}", id, status);

            Message amqpMessage = new Message(body.getBytes());
            amqpMessage.getMessageProperties().setMessageId(id);
            amqpTemplate.send("events", null, amqpMessage);
        }

        @PatchMapping(consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
        public void update(@RequestParam("id") String id, @RequestParam("status") String status) {
            eventRepository.updateStatus(id, status);
            log.info("Event updated: id = {}, status = {}", id, status);
        }
    }

    @Repository
    @RequiredArgsConstructor
    static class EventRepository {
        private final JdbcOperations jdbcOperations;

        public void add(String id, String status, String body) {
            jdbcOperations.update("insert into event (id, status, body) values (?, ?, ?)", id, status, body);
        }

        public void updateStatus(String id, String status) {
            jdbcOperations.update("update event set status = ? where id = ?", status, id);
        }
    }

    public static void main(String[] args) {
        SpringApplication application = new SpringApplication(Application.class);
        application.setBannerMode(Banner.Mode.OFF);
        application.run(args);
    }
}
