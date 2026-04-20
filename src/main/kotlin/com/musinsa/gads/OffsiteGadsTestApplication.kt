package com.musinsa.gads

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication

@SpringBootApplication
@ConfigurationPropertiesScan
class OffsiteGadsTestApplication

fun main(args: Array<String>) {
    runApplication<OffsiteGadsTestApplication>(*args)
}
