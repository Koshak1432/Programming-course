package kosh.torrent;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.*;

//класс, отсылающий и принимающий блоки
public class DownloadUploadManager implements Runnable {
    public DownloadUploadManager(MetainfoFile meta) {
        this.meta = meta;
        initHashes(meta);
        try {
            output = new RandomAccessFile(meta.getName() + "test", "rw");

        } catch (FileNotFoundException e) {
            e.printStackTrace();
        }
    }

    private void initHashes(MetainfoFile meta) {
        byte[] pieces = meta.getPieces();
        assert pieces.length % 20 == 0;
        int piecesNum = pieces.length / 20;
        for (int i = 0; i < piecesNum; ++i) {
            byte[] hash = Util.subArray(pieces, i * 20, 20);
            hashes.put(i, hash);
        }
    }

    @Override
    public void run() {
        while (true) {
            if (Thread.currentThread().isInterrupted()) {
                try {
                    output.close();
                }
                catch (IOException e) {
                    System.err.println("Couldn't close file");
                    e.printStackTrace();
                }
                System.out.println("DU finished");
                return;
            }
            if (!tasks.isEmpty()) {
                doTask(tasks.poll());
            }
        }
    }

    public void doTask(Task task) {
        switch (task.getType()) {
            case SAVE -> saveBlock(task);
            case SEND -> sendBlock(task);
            case CHECK_HASH -> checkHash(task);
            case STOP -> stop();
        }
    }

    public Map<Peer, Queue<Message>> getOutgoingMsg() {
        return outgoingMsg;
    }

    public void addTask(Task task) {
        tasks.add(task);
    }

    private void saveBlock(Task task) {
        int idx = task.getBlock().getIdx();
        int begin = task.getBlock().getBegin();
        byte[] block = task.getBlock().getData();
        try {
            output.seek((long) Constants.PIECE_LENGTH * task.getBlock().getIdx() + task.getBlock().getBegin());
            output.write(block);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void sendBlock(Task task) {
        int idx = task.getBlock().getIdx();
        int begin = task.getBlock().getBegin();
        int len = task.getBlock().getLen();

        byte[] dataToSend = new byte[task.getBlock().getLen()];
        try {
            output.seek((long) Constants.PIECE_LENGTH * task.getBlock().getIdx() + task.getBlock().getBegin());
            int read = output.read(dataToSend);
            if (task.getBlock().getLen() != read) {
                System.err.println("Count of read bytes and requested len are different");
                return;
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
        byte[] idxA = Util.convertToByteArr(idx);
        byte[] beginA = Util.convertToByteArr(begin);
        Message msgToSend = new ProtocolMessage(MessagesTypes.PIECE,
                                                Util.concatByteArrays(idxA, Util.concatByteArrays(beginA, dataToSend)));
        Queue<Message> q;
        synchronized (outgoingMsg) {
            if (outgoingMsg.containsKey(task.getWho())) {
                q = outgoingMsg.get(task.getWho());
            } else {
                q = new LinkedList<>();
            }
            outgoingMsg.put(task.getWho(), q);
            q.add(msgToSend);
        }
    }

    private void stop() {
        System.out.println("Stopped DU thread");
        Thread.currentThread().interrupt();
    }

    private void checkHash(Task task) {
        int idx = task.getIdx();
        int pieceLen = task.getPieceLen();
        byte[] metaHash = hashes.get(idx);
        byte[] pieceData = new byte[pieceLen];
        try {
            output.seek(meta.getPieceLen() * idx);
            if (pieceLen != output.read(pieceData)) {
                System.err.println("Couldn't read enough bytes while checking hashes");
                return;
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
        if (Arrays.equals(metaHash, Util.generateHash(pieceData))) {
            successfulCheck.add(idx);
        } else {
            unsuccessfulCheck.add(idx);
        }
    }

    public Queue<Integer> getSuccessfulCheck() {
        return successfulCheck;
    }

    public Queue<Integer> getUnsuccessfulCheck() {
        return unsuccessfulCheck;
    }

    private final MetainfoFile meta;
    private final Queue<Task> tasks = new LinkedList<>();
    private final Map<Peer, Queue<Message>> outgoingMsg = new HashMap<>();
    private final Queue<Integer> successfulCheck = new LinkedList<>();
    private final Queue<Integer> unsuccessfulCheck = new LinkedList<>();
    private final Map<Integer, byte[]> hashes = new HashMap<>(); //key -- piece num, value -- hash from .torrent
    private RandomAccessFile output;
}
