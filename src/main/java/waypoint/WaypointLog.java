package waypoint;

import java.io.*;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.CRC32C;

/**
 * Append-only log over a simulated disk with process-crash semantics: a crash
 * loses the unsynced (buffered) tail; synced records survive. Power loss is
 * out of scope, since the page cache is not modelled. See README
 * "Limitations".
 *
 * Framing: [int len][int crc32c][payload]. On open, parsing stops at the first
 * bad record (torn tail), the durable region is truncated to the intact
 * prefix, and record indexes are rebased onto that prefix.
 */
public final class WaypointLog {

    private WaypointLog() {}

    /** Simulated disk: buffered tail + durable prefix. */
    public static final class MemoryDisk {
        private final List<byte[]> buffered = new ArrayList<>();
        private final List<byte[]> durable = new ArrayList<>();
        private long appends = 0, syncs = 0;

        public synchronized void write(byte[] framed) {
            buffered.add(framed);
            appends++;
        }

        public synchronized void sync() {
            durable.addAll(buffered);
            buffered.clear();
            syncs++;
        }

        /** Process-crash semantics: the unsynced tail is gone. */
        public synchronized void discardUnsynced() {
            buffered.clear();
        }

        /** Torn-tail repair: keep exactly the first n durable frames. */
        public synchronized void truncateDurableAt(int n) {
            if (n < 0 || n > durable.size()) {
                throw new IllegalArgumentException("truncateDurableAt(" + n + ") outside [0," + durable.size() + "]");
            }
            durable.subList(n, durable.size()).clear();
        }

        /** Records that would survive a crash right now (cloned). */
        public synchronized List<byte[]> durableRecords() {
            List<byte[]> out = new ArrayList<>(durable.size());
            for (byte[] b : durable) out.add(b.clone());
            return out;
        }

        /** Seed a fork child / test with an already-durable prefix. */
        public synchronized void seedDurable(List<byte[]> framedRecords) {
            for (byte[] b : framedRecords) durable.add(b.clone());
        }

        public synchronized long appends() { return appends; }
        public synchronized int durableCount() { return durable.size(); }
        public synchronized long syncs() { return syncs; }

        /** Self-test hook: flip the last byte of the last durable record. */
        public synchronized void corruptLastDurableForSelfTest() {
            byte[] b = durable.get(durable.size() - 1);
            b[b.length - 1] ^= 0x5A;
        }

        /** Self-test hook: flip the first byte of the first durable record. */
        public synchronized void corruptFirstDurableForSelfTest() {
            byte[] b = durable.get(0);
            b[b.length - 1] ^= 0x5A;
        }
    }

    /** Framed WAL. Record indexes are assigned per append; durability of a
     *  record index is defined as "a sync completed after it was written".
     *
     *  Index discipline: on open the durable region is parsed until the first
     *  bad frame; the bad suffix is truncated and {@code appended} is rebased
     *  to {@code live.size()} (the durable prefix length). A recovery instance
     *  therefore hands out exactly the indexes of the records it appends. */
    public static final class Wal {
        private final MemoryDisk disk;
        private final List<byte[]> live = new ArrayList<>(); // parsed durable records
        private long syncedRecords = 0; // durability frontier (record count)
        private long appended = 0;      // total record indexes handed out this open

        public Wal(MemoryDisk disk) {
            this.disk = disk;
            reload();
        }

        /** Parse durable region; stop at first bad record (torn tail) and
         *  truncate the bad suffix. */
        public final void reload() {
            live.clear();
            List<byte[]> raw = disk.durableRecords();
            for (int i = 0; i < raw.size(); i++) {
                try {
                    live.add(parseFramed(raw.get(i)));
                } catch (IOException bad) {
                    disk.truncateDurableAt(i); // torn tail: repair, keep intact prefix
                    break;
                }
            }
            syncedRecords = live.size();
            // Indexes are the durable prefix length, NOT disk.appends(): after a
            // crash the unsynced tail is gone, so durable index == live index.
            appended = live.size();
        }

        /** Append one payload; returns its record index. */
        public long append(byte[] payload) {
            disk.write(frame(payload));
            return appended++;
        }

        public void sync() {
            disk.sync();
            reload();
        }

        /** Durable, parsed records as of the last sync (view for recovery). */
        public List<byte[]> durableParsed() {
            return List.copyOf(live);
        }

        /** Durability frontier: number of records durable right now. */
        public long frontier() {
            return syncedRecords;
        }

        public boolean isDurable(long recordIndex) {
            return recordIndex >= 0 && recordIndex < syncedRecords;
        }

        public long appends() { return appended; }
    }

    static byte[] frame(byte[] payload) {
        CRC32C crc = new CRC32C();
        crc.update(payload, 0, payload.length);
        int c = (int) crc.getValue();
        ByteArrayOutputStream bos = new ByteArrayOutputStream(payload.length + 8);
        DataOutputStream out = new DataOutputStream(bos);
        try {
            out.writeInt(payload.length);
            out.writeInt(c);
            out.write(payload);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return bos.toByteArray();
    }

    static byte[] parseFramed(byte[] raw) throws IOException {
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(raw));
        int len = in.readInt();
        int c = in.readInt();
        if (len < 0 || len > 64 * 1024 * 1024) throw new IOException("bad len");
        byte[] payload = new byte[len];
        in.readFully(payload);
        CRC32C crc = new CRC32C();
        crc.update(payload, 0, payload.length);
        if ((int) crc.getValue() != c) throw new IOException("crc mismatch");
        return payload;
    }
}
