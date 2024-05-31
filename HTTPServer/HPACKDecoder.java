package HTTPServer;

import java.nio.ByteBuffer;
import java.util.*;

public class HPACKDecoder {

    private static final int DEFAULT_MAX_TABLE_SIZE = 4096;
    private static final int MAX_HEADER_LIST_SIZE = 8192; // 8KB
    private int maxTableSize;
    private Map<String, LinkedList<String>> dynamicTable;
    private int dynamicTableSize;
    private int currentHeaderListSize = 0;

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

    public HPACKDecoder() {
        this(DEFAULT_MAX_TABLE_SIZE);
    }

    public HPACKDecoder(int maxTableSize) {
        this.maxTableSize = maxTableSize;
        this.dynamicTable = new LinkedHashMap<>();
        this.dynamicTableSize = 0;
    }

    public Map<String, String> decode(byte[] data) {
        currentHeaderListSize = 0; // Reset for each decode
        ByteBuffer buffer = ByteBuffer.wrap(data);
        Map<String, String> headers = new LinkedHashMap<>();

        while (buffer.hasRemaining()) {
            byte b = buffer.get();

            if ((b & 0x80) == 0x80) {
                // Indexed header
                buffer.position(buffer.position() - 1);
                int index = decodeInteger(buffer, 7);
                String[] header = getTableEntry(index);
                if (header != null) {
                    headers.put(header[0], header[1]);
                }
            } else if ((b & 0x40) == 0x40) {
                // Literal with incremental indexing
                buffer.position(buffer.position() - 1);
                int nameIndex = decodeInteger(buffer, 6);
                String name;
                if (nameIndex == 0) {
                    // New name - decode as string
                    name = decodeString(buffer);
                } else {
                    name = getTableEntryName(nameIndex);
                }
                String value = decodeString(buffer);
                headers.put(name, value);
                addToDynamicTable(name, value);
            } else if ((b & 0x20) == 0x20) {
                // Dynamic table size update
                buffer.position(buffer.position() - 1);
                int newSize = decodeInteger(buffer, 5);
                setMaxTableSize(newSize);
            } else {
                // Literal without indexing or never indexed
                buffer.position(buffer.position() - 1);
                int nameIndex = decodeInteger(buffer, 4);
                String name;
                if (nameIndex == 0) {
                    // New name - decode as string
                    name = decodeString(buffer);
                } else {
                    name = getTableEntryName(nameIndex);
                }
                String value = decodeString(buffer);
                headers.put(name, value);
            }
        }

        return headers;
    }

    public int decodeInteger(ByteBuffer buffer, int prefixBits) {
        int maxPrefix = (1 << prefixBits) - 1;
        byte b = buffer.get();
        int value = b & maxPrefix;

        if (value < maxPrefix) {
            return value;
        }

        int m = 0;
        while (true) {
            b = buffer.get();
            value += (b & 0x7F) << m;
            if ((b & 0x80) == 0) {
                break;
            }
            m += 7;
        }

        return value;
    }

    public String decodeString(ByteBuffer buffer) {
        if (!buffer.hasRemaining()) {
            return "";
        }
        byte b = buffer.get();
        boolean huffmanEncoded = (b & 0x80) == 0x80;
        int length = b & 0x7F;

        if (length == 127) {
            int m = 0;
            int additionalLength = 0;
            while (buffer.hasRemaining()) {
                b = buffer.get();
                additionalLength += (b & 0x7F) << m;
                if ((b & 0x80) == 0) {
                    break;
                }
                m += 7;
            }
            length = 127 + additionalLength;
        }

        // HPACK bomb protection
        currentHeaderListSize += length;
        if (currentHeaderListSize > MAX_HEADER_LIST_SIZE) {
            throw new IllegalArgumentException(
                "Header list size exceeds maximum (" + MAX_HEADER_LIST_SIZE + " bytes). " +
                "Possible HPACK bomb attack."
            );
        }

        // Safety check: ensure we have enough bytes
        if (buffer.remaining() < length) {
            throw new IllegalArgumentException("Not enough bytes to read string of length " + length);
        }

        byte[] bytes = new byte[length];
        buffer.get(bytes);

        if (huffmanEncoded) {
            return decodeHuffman(bytes);
        } else {
            return new String(bytes);
        }
    }

    private String decodeHuffman(byte[] bytes) {
        return new String(bytes);
    }

    private String[] getTableEntry(int index) {
        if (index == 0) {
            return null;
        }

        if (index <= STATIC_TABLE.length) {
            return STATIC_TABLE[index - 1];
        }

        int dynamicIndex = index - STATIC_TABLE.length - 1;
        int count = 0;
        for (Map.Entry<String, LinkedList<String>> entry : dynamicTable.entrySet()) {
            if (dynamicIndex < entry.getValue().size()) {
                return new String[]{entry.getKey(), entry.getValue().get(dynamicIndex)};
            }
            dynamicIndex -= entry.getValue().size();
        }

        return null;
    }

    private String getTableEntryName(int index) {
        if (index == 0) {
            return "";
        }

        if (index <= STATIC_TABLE.length) {
            return STATIC_TABLE[index - 1][0];
        }

        int dynamicIndex = index - STATIC_TABLE.length - 1;
        for (Map.Entry<String, LinkedList<String>> entry : dynamicTable.entrySet()) {
            int entrySize = entry.getValue().size();
            if (dynamicIndex < entrySize) {
                return entry.getKey();
            }
            dynamicIndex -= entrySize;
        }

        return "";
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
