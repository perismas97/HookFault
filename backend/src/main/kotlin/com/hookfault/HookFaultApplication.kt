package com.hookfault

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class HookFaultApplication

fun main(args: Array<String>) {
    runApplication<HookFaultApplication>(*args)
}
