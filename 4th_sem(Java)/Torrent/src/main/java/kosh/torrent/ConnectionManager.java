package kosh.torrent;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.*;

public class ConnectionManager implements Runnable {
    public ConnectionManager(MetainfoFile meta, DownloadUploadManager DU, List<InetSocketAddress> peers, boolean leecher) {
        this.meta = meta;
        this.DU = DU;
        this.leecher = leecher;
        try {
            selector = Selector.open();
            ServerSocketChannel server = ServerSocketChannel.open();
            server.bind(peers.get(0));
            server.configureBlocking(false);
            server.register(selector, SelectionKey.OP_ACCEPT);
        }
        catch (IOException e) {
            System.err.println("can't init connection manager");
            e.printStackTrace();
            return;
        }

        if (leecher) {
            List<SocketChannel> channels = connectToPeers(peers.subList(1, peers.size()));
            sendHS(channels, meta.getInfoHash());
        }
        int numPieces = meta.getPieces().length / 20;
        iam.initPiecesHas(numPieces, !leecher);
        iam.initBlocks(meta.getPieceLen(), meta.getFileLen());
        System.out.println("initialized connection manager");
    }

    private void sendHS(List<SocketChannel> channels, byte[] infoHash) {
        for (SocketChannel channel : channels) {
            Peer peer = new Peer(channel);
            connections.add(peer);
            Message msg = new Handshake(infoHash, peer.getId());
            Queue<Message> messages = new LinkedList<>();
            messages.add(msg);
            messagesToPeer.put(peer, messages);
        }
    }


