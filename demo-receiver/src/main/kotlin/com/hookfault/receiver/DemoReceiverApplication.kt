package com.hookfault.receiver

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication(proxyBeanMethods = false)
class DemoReceiverApplication

fun main(args: Array<String>) {
    runApplication<DemoReceiverApplication>(*args)
}
