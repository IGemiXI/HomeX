package de.homex.sma;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;

/**
 * Minimaler Modbus-TCP-Client (nur Funktion 0x03 "Read Holding Registers") –
 * ohne externe Bibliothek, damit das Plugin eine einzelne kleine JAR bleibt.
 */
final class ModbusTcpClient implements AutoCloseable {

    /** Der Wechselrichter hat mit einem Modbus-Fehlercode geantwortet (z. B. 2 = Register gibt es nicht). */
    static final class ModbusException extends IOException {
        private static final long serialVersionUID = 1L;
        final int code;
        ModbusException(int code) {
            super("Modbus-Fehlercode " + code + (code == 2 ? " (Register nicht vorhanden)" : ""));
            this.code = code;
        }
    }

    private final String host;
    private final int port;
    private final int timeoutMs;
    private Socket socket;
    private DataInputStream in;
    private DataOutputStream out;
    private int transactionId = 0;

    ModbusTcpClient(String host, int port, int timeoutMs) {
        this.host = host;
        this.port = port;
        this.timeoutMs = timeoutMs;
    }

    private void verbinden() throws IOException {
        if (socket != null && socket.isConnected() && !socket.isClosed()) return;
        close();
        Socket s = new Socket();
        s.connect(new InetSocketAddress(host, port), timeoutMs);
        s.setSoTimeout(timeoutMs);
        s.setTcpNoDelay(true);
        socket = s;
        in = new DataInputStream(s.getInputStream());
        out = new DataOutputStream(s.getOutputStream());
    }

    /** Liest {@code count} 16-Bit-Register ab {@code address}. */
    synchronized int[] readHoldingRegisters(int unitId, int address, int count) throws IOException {
        verbinden();
        int tid = (transactionId = (transactionId + 1) & 0xFFFF);
        try {
            // MBAP-Header + PDU als EIN Paket senden (manche Geräte vertragen keine zerstückelten Frames)
            ByteBuffer frame = ByteBuffer.allocate(12)
                    .putShort((short) tid)       // Transaction ID
                    .putShort((short) 0)         // Protocol ID (immer 0)
                    .putShort((short) 6)         // Länge der restlichen Bytes
                    .put((byte) unitId)
                    .put((byte) 0x03)            // Funktion: Read Holding Registers
                    .putShort((short) address)
                    .putShort((short) count);
            out.write(frame.array());
            out.flush();

            int rTid = in.readUnsignedShort();
            in.readUnsignedShort();                 // Protocol
            int len = in.readUnsignedShort();
            in.readUnsignedByte();                  // Unit
            int function = in.readUnsignedByte();
            if (rTid != tid) {
                in.skipNBytes(len - 2);
                throw new IOException("Falsche Transaction-ID in der Antwort");
            }
            if ((function & 0x80) != 0) throw new ModbusException(in.readUnsignedByte());

            int byteCount = in.readUnsignedByte();
            if (byteCount != count * 2) throw new IOException("Unerwartete Antwortlänge " + byteCount);
            int[] regs = new int[count];
            for (int i = 0; i < count; i++) regs[i] = in.readUnsignedShort();
            return regs;
        } catch (ModbusException e) {
            throw e;                                // Verbindung ist ok, nur das Register nicht
        } catch (IOException e) {
            close();                                // beim nächsten Mal neu verbinden
            throw e;
        }
    }

    @Override
    public synchronized void close() {
        if (socket != null) {
            try { socket.close(); } catch (IOException ignored) {}
        }
        socket = null;
        in = null;
        out = null;
    }
}
