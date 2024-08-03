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

    fun joinChat(userName: String) = runBlocking {
        val messageChannel = Channel<ChatMessage>()

        val responseObserver = object : StreamObserver<ChatMessage> {
            override fun onNext(message: ChatMessage) {
                println("\n${message.userName}: ${message.message}")
                print("> ")
            }

            override fun onError(t: Throwable) {
                println("\nError: ${t.message}")
                print("> ")
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

        launch {
            println("You've joined the chat. Type your messages:")
            while (true) {
                print("> ")
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

        joinAll()
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

    print("Enter your username: ")
    val scanner = Scanner(System.`in`)
    val userName = scanner.nextLine().takeIf { it.isNotBlank() } ?: "Anonymous"

    try {
        client.joinChat(userName)
    } finally {
        client.close()
    }
}