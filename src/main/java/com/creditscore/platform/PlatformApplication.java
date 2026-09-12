package com.creditscore.platform;

import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.data.web.config.EnableSpringDataWebSupport;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * {@code pageSerializationMode = VIA_DTO} fixes Spring Data's own "Serializing PageImpl
 * instances as-is is not supported" warning: it transparently wraps every {@code Page}
 * return value (BusinessController, TransactionController) in the stable
 * {@code PagedModel} shape ({@code {content, page: {size, number, totalElements,
 * totalPages}}}) at serialization time, instead of PageImpl's own unstable field set
 * ({@code pageable}, {@code sort}, {@code first}, {@code last}, etc. — never guaranteed
 * across versions). This is a real breaking change to those two endpoints' JSON shape;
 * nothing in this repo's README or Postman collection asserts on the old one.
 */
@SpringBootApplication
@EnableScheduling
@EnableSchedulerLock(defaultLockAtMostFor = "PT30M")
@ConfigurationPropertiesScan
@EnableSpringDataWebSupport(pageSerializationMode = EnableSpringDataWebSupport.PageSerializationMode.VIA_DTO)
public class PlatformApplication {

    public static void main(String[] args) {
        SpringApplication.run(PlatformApplication.class, args);
    }
}
