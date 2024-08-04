package org.example.chat

import io.grpc.ManagedChannel
import io.grpc.ManagedChannelBuilder
import io.grpc.stub.StreamObserver
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.consumeAsFlow
import java.io.Closeable
import java.util.*
import java.util.concurrent.TimeUnit

class ChatClient(private val channel: ManagedChannel) : Closeable {
    private val stub = ChatServiceGrpc.newStub(channel)
    private val scanner = Scanner(System.`in`)

    fun joinChat() = runBlocking {
        val messageChannel = Channel<ChatMessage>()

        val responseObserver = object : StreamObserver<ChatMessage> {
            override fun onNext(message: ChatMessage) {
                println("${message.userName}: ${message.message}")
                if (message.userName == "Server" && message.message.contains("Please enter")) {
                    print("> ")
                }
            }

            override fun onError(t: Throwable) {
                println("\nError: ${t.message}")
            }

            override fun onCompleted() {
                println("\nChat ended")
            }
        }

        val requestObserver = stub.joinChat(responseObserver)

        launch {
            messageChannel.consumeAsFlow().collect { message ->
                requestObserver.onNext(message)
            }
        }

        println("Enter your username:")
        val userName = withContext(Dispatchers.IO) {
            scanner.nextLine()
        }

        messageChannel.send(ChatMessage.newBuilder().setUserName(userName).build())

        while (true) {
            val userInput = withContext(Dispatchers.IO) {
                scanner.nextLine()
            }
            if (userInput.isNotBlank()) {
                val chatMessage = ChatMessage.newBuilder()
                    .setUserName(userName)
                    .setMessage(userInput)
                    .build()
                messageChannel.send(chatMessage)
            }
        }
    }

    override fun close() {
        channel.shutdown().awaitTermination(5, TimeUnit.SECONDS)
        scanner.close()
    }
}

fun main() {
    val port = 50051
    val channel = ManagedChannelBuilder.forAddress("localhost", port).usePlaintext().build()

    val client = ChatClient(channel)

    try {
        client.joinChat()
    } finally {
        client.close()
    }
}