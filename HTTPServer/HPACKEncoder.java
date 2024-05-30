package HTTPServer;

import java.nio.ByteBuffer;
import java.util.*;

public class HPACKEncoder {

    private static final int DEFAULT_MAX_TABLE_SIZE = 4096;
    private int maxTableSize;
    private Map<String, LinkedList<String>> dynamicTable;
    private int dynamicTableSize;

    private static final String[][] STATIC_TABLE = {
        {":authority", ""},
        {":method", "GET"},
        {":method", "POST"},
        {":path", "/"},
        {":path", "/index.html"},
        {":scheme", "http"},
        {":scheme", "https"},
        {":status", "200"},
        {":status", "204"},
        {":status", "206"},
        {":status", "304"},
        {":status", "400"},
        {":status", "404"},
        {":status", "500"},
        {"accept-charset", ""},
        {"accept-encoding", "gzip, deflate"},
        {"accept-language", ""},
        {"accept-ranges", ""},
        {"accept", ""},
        {"access-control-allow-origin", ""},
        {"age", ""},
        {"allow", ""},
        {"authorization", ""},
        {"cache-control", ""},
        {"content-disposition", ""},
        {"content-encoding", ""},
        {"content-language", ""},
        {"content-length", ""},
        {"content-location", ""},
        {"content-range", ""},
        {"content-type", ""},
        {"cookie", ""},
        {"date", ""},
        {"etag", ""},
        {"expect", ""},
        {"expires", ""},
        {"from", ""},
        {"host", ""},
        {"if-match", ""},
        {"if-modified-since", ""},
        {"if-none-match", ""},
        {"if-range", ""},
        {"if-unmodified-since", ""},
        {"last-modified", ""},
        {"link", ""},
        {"location", ""},
        {"max-forwards", ""},
        {"proxy-authenticate", ""},
        {"proxy-authorization", ""},
        {"range", ""},
        {"referer", ""},
        {"refresh", ""},
        {"retry-after", ""},
        {"server", ""},
        {"set-cookie", ""},
        {"strict-transport-security", ""},
        {"transfer-encoding", ""},
        {"user-agent", ""},
        {"vary", ""},
        {"via", ""},
        {"www-authenticate", ""}
    };

    public HPACKEncoder() {
        this(DEFAULT_MAX_TABLE_SIZE);
    }

    public HPACKEncoder(int maxTableSize) {
        this.maxTableSize = maxTableSize;
        this.dynamicTable = new LinkedHashMap<>();
        this.dynamicTableSize = 0;
    }

    public byte[] encode(Map<String, String> headers) {
        // Calculate required size: sum of (name + value) lengths plus overhead
        // Each entry needs: 1 byte header + 2 bytes for length encoding (worst case) + name length + value length
        int requiredSize = 0;
        for (Map.Entry<String, String> header : headers.entrySet()) {
            requiredSize += 1 + 2 + header.getKey().length() + 2 + header.getValue().length();
        }
        int bufferSize = Math.max(8192, requiredSize * 2);  // Double to be safe
        ByteBuffer buffer = ByteBuffer.allocate(bufferSize);

        for (Map.Entry<String, String> header : headers.entrySet()) {
            String name = header.getKey();
            String value = header.getValue();

            if (!buffer.hasRemaining()) {
                // Buffer is full, need to expand
                ByteBuffer newBuffer = ByteBuffer.allocate(buffer.capacity() * 2);
                buffer.flip();
                newBuffer.put(buffer);
                buffer = newBuffer;
            }

            encodeHeader(buffer, name, value);
        }

        buffer.flip();
        byte[] result = new byte[buffer.remaining()];
        buffer.get(result);
        return result;
    }

    private void encodeHeader(ByteBuffer buffer, String name, String value) {
        // Check static table for exact match
        for (int i = 0; i < STATIC_TABLE.length; i++) {
            if (STATIC_TABLE[i][0].equals(name) && STATIC_TABLE[i][1].equals(value)) {
                encodeIndexed(buffer, i + 1);
                return;
            }
        }

        // Check dynamic table for exact match
        int dynamicIndex = findDynamicTableEntry(name, value);
        if (dynamicIndex >= 0) {
            encodeIndexed(buffer, STATIC_TABLE.length + dynamicIndex + 1);
            return;
        }

        // Check for name match in static table
        for (int i = 0; i < STATIC_TABLE.length; i++) {
            if (STATIC_TABLE[i][0].equals(name)) {
                encodeLiteralWithIncrementalIndexing(buffer, i + 1, value);
                addToDynamicTable(name, value);
                return;
            }
        }

        // Check for name match in dynamic table
        dynamicIndex = findDynamicTableName(name);
        if (dynamicIndex >= 0) {
            encodeLiteralWithIncrementalIndexing(buffer, STATIC_TABLE.length + dynamicIndex + 1, value);
            addToDynamicTable(name, value);
            return;
        }

        // No match - encode as literal with incremental indexing (new name)
        // Index 0 means literal new name
        buffer.put((byte) 0x40);
        encodeString(buffer, name);
        encodeString(buffer, value);
        addToDynamicTable(name, value);
    }

