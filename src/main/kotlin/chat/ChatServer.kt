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
        Runtime.getRuntime().addShutdownHook(Thread { stop() })
    }

    private fun stop() {
        println("*** shutting down gRPC server since JVM is shutting down")
        server.shutdown()
        println("*** server shut down")
    }

    fun blockUntilShutdown() {
        server.awaitTermination()
    }
}

class ChatService : ChatServiceGrpc.ChatServiceImplBase() {
    private val dispatcher = Executors.newFixedThreadPool(10).asCoroutineDispatcher()
    private val rooms = mutableMapOf<String, RoomInfo>()
    private val scope = CoroutineScope(dispatcher + SupervisorJob())

    init {
        initializeDefaultRooms()
    }

    override fun joinChat(responseObserver: StreamObserver<ChatMessage>): StreamObserver<ChatMessage> {
        println("New client connected")
        return ChatSession(responseObserver).requestObserver
    }

    private fun initializeDefaultRooms() {
        listOf("gRPC", "kotlin", "golang").forEach { rooms[it] = RoomInfo() }
    }

    inner class ChatSession(private val responseObserver: StreamObserver<ChatMessage>) {
        private var userName = ""
        private var roomName = ""
        private val clientScope = CoroutineScope(dispatcher + SupervisorJob())

        val requestObserver = object : StreamObserver<ChatMessage> {
            override fun onNext(message: ChatMessage) = handleMessage(message)
            override fun onError(t: Throwable) = handleError(t)
            override fun onCompleted() = handleCompleted()
        }

        private fun handleMessage(message: ChatMessage) {
            when {
                userName.isEmpty() -> handleUserName(message)
                roomName.isEmpty() -> handleRoomName(message)
                else -> handleChatMessage(message)
            }
        }

        private fun handleUserName(message: ChatMessage) {
            userName = message.userName
            sendServerMessage(getWelcomeMessage())
        }

        private fun handleRoomName(message: ChatMessage) {
            roomName = message.message
            if (rooms.containsKey(roomName)) {
                joinRoom()
            } else {
                sendServerMessage(getRoomNotFoundMessage())
                roomName = ""
            }
        }

        private fun handleChatMessage(message: ChatMessage) {
            clientScope.launch {
                rooms[roomName]?.messageFlow?.emit(message)
            }
            println("[$roomName] ${message.userName}: ${message.message}")
        }

        // Joining a room means - connect to a particular room where you are chat with others.
        private fun joinRoom() {
            rooms[roomName]?.users?.add(userName)
            sendServerMessage("You've joined the room: $roomName")
            broadcastMessage(roomName, "Server", "$userName has joined the room.")
        }

        private fun handleError(t: Throwable) {
            println("Error: ${t.message}")
            cleanupClient()
        }

        private fun handleCompleted() {
            println("Client disconnected")
            cleanupClient()
            responseObserver.onCompleted()
        }

        private fun cleanupClient() {
            if (roomName.isNotEmpty() && userName.isNotEmpty()) {
                rooms[roomName]?.users?.remove(userName)
                broadcastMessage(roomName, "Server", "$userName has left the room.")
            }
            clientScope.cancel()
        }

        private fun sendServerMessage(message: String) {
            responseObserver.onNext(ChatMessage.newBuilder()
                .setUserName("Server")
                .setMessage(message)
                .build())
        }

        private fun getWelcomeMessage(): String {
            val roomList = rooms.keys.joinToString(", ")
            return "Welcome, $userName! Available rooms: $roomList\nPlease enter a room name to join:"
        }

        private fun getRoomNotFoundMessage(): String {
            val roomList = rooms.keys.joinToString(", ")
            return "Room not found. Available rooms: $roomList\nPlease try again:"
        }

        init {
            setupMessageCollection()
        }

        private fun setupMessageCollection() {
            clientScope.launch {
                for (room in rooms.values) {
                    launch {
                        room.messageFlow.collect { message ->
                            if (roomName == room.users.find { it == userName }?.let { rooms.entries.find { entry -> entry.value.users.contains(it) }?.key }) {
                                responseObserver.onNext(message)
                            }
                        }
                    }
                }
            }
        }
    }

    private fun broadcastMessage(roomName: String, userName: String, message: String) {
        scope.launch {
            rooms[roomName]?.messageFlow?.emit(ChatMessage.newBuilder()
                .setUserName(userName)
                .setMessage(message)
                .build())
        }
    }

    data class RoomInfo(
        val messageFlow: MutableSharedFlow<ChatMessage> = MutableSharedFlow(),
        val users: MutableSet<String> = mutableSetOf()
    )
}

fun main() {
    val port = 50051
    val server = ChatServer(port)
    server.start()
    server.blockUntilShutdown()
}