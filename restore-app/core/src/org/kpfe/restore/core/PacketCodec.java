package org.kpfe.restore.core;

/** Source-derived vendor framing; a built packet is not proof of MCU acceptance. */
public final class PacketCodec {
    public static final int PACKET_SIZE = 64;
    public static final int PAYLOAD_SIZE = 58;
    private PacketCodec() { }

    public static int packetCount(FirmwareImage image) {
        return (image.size() + PAYLOAD_SIZE - 1) / PAYLOAD_SIZE;
    }

    public static byte[] dataPacket(FirmwareImage image, int index) {
        if (index < 0 || index >= packetCount(image)) throw new IllegalArgumentException("Invalid packet index");
        byte[] packet = new byte[PACKET_SIZE];
        int offset = index * PAYLOAD_SIZE;
        int count = Math.min(PAYLOAD_SIZE, image.size() - offset);
        packet[2] = (byte) count;
        packet[3] = 2;
        packet[4] = (byte) index;
        packet[5] = (byte) (index >>> 8);
        int sum = 0;
        for (int i = 0; i < count; i++) {
            packet[i + 6] = image.valueAt(offset + i);
            sum += packet[i + 6] & 255;
        }
        packet[0] = (byte) sum;
        packet[1] = (byte) (sum >>> 8);
        return packet;
    }

    public static byte[] quitPacket() {
        byte[] packet = new byte[PACKET_SIZE];
        packet[3] = 3;
        return packet;
    }

    public static byte[] bootEntry() {
        return new byte[] {(byte) 0xe7, 0, 0, 0, 0, 0, 0, 0, 7, 7, (byte) 0xed};
    }
}
