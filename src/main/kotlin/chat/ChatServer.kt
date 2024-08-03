package org.example.chat

import io.grpc.Server
import io.grpc.ServerBuilder
import io.grpc.stub.StreamObserver
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import java.util.concurrent.Executors

class ChatServer(private val port: Int) {
    private val server: Server = ServerBuilder
        .forPort(port)
        .addService(ChatService())
        .build()

    fun start() {
        server.start()
        println("Server started, listening on $port")
        Runtime.getRuntime().addShutdownHook(
            Thread {
                println("*** shutting down gRPC server since JVM is shutting down")
                this@ChatServer.stop()
                println("*** server shut down")
            }
        )
    }

    private fun stop() {
        server.shutdown()
    }

    fun blockUntilShutdown() {
        server.awaitTermination()
    }

    private class ChatService : ChatServiceGrpc.ChatServiceImplBase() {
        private val dispatcher = Executors.newFixedThreadPool(10).asCoroutineDispatcher()
        private val messageFlow = MutableSharedFlow<ChatMessage>()

        override fun joinChat(responseObserver: StreamObserver<ChatMessage>): StreamObserver<ChatMessage> {
            println("New client connected")

            val clientScope = CoroutineScope(dispatcher + SupervisorJob())

            val requestObserver = object : StreamObserver<ChatMessage> {
                override fun onNext(message: ChatMessage) {
                    clientScope.launch {
                        messageFlow.emit(message)
                    }
                    println("Received: ${message.userName}: ${message.message}")
                }

                override fun onError(t: Throwable) {
                    println("Error: ${t.message}")
                    clientScope.cancel()
                }

                override fun onCompleted() {
                    println("Client disconnected")
                    responseObserver.onCompleted()
                    clientScope.cancel()
                }
            }

            clientScope.launch {
                messageFlow.collect { message ->
                    responseObserver.onNext(message)
                }
            }

            return requestObserver
        }
    }
}

fun main() {
    val port = 50051
    val server = ChatServer(port)
    server.start()
    server.blockUntilShutdown()
}