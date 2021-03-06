package server;

import auth.UserManager;
import common.collection.HumanManager;
import commands.ServerCommandManager;
import common.auth.User;
import common.commands.CommandType;
import common.connection.*;
import common.data.HumanBeing;
import common.exceptions.*;
import database.DBManager;
import database.HumanDBManager;
import database.UserDBManager;
import exceptions.ServerOnlyCommandException;
import log.Log;

import java.io.*;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.util.*;
import java.util.concurrent.*;

/**
 * Класс сервера.
 */

public class Server extends Thread implements SenderReceiver {
    public final int MAX_CLIENTS = 10;

    private HumanManager collectionManager;
    private ServerCommandManager commandManager;
    private DBManager databaseHandler;
    private UserManager userManager;

    private int port;
    private DatagramChannel channel;

    private ExecutorService receiverThreadPool;
    private ExecutorService senderThreadPool;
    private ExecutorService requestHandlerThreadPool;

    private Queue<Map.Entry<InetSocketAddress, Request>> requestQueue;
    private Queue<Map.Entry<InetSocketAddress, Response>> responseQueue;
    private Set<InetSocketAddress> activeClients;
    private volatile boolean running;

    private Selector selector;

    private User hostUser;

    /**
     * Инициализация
     * @param p
     * @param properties
     * @throws ConnectionException
     * @throws DatabaseException
     */

    private void init(int p, Properties properties) throws ConnectionException, DatabaseException {
        running = true;
        port = p;
        setDaemon(true);
        hostUser = null;
        receiverThreadPool = Executors.newFixedThreadPool(MAX_CLIENTS);
        senderThreadPool = Executors.newFixedThreadPool(MAX_CLIENTS);
        requestHandlerThreadPool = Executors.newCachedThreadPool();

        requestQueue = new ConcurrentLinkedQueue<>();
        responseQueue = new ConcurrentLinkedQueue<>();
        activeClients = ConcurrentHashMap.newKeySet();

        databaseHandler = new DBManager(properties.getProperty("url"), properties.getProperty("user"), properties.getProperty("password"));
        userManager = new UserDBManager(databaseHandler);
        collectionManager = new HumanDBManager(databaseHandler, userManager);
        commandManager = new ServerCommandManager(this);


        try {
            collectionManager.deserializeCollection("");
        } catch (CollectionException e) {
            Log.logger.error(e.getMessage());
        }
        host(port);
        setName("Серверный поток");
        Log.logger.trace("Сервер запущен!");
    }

    /**
     * Размещение
     * @param p
     * @throws ConnectionException
     */

    private void host(int p) throws ConnectionException {
        try {
            if (channel != null && channel.isOpen()) channel.close();
            port = p;
            channel = DatagramChannel.open();
            channel.configureBlocking(false);
            channel.bind(new InetSocketAddress(port));
            selector = Selector.open();
            channel.register(selector, SelectionKey.OP_WRITE | SelectionKey.OP_READ);
        } catch (AlreadyBoundException e) {
            throw new PortAlreadyInUseException();
        } catch (IllegalArgumentException e) {
            throw new InvalidPortException();
        } catch (IOException e) {
            throw new ConnectionException("что-то пошло не так во время инициализации сервера");
        }
    }


    public Server(int p, Properties properties) throws ConnectionException, DatabaseException {
        init(p, properties);
    }

    /**
     * Получение запроса от клиента.
     * @throws ConnectionException
     * @throws InvalidDataException
     */

    public void receive() throws ConnectionException, InvalidDataException {
        ByteBuffer buf = ByteBuffer.allocate(BUFFER_SIZE);
        InetSocketAddress clientAddress = null;
        Request request = null;
        try {
            clientAddress = (InetSocketAddress) channel.receive(buf);
            if (clientAddress == null) return;
            Log.logger.trace("получен запрос от " + clientAddress);
        } catch (ClosedChannelException e) {
            throw new ClosedConnectionException();
        } catch (IOException e) {
            throw new ConnectionException("что-то пошло не так во время получения запроса");
        }
        try {
            ObjectInputStream objectInputStream = new ObjectInputStream(new ByteArrayInputStream(buf.array()));
            request = (Request) objectInputStream.readObject();
        } catch (ClassNotFoundException | ClassCastException | IOException e) {
            throw new InvalidReceivedDataException();
        }
        if (request != null && request.getBroadcastAddress() != null) {
            activeClients.add(request.getBroadcastAddress());
            Log.logger.trace("добавлен широковещательный адрес " + request.getBroadcastAddress().toString());
        }
        requestQueue.offer(new AbstractMap.SimpleEntry<>(clientAddress, request));

    }

    private void broadcast(Response response, InetSocketAddress currentAddress) {
        Log.logger.trace("изменения в вещании");
        for (InetSocketAddress client : activeClients) {
            if (!currentAddress.equals(client)) responseQueue.offer(new AbstractMap.SimpleEntry<>(client, response));
        }
    }

    public void broadcast(Response response) {
        Log.logger.trace("изменения в вещании");
        for (InetSocketAddress client : activeClients) {
            responseQueue.offer(new AbstractMap.SimpleEntry<>(client, response));
        }
    }

    /**
     * Отправляет ответ
     * @param clientAddress
     * @param response
     * @throws ConnectionException
     */

