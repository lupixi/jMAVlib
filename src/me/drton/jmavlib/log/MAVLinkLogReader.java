package me.drton.jmavlib.log;

import me.drton.jmavlib.mavlink.MAVLinkField;
import me.drton.jmavlib.mavlink.MAVLinkMessage;
import me.drton.jmavlib.mavlink.MAVLinkSchema;
import me.drton.jmavlib.mavlink.MAVLinkStream;

import java.io.EOFException;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.*;

/**
 * User: ton Date: 25.07.14 Time: 21:43
 */
public class MAVLinkLogReader implements LogReader {
    private RandomAccessFile file;
    private Map<String, String> fieldsFormats;
    private MAVLinkStream stream;
    private long time;
    private long sizeUpdates = -1;
    private long sizeMicroseconds = -1;
    private long startMicroseconds = -1;

    public MAVLinkLogReader(String fileName, MAVLinkSchema schema) throws IOException, FormatErrorException {
        file = new RandomAccessFile(fileName, "r");
        stream = new MAVLinkStream(schema, file.getChannel());
        updateInfo();
    }

    @Override
    public void close() throws IOException {
        file.close();
    }

    @Override
    public boolean seek(long seekTime) throws FormatErrorException, IOException {
        time = 0;
        stream.position(0);
        if (seekTime == 0) {
            return true;
        }
        while (true) {
            long pos = stream.position();
            MAVLinkMessage msg = stream.read();
            if (msg == null) {
                break;
            }
            long t = getTime(msg);
            if (t >= 0) {
                if (t > seekTime) {
                    stream.position(pos);
                    return true;
                } else if (t > time) {
                    time = t;
                }
            }
        }
        return false;
    }

    private String fieldName(MAVLinkMessage msg, MAVLinkField field) {
        return "M" + msg.systemID + ":" + msg.getMsgName() + "." + field.name;
    }

    private void addMessageFormat(MAVLinkMessage msg) {
        for (MAVLinkField field : msg.definition.fields) {
            String type;
            switch (field.type) {
                case CHAR:
                    type = "char";
                    break;
                case UINT8:
                    type = "uint8";
                    break;
                case INT8:
                    type = "int8";
                    break;
                case UINT16:
                    type = "uint16";
                    break;
                case INT16:
                    type = "int16";
                    break;
                case UINT32:
                    type = "uint32";
                    break;
                case INT32:
                    type = "int32";
                    break;
                case UINT64:
                    type = "uint64";
                    break;
                case INT64:
                    type = "int64";
                    break;
                case FLOAT:
                    type = "float";
                    break;
                case DOUBLE:
                    type = "double";
                    break;
                default:
                    type = "<unknown>";
                    break;
            }
            fieldsFormats.put(fieldName(msg, field), type);
        }
    }

    private void updateInfo() throws IOException, FormatErrorException {
        fieldsFormats = new HashMap<String, String>();
        Set<String> messagesSysIDs = new HashSet<String>();
        seek(0);
        long packetsNum = 0;
        long timeStart = -1;
        long timeEnd = -1;
        while (true) {
            MAVLinkMessage msg;
            msg = stream.read();
            if (msg == null) {
                break;
            }

            long t = getTime(msg);
            if (t >= 0) {
                if (timeStart < 0) {
                    timeStart = t;
                }
                if (t > timeEnd) {
                    timeEnd = t;
                }
                packetsNum++;
            }

            String msgSysID = msg.systemID + ":" + msg.getMsgName();
            if (!messagesSysIDs.contains(msgSysID)) {
                messagesSysIDs.add(msgSysID);
                addMessageFormat(msg);
            }
        }
        startMicroseconds = timeStart;
        sizeUpdates = packetsNum;
        sizeMicroseconds = timeEnd - timeStart;
        seek(0);
    }

    private long getTime(MAVLinkMessage msg) {
        MAVLinkField field;
        field = msg.definition.fieldsByName.get("time_usec");
        if (field != null) {
            return ((Number) msg.get(field)).longValue();
        }
        field = msg.definition.fieldsByName.get("time_boot_ms");
        if (field != null) {
            return ((Number) msg.get(field)).longValue() * 1000;
        }
        return -1;
    }

    @Override
    public long readUpdate(Map<String, Object> update) throws IOException, FormatErrorException {
        MAVLinkMessage msg = stream.read();
        if (msg == null) {
            throw new EOFException();
        }
        for (MAVLinkField field : msg.definition.fields) {
            update.put(fieldName(msg, field), msg.get(field));
            long t = getTime(msg);
            if (t >= 0 && t > time) {
                time = t;
            }
        }
        return time;
    }

    @Override
    public Map<String, String> getFields() {
        return fieldsFormats;
    }

    @Override
    public String getFormat() {
        return "MAVLink";
    }

    @Override
    public long getSizeUpdates() {
        return sizeUpdates;
    }

    @Override
    public long getStartMicroseconds() {
        return startMicroseconds;
    }

    @Override
    public long getSizeMicroseconds() {
        return sizeMicroseconds;
    }

    @Override
    public Map<String, Object> getVersion() {
        return Collections.emptyMap();
    }

    @Override
    public Map<String, Object> getParameters() {
        return Collections.emptyMap();
    }

    public static void main(String[] args) throws Exception {
        MAVLinkLogReader reader = new MAVLinkLogReader("test.mavlink", new MAVLinkSchema("common.xml"));
        long tStart = System.currentTimeMillis();
        Map<String, Object> data = new HashMap<String, Object>();
        while (true) {
            long t;
            data.clear();
            try {
                t = reader.readUpdate(data);
            } catch (EOFException e) {
                break;
            }
            System.out.println(t + " " + data);
        }
        long tEnd = System.currentTimeMillis();
        System.out.println(tEnd - tStart);
        reader.close();
    }
}
