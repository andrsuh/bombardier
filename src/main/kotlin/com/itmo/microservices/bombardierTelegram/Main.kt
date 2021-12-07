package com.itmo.microservices.bombardierTelegram

import com.github.kotlintelegrambot.bot
import com.github.kotlintelegrambot.entities.ChatId
import com.github.kotlintelegrambot.entities.TelegramFile
import com.itmo.microservices.bombardierCore.DemoServiceApplication
import com.itmo.microservices.bombardierCore.bombardier.controller.AllTestsFinishedEvent
import com.itmo.microservices.bombardierCore.bombardier.controller.AllTestsRunner
import com.itmo.microservices.bombardierCore.bombardier.controller.BombardierController
import org.springframework.beans.factory.support.DefaultListableBeanFactory
import org.springframework.beans.factory.support.RootBeanDefinition
import org.springframework.boot.runApplication
import org.springframework.context.ApplicationListener
import org.springframework.stereotype.Component
import java.io.*
import kotlin.system.exitProcess

fun main(args: Array<String>) {
    @Component
    class Config {
        lateinit var logFile: File
        lateinit var oldStdOut: PrintStream
    }

    @Component
    class Listener : ApplicationListener<AllTestsFinishedEvent> {
        val quote = listOf(
            "Если предали один раз, то это только первый раз. Если предали еще – это уже второй.",
            "Ненавижу, когда сначала и не выполняют, когда потом любят, а потом сначала, когда потом никогда ни потом..",
            "Как корабль назовешь, так его и называй", "Тишина тоже музыка... когда оглох",
            "Даже железному терпению приходит конец – когда какать приспичило пипец...",
            "Не губы красят девушек, а девушки красят губы", "Не ругайте ленивых, они же ничего не сделали",
            "Хитрый человек всегда хитрее обычного человека",
            "Я никогда не был идеальным, начиная от внешности и заканчивая характером, но зато я всегда был",
            "Если волк дышит, то волк живой. Если волк не дышит – умер волк",
            "Способен не опаздывать лишь тот, кто способен опаздывать заранее"
        ).random()

        override fun onApplicationEvent(p0: AllTestsFinishedEvent) {
            val cfg = (p0.source as AllTestsRunner).appContext.getBean(Config::class.java)
            System.setOut(cfg.oldStdOut)
            println("Hello back to old stdout!")

            val bot = bot {
                token = System.getProperty("bot.key")
            }

            bot.sendDocument(ChatId.fromChannelUsername("sd2021testing"), TelegramFile.ByFile(cfg.logFile), quote)

            exitProcess(0)
        }
    }

    System.setProperty("bot.key", args[0])
    val app = runApplication<DemoServiceApplication>(*args)

    app.addApplicationListener(Listener())

    val root = File("${System.getProperty("user.home")}/.bombardier")
    if (!root.exists()) {
        root.mkdir()
    }

    (app.beanFactory as DefaultListableBeanFactory).registerBeanDefinition("configBeanKek", RootBeanDefinition(Config::class.java))

    val testingStartMs = System.currentTimeMillis()
    val newLogFile = root.resolve("${testingStartMs}.log")

    app.getBean(Config::class.java).apply {
        logFile = newLogFile
        oldStdOut = System.out
    }

    println("\nRedirecting stdout to ${newLogFile.path}")
    System.setOut(PrintStream(BufferedOutputStream(FileOutputStream(newLogFile))))

    app.getBean(BombardierController::class.java).runTestForAll()
}