    private List<SocketChannel> connectToPeers(List<InetSocketAddress> peers) {
        List<SocketChannel> channels = new ArrayList<>();
        try {
            for (InetSocketAddress address : peers) {
                SocketChannel channel = SocketChannel.open(address);
                channel.configureBlocking(false);
                channel.register(selector, SelectionKey.OP_READ | SelectionKey.OP_WRITE);
                channels.add(channel);
                System.out.println("Connected to " + address);
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
        return channels;
    }

    @Override
    public void run() {
        System.out.println("connection manager is running");
        int i = 0;
        while (true) {
            int ready;
            try {
                ready = selector.selectNow();
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
            for (SelectionKey key : keys) {
                if (! key.isValid()) {
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
            keys.clear();

            if (i == 400) {
                try {
                    Thread.sleep(1000000000);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
                if (connections.isEmpty()) {
                    System.out.println("Connections list is empty");
                    return;
                }
            }

            while (!DU.getSuccessfulCheck().isEmpty()) {
                Integer idxHave = DU.getSuccessfulCheck().poll();
                assert idxHave != null;
                for (Peer peer : connections) {
                    messagesToPeer.get(peer).add(new ProtocolMessage(MessagesTypes.HAVE, Util.convertToByteArr(idxHave)));
                }
            }
            while (!DU.getUnsuccessfulCheck().isEmpty()) {
                Integer idxToClear = DU.getUnsuccessfulCheck().poll();
                assert idxToClear != null;
                clearPiece(idxToClear);
            }
//            System.out.println("---------------------------------");
            ++i;
        }
    }

    private void clearPiece(Integer idxToClear) {
        System.out.println("enter clear piece");
        iam.getPiecesHas().set(idxToClear, false);
        int blocksNumber = iam.getHasMap().get(idxToClear).size();
        iam.getHasMap().get(idxToClear).set(0, blocksNumber, false);
        int fromIdx = idxToClear * (int) meta.getPieceLen() / Constants.BLOCK_SIZE;
        int toIdx = idxToClear * (int) meta.getPieceLen() / Constants.BLOCK_SIZE + blocksNumber;
        iam.getRequestedBlocks().set(fromIdx, toIdx, false);
    }

    private Peer findPeer(SocketChannel remoteChannel) {
        for (Peer peer : connections) {
            if (peer.getChannel().equals(remoteChannel)) {
                return peer;
            }
        }
        return null; //never
    }

    private void accept(SelectionKey key) {
        System.out.println("ENTER ACCEPT");
        try {
            ServerSocketChannel serverSocketChannel = (ServerSocketChannel) key.channel();
            SocketChannel channel = serverSocketChannel.accept();
            channel.configureBlocking(false);
            Peer peer = new Peer(channel);
            connections.add(peer);
            Handshake myHS = new Handshake(meta.getInfoHash(), iam.getId());
            messagesToPeer.put(peer, new LinkedList<>());
            messagesToPeer.get(peer).add(myHS);
            channel.register(selector, SelectionKey.OP_READ | SelectionKey.OP_WRITE);
        }
        catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void readFromPeer(SelectionKey key) {
        System.out.println("READ FROM PEER");
        SocketChannel channel = (SocketChannel) key.channel();
        Peer peer = findPeer(channel);
        assert peer != null;
        if (!iam.getHandshaked().contains(peer)) {
            peer.getReadyMessages().add(peer.getRemoteHS());
            System.out.println("add HS");
        }
        peer.constructPeerMsg();
//            try {
//                //или не совсем закрыто??
////                System.out.println("Connection closed by " + peer);
////                channel.close();
//                //мб как-то ещё обработать, закинуть его в неактивные коннекты, чтобы потом рестартнуть если что
//            } catch (IOException e) {
//                e.printStackTrace();
//            }
//            return;
        System.out.println("read peer messages size: " + peer.getReadyMessages().size());
        while (!peer.getReadyMessages().isEmpty()) {
            Message msg = peer.getReadyMessages().poll();
            System.out.println("going to handle msg type: " + msg.getType());
            handleMsg(msg, peer);
        }
    }

    //тут отправить сообщение из очереди
    //ищу пира, беру сообщение из очереди для него, отправляю
    //подумать, через кого отправлять
    private void sendToPeer(SelectionKey key) {
//        System.out.println("SEND TO PEER");
        SocketChannel channel = (SocketChannel) key.channel();
        Peer peer = findPeer(channel);
        assert peer != null;
//        System.out.println("messages size: " + messages.size() + " , 219 cm");
        if (DU.getOutgoingMsg().containsKey(peer)) {
            synchronized (DU.getOutgoingMsg().get(peer)) {
                messagesToPeer.get(peer).addAll(DU.getOutgoingMsg().get(peer));
                DU.getOutgoingMsg().get(peer).clear();
            }
        }

        while (!messagesToPeer.get(peer).isEmpty()) {
            Message msgToSend = messagesToPeer.get(peer).poll();
            assert msgToSend != null;
            peer.sendMsg(msgToSend);
            System.out.println("Wrote to " + peer + ", type of msg(int): " + msgToSend.getType());
        }
//        System.out.println("No more messages to " + peer);
    }

    //возомжно, нормал инт и дурацкая конвертация в байтовый массив -- бред, и следует просто буффером класть туда инт
    private void handleMsg(Message msg, Peer peer) {
        switch (msg.getType()) {
            case MessagesTypes.HANDSHAKE -> {
                System.out.println("Got HANDSHAKE");
                //спецом id пировский, т.к. трекера нет
                if (!Arrays.equals(msg.getMessage(), new Handshake(meta.getInfoHash(), peer.getId()).getMessage())) {
                    System.out.println("HS are different!, interrupted");
                    Thread.currentThread().interrupt();
                    return;
                }
                iam.getHandshaked().add(peer);
                if (leecher) {
                    messagesToPeer.get(peer).add(new ProtocolMessage(MessagesTypes.INTERESTED));
                    System.out.println("add INTERESTED");
                }
            }
            case MessagesTypes.CHOKE -> {
                System.out.println("got CHOKE");
                peer.setChoking(true);
            }
            case MessagesTypes.UNCHOKE -> {
                System.out.println("got UNCHOKE");
                peer.setChoking(false);
                //мб ещё припилить метод, который будет говорить, заинтересован ли я в пире, а то мб у него нет ничего
            }
            case MessagesTypes.INTERESTED -> {
                System.out.println("got INTERESTED msg");
                peer.setInteresting(true);
                messagesToPeer.get(peer).add(new ProtocolMessage(MessagesTypes.UNCHOKE));
                System.out.println("add UNCHOKE");
                peer.setChoked(false);
                messagesToPeer.get(peer).add(new ProtocolMessage(MessagesTypes.BITFIELD, iam.getPiecesHas().toByteArray()));
                System.out.println("add BITFIELD");
            }
            case MessagesTypes.NOT_INTERESTED -> peer.setInteresting(false);
            case MessagesTypes.HAVE -> {
                System.out.println("got HAVE msg, set peer's piece to true");
                peer.setPiece(Util.convertToInt(msg.getPayload()), true);
            }
            case MessagesTypes.BITFIELD -> {
                System.out.println("got BITFIELD");
                if (msg.getMessage().length > 5) {
                    peer.setPiecesHas(msg.getPayload());
                } else {
                    System.out.println("bitfield payload is 0, init peer's pieces to false");
                    int piecesNum = (int) Math.ceilDiv(meta.getFileLen(),  meta.getPieceLen());
                    peer.initPiecesHas(piecesNum, false);
                }
                peer.initBlocks(meta.getPieceLen(), meta.getFileLen());
                System.out.println("iam cardinality in bitfield handle: " + iam.getPiecesHas().cardinality());
                if (iam.getPiecesHas().cardinality() != meta.getPieces().length / 20) {
                    Message request = createRequest(peer, iam);
                    if (request == null) {
                        System.out.println("have no blocks to request");
                        return;
                    }
                    messagesToPeer.get(peer).add(request);
                    System.out.println("sent REQUEST");
                }
            }
            //means remote peer is requesting a piece
            case MessagesTypes.REQUEST -> {
                //A block is uploaded by a client when the client is not choking a peer,
                // and that peer is interested in the client
                if (peer.isChoked() || !peer.isInteresting()) {
                    return;
                }
                byte[] payload = msg.getPayload();
                assert payload.length == 12;
                int idx = Util.convertToInt(Arrays.copyOfRange(payload, 0, 4));
                int begin = Util.convertToInt(Arrays.copyOfRange(payload, 4, 8));
                int len = Util.convertToInt(Arrays.copyOfRange(payload, 8, payload.length));
                System.out.println("REQUEST idx: " + idx + ", begin: " + begin + ", len: " + len);
                //добавить в очерень тасок DU
                DU.addTask(new Task(TaskType.SEND, idx, begin, len, peer));
                System.out.println("got REQUEST, gave DU task SEND, DU add PIECE");
            }
            //means remote peer send us a block of data
            case MessagesTypes.PIECE -> {
                //A block is downloaded by the client when the client is interested in a peer,
                // and that peer is not choking the client
                if (!peer.isInterested() || !peer.isChoking()) {
                    return;
                }
                byte[] payload = msg.getPayload();
                int idx = Util.convertToInt(Arrays.copyOfRange(payload, 0, 4));
                int begin = Util.convertToInt(Arrays.copyOfRange(payload, 4, 8));
                byte[] blockData = Arrays.copyOfRange(payload, 8, payload.length);
                DU.addTask(new Task(TaskType.SAVE, idx, begin, blockData));
                int blockIdx = begin / Constants.BLOCK_SIZE;
                iam.getHasMap().get(idx).set(blockIdx);
                Message request = createRequest(peer, iam);
                if (request == null) {
                    System.out.println("have no blocks to request");
                    return;
                }
                messagesToPeer.get(peer).add(request);
                BitSet bs = iam.getHasMap().get(idx);
                if (bs.cardinality() == bs.size()) {
                    int pieceLen = Constants.BLOCK_SIZE * (bs.size() - 1) + peer.getLastBlockSize();
                    DU.addTask(new Task(TaskType.CHECK_HASH, idx, pieceLen));
                }
                System.out.println("got PIECE, send request, gave DU task to save and(optional) check hash");
            }
            //means remote peer want us to cancel last request from him
            case MessagesTypes.CANCEL -> {
                //todo
                //как отменять? где очередь сообщений должна быть?
                //нужен интерфейс, чтобы ещё и have всем отправлять
            }
        }
    }

    //мб в пира засунуть или в mm
    private int getBlockIdxToRequest(Peer to, Peer from) {
        System.out.println("!!!cardinality of seeder's pieces has in leecher: " + to.getPiecesHas().cardinality());
        BitSet piecesToRequest = (BitSet) from.getPiecesHas().clone();
        piecesToRequest.flip(0, piecesToRequest.size());
        piecesToRequest.and(to.getPiecesHas());
        //в piecesToRequest те куски, которых нет у меня, есть у пира
        if (piecesToRequest.cardinality() == 0) {
            return - 1;
        }
        Random random = new Random();
        int pieceIdx = piecesToRequest.nextSetBit(random.nextInt(piecesToRequest.size()));
        if (pieceIdx == -1) {
            pieceIdx = piecesToRequest.nextSetBit(0);
        }

        BitSet blocksToRequest = (BitSet) from.getHasMap().get(pieceIdx).clone(); //кокие у меня есть
        blocksToRequest.or(from.getRequestedBlocks()); // какие есть у меня и запрошенные
        blocksToRequest.flip(0, blocksToRequest.size()); //каких у меня нет и не запрошенные
        blocksToRequest.and(to.getHasMap().get(pieceIdx)); //нет у меня, не запрошенные и есть у пира
        if (blocksToRequest.cardinality() == 0) {
            return -1;
        }

        int blockIdx = blocksToRequest.nextSetBit(random.nextInt(blocksToRequest.size()));
        if (blockIdx == -1) {
            blockIdx = blocksToRequest.nextSetBit(0);
        }
        return blockIdx;
    }
    private Message createRequest(Peer to, Peer from) {
        int blockToRequest = getBlockIdxToRequest(to, from);
        System.out.println("requested block idx: " + blockToRequest);
        if (blockToRequest == -1) {
            return null;
        }
        from.getRequestedBlocks().set(blockToRequest);
        int begin = Constants.BLOCK_SIZE * blockToRequest;
        int len = (blockToRequest - 1 == meta.getPieceLen() / Constants.BLOCK_SIZE) ? iam.getLastBlockSize() : Constants.BLOCK_SIZE;
        return new ProtocolMessage(MessagesTypes.REQUEST, Util.concatByteArrays(Util.concatByteArrays(Util.convertToByteArr(blockToRequest), Util.convertToByteArr(begin)), Util.convertToByteArr(len)));
    }


    private final Map<Peer, Queue<Message>> messagesToPeer = new HashMap<>();
    private final List<Peer> connections = new ArrayList<>();
    private final Peer iam = new Peer(null);
    private Selector selector;
    //мб куда-нибудь перенести
    private final MetainfoFile meta;
    private final DownloadUploadManager DU;
    private boolean leecher = false;
}
