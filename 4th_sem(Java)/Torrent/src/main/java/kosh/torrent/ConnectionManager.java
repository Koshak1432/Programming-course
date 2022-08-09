package kosh.torrent;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.*;

//server
public class ConnectionManager implements Runnable {
    public ConnectionManager(String hostname, int port, MetainfoFile meta, DownloadUploadManager DU) {
        this.meta = meta;
        this.DU = DU;
        InetSocketAddress address = new InetSocketAddress(hostname, port);
        try {
            selector = Selector.open();
            ServerSocketChannel server = ServerSocketChannel.open();
            server.bind(address);
            server.configureBlocking(false);
            server.register(selector, SelectionKey.OP_ACCEPT);
        }
        catch (IOException e) {
            System.err.println("can't init connection manager");
            e.printStackTrace();
            return;
        }
        System.out.println("initialized connection manager");
    }

    //todo где-то имплементнуть реквест блока, будет после получения блока, но ещё первый блок надо запросить где-то
    @Override
    public void run() {
        System.out.println("connection manager is running");
        while (true) {
            int ready;
            try {
                ready = selector.select();
            }
            catch (IOException e) {
                System.err.println("Can't select");
                e.printStackTrace();
                return;
            }
            if (ready == 0) {
                continue;
            }
            Set<SelectionKey> keys = selector.selectedKeys();
            Iterator<SelectionKey> iterator = keys.iterator();
            while (iterator.hasNext()) {
                SelectionKey key = iterator.next();
                iterator.remove();
                if (!key.isValid()) {
                    continue;
                }
                if (key.isAcceptable()) {
                    accept(key);
                }
                if (key.isReadable()) {
                    readFromPeer(key);
                }
                if (key.isWritable()) {
                    sendToPeer(key);
                }
            }
        }
    }

    private Peer findPeer(SocketChannel remoteChannel) {
        for (Peer peer : connections) {
            if (peer.getChannel().equals(remoteChannel)) {
                return peer;
            }
        }
        return null; //never
    }


    //принимаю подключение, завожу очередь для пира, куда сразу же кладу HS сервера для отправки этому пиру, тот уже смотрит его, и если расходятся, то отключается
    //либо тут сверяю HS(через MessagesManager), если расходятся, то сервак отклоняет соединение
    //если всё ок, то кидаю в очередь свой HS для отправки в будущем и регистрирую канал для чтения и записи

    //оповещать messageManager-а о новом подключении, передавая сокет, чтобы тот уже хранил мапу очередей
    //когда отправить захочу, то смотрю на очередь соответствующего сокета и отсылаю сообщение
    //или
    //в этом классе будет мапа сокет-очередь строк с названиями команд    бред
    //пусть тут будет мапа очередей, засовывать туда буду то, что вернёт команда из messageManager

    //todo мб вообще не принимать тут, а изначально подключиться к пирам, список которых в аргументах прилетает
    //принимаю подключение, чекаю HS, добавляю подключение в лист,
    // добавляю сообщения: свой HS и Bitfield в очередь для пира
    //регистрирую канал для записи и чтения
    private void accept(SelectionKey key) {
        try {
            ServerSocketChannel serverSocketChannel = (ServerSocketChannel) key.channel();
            SocketChannel channel = serverSocketChannel.accept();
            channel.configureBlocking(false);
            Peer peer = new Peer(channel);
            Message myHS = new Handshake(meta.getInfoHash(), peer.getId());
            if (!peer.checkHS(myHS)) {
                peer.closeConnection();
                System.out.println("info hashes are different, closed connection with " + peer);
            }
            System.out.println("Connect from " + peer);
            peer.setChoked(false);
            connections.add(peer);
            messagesToPeer.put(peer, new LinkedList<>());
            messagesToPeer.get(peer).add(myHS);
            messagesToPeer.get(peer).add(new ProtocolMessage(MessagesTypes.UNCHOKE));
            channel.register(selector, SelectionKey.OP_READ); //to read from remote socket
            channel.register(selector, SelectionKey.OP_WRITE); //to write to remote socket
        }
        catch (IOException e) {
            e.printStackTrace();
        }
    }


