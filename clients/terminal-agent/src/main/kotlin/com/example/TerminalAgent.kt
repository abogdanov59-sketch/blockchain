package com.example

import kotlinx.cli.ArgParser
import kotlinx.cli.ArgType
import kotlinx.cli.default

fun main(args: Array<String>) {
    val parser = ArgParser("terminal-agent")
    val command by parser.option(ArgType.Choice(listOf("health", "capabilities"), { it }, { it }), shortName = "c", description = "Command to execute").default("health")
    parser.parse(args)

    when (command) {
        "health" -> println("Terminal agent is configured. mTLS/bootstrap flows to be implemented.")
        "capabilities" -> println("Supports secure submissions of shipment and lab artifacts (coming soon).")
    }
}