    private void encodeIndexed(ByteBuffer buffer, int index) {
        if (index < 127) {
            buffer.put((byte) (0x80 | index));
        } else {
            buffer.put((byte) 0xFF);
            encodeIntegerContinuation(buffer, index - 127, 7);
        }
    }

    private void encodeLiteralWithIncrementalIndexing(ByteBuffer buffer, int index, String value) {
        if (index < 63) {
            buffer.put((byte) (0x40 | index));
        } else {
            buffer.put((byte) 0x7F);
            encodeIntegerContinuation(buffer, index - 63, 6);
        }
        encodeString(buffer, value);
    }

    private void encodeLiteralWithoutIndexing(ByteBuffer buffer, String name, String value) {
        buffer.put((byte) 0x00);
        encodeString(buffer, name);
        encodeString(buffer, value);
    }

    private void encodeString(ByteBuffer buffer, String value) {
        byte[] bytes = value.getBytes();
        if (bytes.length < 128) {
            buffer.put((byte) (0x00 | bytes.length));
        } else {
            buffer.put((byte) 0x7F);
            encodeIntegerContinuation(buffer, bytes.length - 127, 7);
        }
        buffer.put(bytes);
    }

    public byte[] encodeString(String value) {
        byte[] bytes = value.getBytes();
        ByteBuffer buffer = ByteBuffer.allocate(bytes.length + 10);
        encodeString(buffer, value);
        buffer.flip();
        byte[] result = new byte[buffer.remaining()];
        buffer.get(result);
        return result;
    }

    private void encodeIntegerContinuation(ByteBuffer buffer, int value, int prefixBits) {
        // Encode the continuation bytes after the prefix byte
        // Each byte has high bit set to indicate continuation, except the last
        while (value >= 128) {
            buffer.put((byte) (0x80 | (value & 0x7F)));
            value >>= 7;
        }
        buffer.put((byte) (value & 0x7F));
    }

    public byte[] encodeInteger(int value, int prefixBits) {
        ByteBuffer buffer = ByteBuffer.allocate(10);
        int maxPrefix = (1 << prefixBits) - 1;
        if (value < maxPrefix) {
            buffer.put((byte) value);
        } else {
            buffer.put((byte) maxPrefix);
            value -= maxPrefix;
            encodeIntegerContinuation(buffer, value, prefixBits);
        }
        buffer.flip();
        byte[] result = new byte[buffer.remaining()];
        buffer.get(result);
        return result;
    }

    private int findDynamicTableEntry(String name, String value) {
        int globalIndex = 0;
        for (Map.Entry<String, LinkedList<String>> entry : dynamicTable.entrySet()) {
            LinkedList<String> values = entry.getValue();
            for (int i = 0; i < values.size(); i++) {
                if (entry.getKey().equals(name) && values.get(i).equals(value)) {
                    return globalIndex;
                }
                globalIndex++;
            }
        }
        return -1;
    }

    private int findDynamicTableName(String name) {
        int currentIndex = 0;
        for (Map.Entry<String, LinkedList<String>> entry : dynamicTable.entrySet()) {
            if (entry.getKey().equals(name)) {
                return currentIndex;
            }
            currentIndex += entry.getValue().size();
        }
        return -1;
    }

    private void addToDynamicTable(String name, String value) {
        int headerSize = name.length() + value.length() + 32;

        if (headerSize > maxTableSize) {
            return;
        }

        while (dynamicTableSize + headerSize > maxTableSize && !dynamicTable.isEmpty()) {
            evictOldestEntry();
        }

        dynamicTable.computeIfAbsent(name, k -> new LinkedList<>()).addFirst(value);
        dynamicTableSize += headerSize;
    }

    private void evictOldestEntry() {
        Iterator<Map.Entry<String, LinkedList<String>>> iterator = dynamicTable.entrySet().iterator();
        if (iterator.hasNext()) {
            Map.Entry<String, LinkedList<String>> entry = iterator.next();
            LinkedList<String> values = entry.getValue();
            if (!values.isEmpty()) {
                String removedValue = values.removeLast();
                int headerSize = entry.getKey().length() + removedValue.length() + 32;
                dynamicTableSize -= headerSize;
                if (values.isEmpty()) {
                    iterator.remove();
                }
            }
        }
    }

    public void setMaxTableSize(int newMaxSize) {
        this.maxTableSize = newMaxSize;
        while (dynamicTableSize > maxTableSize && !dynamicTable.isEmpty()) {
            evictOldestEntry();
        }
    }

    public int getMaxTableSize() {
        return maxTableSize;
    }

    public int getDynamicTableSize() {
        return dynamicTableSize;
    }
}