    //прочитать инфу от пира, вернуть сообщение с этой инфой, тут в свиче посмотреть на тип сообщения, если что-то отметить надо, то отметить тут
    //если реквест, пис или кансел, то кинуть таску в очередь DU
    //можно будет паттерн наблюдателя имплементнуть, DU подпишется на cm, а cm будет оповещать своих подписчиков, с методом, который
    //будет добавлять в очередь таску или что-то такое
    //как бродкастить have? кто-то должен смотреть на количество блоков скачанных и чекать хэши
    //можно завести мапу, где ключи -- индекс куска, а значения -- лист блоков | где?
    //где выбирать какие куски запрашивать?
    private void readFromPeer(SelectionKey key) {
        SocketChannel channel = (SocketChannel) key.channel();
        Peer peer = findPeer(channel);
        assert peer != null;
        Message peerMsg = peer.constructPeerMsg(peer.getChannel());
        if (peerMsg == null) {
            try {
                //или не совсем закрыто??
                System.out.println("Connection closed by " + peer);
                channel.close();
                //мб как-то ещё обработать, закинуть его в неактивные коннекты, чтобы потом рестартнуть если что
                return;
            } catch (IOException e) {
                e.printStackTrace();
            }
        }


        handleMsg(peerMsg, peer);

        if (messagesToPeer.containsKey(peer)) {
            messagesToPeer.get(peer).add(response);
        } else {
            Queue<Message> messages = new LinkedList<>();
            messages.add(response);
            messagesToPeer.put(peer, messages);
        }
    }

    //тут отправить сообщение из очереди
    //ищу пира, беру сообщение из очереди для него, отправляю
    //подумать, через кого отправлять
    private void sendToPeer(SelectionKey key) {
        SocketChannel channel = (SocketChannel) key.channel();
        Peer peer = findPeer(channel);
        assert peer != null;
        Queue<Message> messages = messagesToPeer.get(peer);
        synchronized (DU.getOutgoingMsg().get(peer)) {
            messages.addAll(DU.getOutgoingMsg().get(peer));
        }
        if (!messages.isEmpty()) {
            Message msgToSend = messages.poll();
            peer.sendMsg(msgToSend);
            System.out.println("Wrote to " + peer + ", type of msg(int): " + msgToSend.getType());
            return;
        }
        System.out.println("No more messages to " + peer);
    }

    //возомжно, нормал инт и дурацкая конвертация в байтовый массив -- бред, и следует просто буффером класть туда инт
    private void handleMsg(Message msg, Peer peer) {
        switch (msg.getType()) {
            case MessagesTypes.CHOKE -> peer.setChoked(true);
            case MessagesTypes.UNCHOKE -> {
                peer.setChoked(false);
                //мб ещё припилить метод, который будет говорить, заинтересован ли я в пире, а то мб у него нет ничего
                messagesToPeer.get(peer).add(new ProtocolMessage(MessagesTypes.INTERESTED));

            }
            case MessagesTypes.INTERESTED -> {
                peer.setInterested(true);
                messagesToPeer.get(peer).add(new ProtocolMessage(MessagesTypes.BITFIELD, iam.getPiecesHas().toByteArray()));
            }
            case MessagesTypes.NOT_INTERESTED -> peer.setInterested(false);
            case MessagesTypes.HAVE -> peer.setPiece(Util.convertToNormalInt(msg.getPayload()), true);
            case MessagesTypes.BITFIELD -> {
                peer.setPiecesHas(msg.getPayload());
                peer.initBlocks(meta.getPieceLen(), meta.getFileLen());
                requestPiece(peer, iam);
            }
            //means remote peer is requesting a piece
            case MessagesTypes.REQUEST -> {
                byte[] payload = msg.getPayload();
                assert payload.length == 12;
                int idx = Util.convertToNormalInt(Arrays.copyOfRange(payload, 0, 4));
                int begin = Util.convertToNormalInt(Arrays.copyOfRange(payload, 4, 8));
                int len = Util.convertToNormalInt(Arrays.copyOfRange(payload, 8, payload.length));
                //добавить в очерень тасок DU
                DU.addTask(new Task(TaskType.SEND, idx, begin, len, peer));
            }
            //means remote peer send us a block of data
            case MessagesTypes.PIECE -> {
                byte[] payload = msg.getPayload();
                int idx = Util.convertToNormalInt(Arrays.copyOfRange(payload, 0, 4));
                int begin = Util.convertToNormalInt(Arrays.copyOfRange(payload, 4, 8));
                byte[] blockData = Arrays.copyOfRange(payload, 8, payload.length);
                DU.addTask(new Task(TaskType.SAVE, idx, begin, blockData));
                int blockIdx = begin / Constants.BLOCK_SIZE;
                iam.getHasMap().get(idx).set(blockIdx, true);
                requestPiece(peer, iam);
                //если полный кусок, то чек хэшей //todo
            }
            //means remote peer want us to cancel last request from him
            case MessagesTypes.CANCEL -> {
                //todo
                //как отменять? где очередь сообщений должна быть?
                //нужен интерфейс, чтобы ещё и have всем отправлять
            }
        }
    }

    //мб в пира засунуть
    private void requestPiece(Peer to, Peer from) {

    }


    private final Map<Peer, Queue<Message>> messagesToPeer = new HashMap<>();
    private final List<Peer> connections = new ArrayList<>();
    private final Peer iam = new Peer(null);
    private Selector selector;
    //мб куда-нибудь перенести
    private final MetainfoFile meta;
    private final DownloadUploadManager DU;
}