    public void send(InetSocketAddress clientAddress, Response response) throws ConnectionException {
        if (clientAddress == null) throw new InvalidAddressException("адрес клиента не найден");
        try {

            ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream(BUFFER_SIZE);
            ObjectOutputStream objectOutputStream = new ObjectOutputStream(byteArrayOutputStream);
            objectOutputStream.writeObject(response);
            channel.send(ByteBuffer.wrap(byteArrayOutputStream.toByteArray()), clientAddress);
            Log.logger.trace("отправил ответ на " + clientAddress);
        } catch (IOException e) {
            throw new ConnectionException("что-то пошло не так во время отправки ответа");
        }
    }

    /**
     * Запрос
     * @param address
     * @param request
     */

    private void handleRequest(InetSocketAddress address, Request request) {
        AnswerMsg answerMsg = new AnswerMsg();
        try {

            InetSocketAddress client = request.getBroadcastAddress();
            if (request.getStatus() == Request.Status.EXIT) {
                activeClients.remove(client);
                Log.logger.info("клиент " + address.toString() + " пошёл пить пиво");
                return;
            }
            if (request.getStatus() == Request.Status.HELLO) {
                answerMsg = new AnswerMsg().setStatus(Response.Status.COLLECTION).setCollectionOperation(CollectionOperation.ADD).setCollection(collectionManager.getCollection());
                activeClients.add(client);
                responseQueue.offer(new AbstractMap.SimpleEntry<>(address, answerMsg));
                return;
            }
            if (request.getStatus() == Request.Status.CONNECTION_TEST) {
                answerMsg.setStatus(Response.Status.FINE);
                responseQueue.offer(new AbstractMap.SimpleEntry<>(address, answerMsg));
                return;
            }
            HumanBeing human = request.getHuman();

            if (human != null) {
                human.setCreationDate(new Date());
            }

            request.setStatus(Request.Status.RECEIVED_BY_SERVER);

            if (commandManager.getCommand(request).getType() == CommandType.SERVER_ONLY) {
                throw new ServerOnlyCommandException();
            }
            answerMsg = (AnswerMsg) commandManager.runCommand(request);


            if (answerMsg.getStatus() == Response.Status.EXIT) {
                close();
            }
        } catch (CommandException e) {
            answerMsg.error(e.getMessage());
            Log.logger.error(e.getMessage());
        }

        if (answerMsg.getCollectionOperation() != CollectionOperation.NONE && answerMsg.getStatus() == Response.Status.FINE) {
            answerMsg.setStatus(Response.Status.BROADCAST);
            broadcast(answerMsg, request.getBroadcastAddress());
        }
        responseQueue.offer(new AbstractMap.SimpleEntry<>(address, answerMsg));

    }

    /**
     * Запуск сервера в многопоточке
     */

    public void run() {
        while (running) {
            try {
                selector.select();
            } catch (IOException e) {
                continue;
            }
            Iterator<SelectionKey> selectedKeys = selector.selectedKeys().iterator();
            while (selectedKeys.hasNext()) {
                SelectionKey key = selectedKeys.next();
                selectedKeys.remove();
                if (!key.isValid()) {
                    continue;
                }
                if (key.isReadable()) {
                    receiverThreadPool.submit(new Receiver());
                    continue;
                }
                if (key.isWritable() && responseQueue.size() > 0) {
                    senderThreadPool.submit(new Sender(responseQueue.poll()));
                }
            }
            if (requestQueue.size() > 0) {
                requestHandlerThreadPool.submit(new RequestHandler(requestQueue.poll()));
            }
        }
    }

    /**
     * Запуск сервера в консоль
     */

    public void consoleMode() {
        commandManager.consoleMode();
    }

    /**
     * Закрытие сервера
     */

    public void close() {
        try {
            broadcast(new AnswerMsg().setStatus(Response.Status.EXIT));

            while (responseQueue.size() > 0) {

                try {
                    Thread.sleep(1000);
                } catch (InterruptedException ignored) {

                }
            }
            running = false;
            receiverThreadPool.shutdown();
            requestHandlerThreadPool.shutdown();
            senderThreadPool.shutdown();
            databaseHandler.closeConnection();
            channel.close();
        } catch (IOException e) {
            Log.logger.error("не удается закрыть канал");
        }
    }

    public HumanManager getCollectionManager() {
        return collectionManager;
    }

    public UserManager getUserManager() {
        return userManager;
    }

    public User getHostUser() {
        return hostUser;
    }

    public void setHostUser(User usr) {
        hostUser = usr;
    }

    private class Receiver implements Runnable {
        public void run() {
            try {
                receive();
            } catch (ConnectionException | InvalidDataException e) {
                Log.logger.error(e.getMessage());
            }
        }
    }

    private class RequestHandler implements Runnable {
        private final Request request;
        private final InetSocketAddress address;

        public RequestHandler(Map.Entry<InetSocketAddress, Request> requestEntry) {
            request = requestEntry.getValue();
            address = requestEntry.getKey();
        }

        public void run() {
            handleRequest(address, request);
        }
    }

    private class Sender implements Runnable {
        private final Response response;
        private final InetSocketAddress address;

        public Sender(Map.Entry<InetSocketAddress, Response> responseEntry) {
            response = responseEntry.getValue();
            address = responseEntry.getKey();
        }

        public void run() {
            try {
                send(address, response);
            } catch (ConnectionException e) {
                Log.logger.error(e.getMessage());
            }
        }
    }

    public boolean isRunning() {
        return running;
    }
}