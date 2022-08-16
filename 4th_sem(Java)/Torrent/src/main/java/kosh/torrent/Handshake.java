package kosh.torrent;

//будто все сообщения по протоколу, а вообще в идеале убрать константы и передавать их в конструктор
public class Handshake extends Message {
    public Handshake(byte[] infoHash, byte[] peerId) {
        super(MessagesTypes.HANDSHAKE);
        this.infoHash = infoHash;
        this.peerId = peerId; //who is sending the message
    }

    public byte[] getMessage() {
        return Util.concatByteArrays(pLength, Util.concatByteArrays(protocol, Util.concatByteArrays(reserved, Util.concatByteArrays(infoHash, peerId))));
    }

    private final byte[] protocol = "BitTorrent protocol".getBytes();
    private final byte[] pLength = {19};
    private final byte[] reserved = {0, 0, 0, 0, 0, 0, 0, 0};
    private final byte[] infoHash;
    private final byte[] peerId;
}
