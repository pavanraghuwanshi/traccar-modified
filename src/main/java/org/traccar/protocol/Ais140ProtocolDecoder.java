/*
 * AIS-140 ATL-140 protocol decoder.
 */
package org.traccar.protocol;

import io.netty.channel.Channel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.traccar.BaseProtocolDecoder;
import org.traccar.Protocol;
import org.traccar.helper.UnitsConverter;
import org.traccar.model.Position;
import org.traccar.NetworkMessage;
import org.traccar.session.DeviceSession;

import java.net.SocketAddress;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.TimeZone;

public class Ais140ProtocolDecoder extends BaseProtocolDecoder {

    private static final Logger LOGGER = LoggerFactory.getLogger(Ais140ProtocolDecoder.class);
    // Fields 0..15 are required for basic location parsing.
    private static final int AIS140_CP_MINIMUM_FIELDS = 15;
    private static final Map<String, String> ALARMS = new HashMap<>();

    static {
        ALARMS.put("BD", Position.ALARM_POWER_CUT);
        ALARMS.put("BL", Position.ALARM_LOW_BATTERY);
        ALARMS.put("BH", Position.ALARM_LOW_BATTERY);
        ALARMS.put("BR", Position.ALARM_POWER_RESTORED);
        ALARMS.put("TA", Position.ALARM_TAMPERING);
        ALARMS.put("EA", Position.ALARM_SOS);
        ALARMS.put("HB", Position.ALARM_BRAKING);
        ALARMS.put("HA", Position.ALARM_ACCELERATION);
        ALARMS.put("RT", Position.ALARM_CORNERING);
        ALARMS.put("DT", Position.ALARM_TAMPERING);
        ALARMS.put("OD", Position.ALARM_OVERSPEED);
        ALARMS.put("FIX", Position.ALARM_GEOFENCE_ENTER);
        ALARMS.put("FOX", Position.ALARM_GEOFENCE_EXIT);
    }

    public Ais140ProtocolDecoder(Protocol protocol) {
        super(protocol);
    }

    private static String value(String[] values, int index) {
        return index < values.length ? values[index].trim() : "";
    }

    private static int integer(String value) {
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException error) {
            return 0;
        }
    }

    private static double decimal(String value) {
        try {
            return Double.parseDouble(value.trim());
        } catch (NumberFormatException error) {
            return 0;
        }
    }

    private static boolean enabled(String value) {
        return "1".equals(value);
    }

    private static String cleanField(String val) {
        if (val.contains("*")) {
            val = val.substring(0, val.indexOf('*'));
        }
        return val.trim();
    }

    private String findImei(String[] values, int defaultIndex) {
        String direct = value(values, defaultIndex);
        if (direct.matches("\\d{15}")) {
            return direct;
        }
        for (int i = 1; i < Math.min(values.length, 10); i++) {
            String v = value(values, i);
            if (v.matches("\\d{15}")) {
                return v;
            }
        }
        return direct;
    }

    private static Date parseUtc(String date, String time) {
        if (date.length() != 8 || time.length() != 6) {
            return new Date();
        }
        SimpleDateFormat format = new SimpleDateFormat("ddMMyyyyHHmmss");
        format.setLenient(false);
        format.setTimeZone(TimeZone.getTimeZone("UTC"));
        try {
            return format.parse(date + time);
        } catch (ParseException error) {
            return new Date();
        }
    }

    private double parseOdometer(String odoStr) {
        if (odoStr == null || odoStr.isBlank()) {
            return 0;
        }
        double val = decimal(odoStr);
        if (odoStr.contains(".")) {
            return val * 1000;
        }
        return val;
    }

    private Position lastPosition(DeviceSession deviceSession) {
        Position position = new Position(getProtocolName());
        position.setDeviceId(deviceSession.getDeviceId());
        getLastLocation(position, null);
        return position;
    }

    private void setCoordinates(Position position, String latitude, String latitudeHemisphere,
            String longitude, String longitudeHemisphere) {
        double lat = decimal(latitude);
        double lon = decimal(longitude);
        if ("S".equalsIgnoreCase(latitudeHemisphere)) {
            lat = -lat;
        }
        if ("W".equalsIgnoreCase(longitudeHemisphere)) {
            lon = -lon;
        }
        if (lat == 0.0 && lon == 0.0) {
            getLastLocation(position, null);
            position.setValid(false);
        } else {
            position.setLatitude(lat);
            position.setLongitude(lon);
        }
    }

    private Object decodeLocation(Channel channel, SocketAddress remoteAddress, String[] values) {
        if (values.length < AIS140_CP_MINIMUM_FIELDS) {
            LOGGER.warn(
                    "AIS-140 CP rejected: expected at least {} fields, received {}",
                    AIS140_CP_MINIMUM_FIELDS,
                    values.length);
            return null;
        }
        String imei = findImei(values, 7);
        DeviceSession deviceSession = getDeviceSession(channel, remoteAddress, imei);
        if (deviceSession == null) {
            LOGGER.warn("AIS-140 CP rejected: device not found for IMEI {}", imei);
            return null;
        }
        Position position = new Position(getProtocolName());
        position.setDeviceId(deviceSession.getDeviceId());
        position.setTime(parseUtc(value(values, 10), value(values, 11)));
        position.setValid(enabled(value(values, 9)));
        if (position.getValid()) {
            setCoordinates(position, value(values, 12), value(values, 13), value(values, 14), value(values, 15));
        } else {
            getLastLocation(position, null);
        }
        position.setSpeed(UnitsConverter.knotsFromKph(decimal(value(values, 16))));
        position.setCourse(decimal(value(values, 17)));
        position.setAltitude(decimal(value(values, 19)));
        position.set(Position.KEY_SATELLITES, integer(value(values, 18)));
        position.set(Position.KEY_PDOP, decimal(value(values, 20)));
        position.set(Position.KEY_HDOP, decimal(value(values, 21)));
        position.set("operator", value(values, 22));
        position.set("mcc", integer(value(values, 30)));
        position.set("mnc", integer(value(values, 31)));
        position.set("lac", value(values, 32));
        position.set("cell", value(values, 33));
        position.set(Position.KEY_IGNITION, enabled(value(values, 23)));
        position.set(Position.KEY_POWER, decimal(value(values, 25)));
        position.set(Position.KEY_BATTERY, decimal(value(values, 26)));
        position.set(Position.KEY_ALARM, ALARMS.get(value(values, 4)));
        position.set(Position.KEY_RSSI, integer(value(values, 29)));
        String packetStatus = value(values, 6);
        position.set("packetStatus", packetStatus);
        if ("H".equalsIgnoreCase(packetStatus) || "02".equals(value(values, 5))) {
            position.set(Position.KEY_ARCHIVE, true);
        }
        position.set("vehicleRegistration", value(values, 8));
        position.set("mainPower", enabled(value(values, 24)));
        position.set("panic", enabled(value(values, 27)));
        position.set("tamper", value(values, 28));

        int inputIndex = -1;
        for (int i = 34; i < values.length - 1; i++) {
            if (value(values, i).matches("[01]{4}") && value(values, i + 1).matches("[01]{2}")) {
                inputIndex = i;
                break;
            }
        }

        if (inputIndex >= 0) {
            position.set(Position.KEY_INPUT, value(values, inputIndex));
            position.set(Position.KEY_OUTPUT, value(values, inputIndex + 1));
            if (inputIndex + 2 < values.length) {
                position.set(Position.KEY_INDEX, integer(value(values, inputIndex + 2)));
            }
            if (inputIndex + 3 < values.length) {
                position.set(Position.KEY_ODOMETER, parseOdometer(value(values, inputIndex + 3)));
            }
            if (inputIndex + 4 < values.length) {
                position.set("fenceId", value(values, inputIndex + 4));
            }
            if (inputIndex + 5 < values.length) {
                String fuelAndChecksum = value(values, inputIndex + 5);
                if (fuelAndChecksum.contains("*")) {
                    position.set("fuelData", fuelAndChecksum.substring(0, fuelAndChecksum.indexOf('*')).trim());
                    String chk = fuelAndChecksum.substring(fuelAndChecksum.indexOf('*') + 1).trim();
                    if (!chk.isBlank() && !"?".equals(chk)) {
                        position.set("checksum", chk);
                    }
                } else {
                    position.set("fuelData", fuelAndChecksum.trim());
                    if (inputIndex + 6 < values.length) {
                        String chk = cleanField(value(values, inputIndex + 6));
                        if (!chk.isBlank() && !"?".equals(chk)) {
                            position.set("checksum", chk);
                        }
                    }
                }
            }
        } else {
            if (values.length > 46) {
                position.set(Position.KEY_INPUT, value(values, 46));
            }
            if (values.length > 47) {
                position.set(Position.KEY_OUTPUT, value(values, 47));
            }
            if (values.length > 48) {
                position.set(Position.KEY_INDEX, integer(value(values, 48)));
            }
            if (values.length > 49) {
                position.set(Position.KEY_ODOMETER, parseOdometer(value(values, 49)));
            }
            if (values.length > 50) {
                position.set("fenceId", value(values, 50));
            }
            if (values.length > 51) {
                String fuelAndChecksum = value(values, 51);
                if (fuelAndChecksum.contains("*")) {
                    position.set("fuelData", fuelAndChecksum.substring(0, fuelAndChecksum.indexOf('*')).trim());
                    String chk = fuelAndChecksum.substring(fuelAndChecksum.indexOf('*') + 1).trim();
                    if (!chk.isBlank() && !"?".equals(chk)) {
                        position.set("checksum", chk);
                    }
                } else {
                    position.set("fuelData", fuelAndChecksum.trim());
                    if (values.length > 52) {
                        String chk = cleanField(value(values, 52));
                        if (!chk.isBlank() && !"?".equals(chk)) {
                            position.set("checksum", chk);
                        }
                    }
                }
            }
        }

        return position;
    }

    private Object decodeEmergency(Channel channel, SocketAddress remoteAddress, String[] values) {
        if (values.length < 16) {
            LOGGER.warn("AIS-140 EPB rejected: expected at least 16 fields, received {}", values.length);
            return null;
        }
        String imei = findImei(values, 3);
        DeviceSession deviceSession = getDeviceSession(channel, remoteAddress, imei);
        if (deviceSession == null) {
            LOGGER.warn("AIS-140 EPB rejected: device not found for IMEI {}", imei);
            return null;
        }
        Position position = new Position(getProtocolName());
        position.setDeviceId(deviceSession.getDeviceId());
        String dateTime = value(values, 5);
        if (dateTime.length() == 14) {
            position.setTime(parseUtc(dateTime.substring(0, 8), dateTime.substring(8, 14)));
        }
        position.setValid("A".equalsIgnoreCase(value(values, 6)));
        setCoordinates(position, value(values, 7), value(values, 8), value(values, 9), value(values, 10));
        position.setAltitude(decimal(value(values, 11)));
        position.setSpeed(UnitsConverter.knotsFromKph(decimal(value(values, 12))));
        position.set(Position.KEY_ODOMETER, parseOdometer(value(values, 13)));
        if ("EMR".equals(value(values, 2))) {
            position.set(Position.KEY_ALARM, Position.ALARM_SOS);
        }
        position.set("emergencyState", value(values, 2));
        String packetStatus = value(values, 4);
        position.set("packetStatus", packetStatus);
        if ("SP".equalsIgnoreCase(packetStatus) || "H".equalsIgnoreCase(packetStatus)) {
            position.set(Position.KEY_ARCHIVE, true);
        }
        position.set("gpsProvider", value(values, 14));
        position.set("vehicleRegistration", value(values, 15));
        return position;
    }

    private Object decodeHealth(Channel channel, SocketAddress remoteAddress, String[] values) {
        if (values.length < 12) {
            LOGGER.warn("AIS-140 HP rejected: expected at least 12 fields, received {}", values.length);
            return null;
        }
        String imei = findImei(values, 4);
        DeviceSession deviceSession = getDeviceSession(channel, remoteAddress, imei);
        if (deviceSession == null) {
            LOGGER.warn("AIS-140 HP rejected: device not found for IMEI {}", imei);
            return null;
        }
        Position position = lastPosition(deviceSession);
        position.set(Position.KEY_BATTERY_LEVEL, integer(value(values, 5)));
        position.set("lowBatteryThreshold", integer(value(values, 6)));
        position.set("memoryPercent", integer(value(values, 7)));
        position.set("ignitionInterval", integer(value(values, 8)));
        position.set("ignitionOffInterval", integer(value(values, 9)));
        position.set(Position.KEY_INPUT, value(values, 10));
        position.set(Position.KEY_POWER, decimal(cleanField(value(values, 11))));
        if (values.length > 12) {
            position.set(Position.KEY_BATTERY, decimal(cleanField(value(values, 12))));
        }
        return position;
    }

    private Object decodeLogin(Channel channel, SocketAddress remoteAddress, String[] values) {
        if (values.length < 9) {
            LOGGER.warn("AIS-140 login rejected: expected at least 9 fields, received {}", values.length);
            return null;
        }
        String imei = findImei(values, 2);
        DeviceSession deviceSession = getDeviceSession(channel, remoteAddress, imei);
        if (deviceSession == null) {
            LOGGER.warn("AIS-140 login rejected: device not found for IMEI {}", imei);
            return null;
        }
        Position position = new Position(getProtocolName());
        position.setDeviceId(deviceSession.getDeviceId());
        position.setTime(new Date());
        position.setValid(true);
        position.set("vehicleRegistration", value(values, 1));
        position.set("firmware", value(values, 3));
        position.set("protocolVersion", value(values, 4));
        setCoordinates(position, value(values, 5), value(values, 6), value(values, 7), cleanField(value(values, 8)));
        return position;
    }

    private Object decodeHlp(Channel channel, SocketAddress remoteAddress, String[] values) {
        if (values.length < 10) {
            LOGGER.warn("AIS-140 HLP rejected: expected at least 10 fields, received {}", values.length);
            return null;
        }
        String imei = findImei(values, 3);
        DeviceSession deviceSession = getDeviceSession(channel, remoteAddress, imei);
        if (deviceSession == null) {
            LOGGER.warn("AIS-140 HLP rejected: device not found for IMEI {}", imei);
            return null;
        }
        Position position = lastPosition(deviceSession);
        position.set(Position.KEY_BATTERY_LEVEL, integer(value(values, 4)));
        position.set("lowBatteryThreshold", integer(value(values, 5)));
        position.set("memoryPercent", integer(value(values, 6)));
        position.set("ignitionInterval", integer(value(values, 7)));
        position.set("ignitionOffInterval", integer(value(values, 8)));
        position.set(Position.KEY_INPUT, value(values, 9));

        if (values.length > 10) {
            position.set(Position.KEY_POWER, decimal(cleanField(value(values, 10))));
        }
        if (values.length > 11) {
            position.set(Position.KEY_BATTERY, decimal(cleanField(value(values, 11))));
        }
        return position;
    }

    private Object decodeLgn(Channel channel, SocketAddress remoteAddress, String[] values) {
        if (values.length < 9) {
            LOGGER.warn("AIS-140 login rejected: expected at least 9 fields, received {}", values.length);
            return null;
        }
        String imei = findImei(values, 3);
        DeviceSession deviceSession = getDeviceSession(channel, remoteAddress, imei);
        if (deviceSession == null) {
            LOGGER.warn("AIS-140 login rejected: device not found for IMEI {}", imei);
            return null;
        }
        Position position = new Position(getProtocolName());
        position.setDeviceId(deviceSession.getDeviceId());
        position.setTime(new Date());
        position.setValid(true);
        position.set("vehicleRegistration", value(values, 2));
        position.set("firmware", value(values, 4));
        position.set("protocolVersion", value(values, 5));
        setCoordinates(position, value(values, 6), value(values, 7), value(values, 8), cleanField(value(values, 9)));
        return position;
    }

    private Object decodeNmp(Channel channel, SocketAddress remoteAddress, String[] values) {
        if (values.length < 15) {
            LOGGER.warn("AIS-140 NMP rejected: expected at least 15 fields, received {}", values.length);
            return null;
        }
        String imei = findImei(values, 6);
        DeviceSession deviceSession = getDeviceSession(channel, remoteAddress, imei);
        if (deviceSession == null) {
            LOGGER.warn("AIS-140 NMP rejected: device not found for IMEI {}", imei);
            return null;
        }
        Position position = new Position(getProtocolName());
        position.setDeviceId(deviceSession.getDeviceId());
        position.setTime(parseUtc(value(values, 9), value(values, 10)));
        position.setValid(enabled(value(values, 8)));
        if (position.getValid()) {
            setCoordinates(
                    position, value(values, 11), value(values, 12),
                    value(values, 13), cleanField(value(values, 14)));
        } else {
            getLastLocation(position, null);
        }
        position.setSpeed(UnitsConverter.knotsFromKph(decimal(value(values, 15))));
        position.setCourse(decimal(value(values, 16)));
        position.set(Position.KEY_SATELLITES, integer(value(values, 17)));
        position.setAltitude(decimal(value(values, 18)));
        position.set(Position.KEY_PDOP, decimal(value(values, 19)));
        position.set(Position.KEY_HDOP, decimal(value(values, 20)));
        position.set("operator", value(values, 21));
        position.set(Position.KEY_IGNITION, enabled(value(values, 22)));
        position.set("mainPower", enabled(value(values, 23)));
        position.set(Position.KEY_POWER, decimal(value(values, 24)));
        position.set(Position.KEY_BATTERY, decimal(value(values, 25)));
        position.set("panic", enabled(value(values, 26)));
        position.set("tamper", value(values, 27));
        position.set(Position.KEY_RSSI, integer(value(values, 28)));
        position.set("mcc", integer(value(values, 29)));
        position.set("mnc", integer(value(values, 30)));
        position.set("lac", value(values, 31));
        position.set("cell", value(values, 32));

        position.set(Position.KEY_ALARM, ALARMS.get(value(values, 4)));
        position.set("packetStatus", value(values, 3));
        if ("H".equalsIgnoreCase(value(values, 5))) {
            position.set(Position.KEY_ARCHIVE, true);
        }
        position.set("vehicleRegistration", value(values, 7));

        int inputIndex = -1;
        for (int i = 30; i < values.length - 1; i++) {
            if (value(values, i).matches("[01]{4}") && value(values, i + 1).matches("[01]{2}")) {
                inputIndex = i;
                break;
            }
        }

        if (inputIndex >= 0) {
            position.set(Position.KEY_INPUT, value(values, inputIndex));
            position.set(Position.KEY_OUTPUT, value(values, inputIndex + 1));
            if (inputIndex + 2 < values.length) {
                position.set(Position.KEY_INDEX, integer(value(values, inputIndex + 2)));
            }
            if (inputIndex + 3 < values.length) {
                position.set(Position.PREFIX_ADC + 1, decimal(value(values, inputIndex + 3)));
            }
            if (inputIndex + 4 < values.length) {
                position.set(Position.PREFIX_ADC + 2, decimal(value(values, inputIndex + 4)));
            }
            if (inputIndex + 5 < values.length) {
                position.set(Position.KEY_ODOMETER, decimal(value(values, inputIndex + 5)) * 1000);
            }
        } else {
            position.set(Position.KEY_INPUT, value(values, 45));
            position.set(Position.KEY_OUTPUT, value(values, 46));
            position.set(Position.KEY_INDEX, integer(value(values, 47)));
            position.set(Position.PREFIX_ADC + 1, decimal(value(values, 48)));
            position.set(Position.PREFIX_ADC + 2, decimal(value(values, 49)));
            position.set(Position.KEY_ODOMETER, decimal(value(values, 50)) * 1000);
        }

        int fuelStart = inputIndex >= 0 ? inputIndex + 6 : 51;
        if (values.length > fuelStart) {
            String tail = String.join(",", Arrays.copyOfRange(values, fuelStart, values.length));
            if (tail.contains("*")) {
                position.set("fuelData", tail.substring(0, tail.indexOf('*')).trim());
                String chk = tail.substring(tail.indexOf('*') + 1).trim();
                if (!chk.isBlank()) {
                    position.set("checksum", chk);
                }
            } else {
                position.set("fuelData", tail.trim());
            }
        }
        return position;
    }

    private void logDecoded(String header, String[] values, Object decoded) {
        if (!(decoded instanceof Position position)) {
            return;
        }

        String imei = switch (header) {
            case "CP" -> value(values, 7);
            case "EPB" -> value(values, 3);
            case "HP" -> value(values, 4);
            case "NMP" -> value(values, 6);
            case "LGN", "HLP" -> value(values, 3);
            default -> findImei(values, 2);
        };
        String event = "CP".equals(header) ? value(values, 4) : header;
        if ("NMP".equals(header)) {
            event = value(values, 3);
        }

        if (("CP".equals(header) || "NMP".equals(header)) && "NR".equals(event)) {
            LOGGER.debug("AIS-140 position decoded: imei={}, status={}, valid={}, lat={}, lon={}, speedKnots={}",
                    imei, "CP".equals(header) ? value(values, 6) : value(values, 3),
                    position.getValid(), position.getLatitude(), position.getLongitude(), position.getSpeed());
        } else {
            LOGGER.info("AIS-140 packet decoded: imei={}, header={}, event={}, valid={}, lat={}, lon={}",
                    imei, header, event, position.getValid(), position.getLatitude(), position.getLongitude());
        }
    }

    @Override
    protected Object decode(Channel channel, SocketAddress remoteAddress, Object msg) {
        String message = ((String) msg).trim();
        LOGGER.debug("AIS-140 raw packet from {}: {}", remoteAddress, message);
        if (message.contains("WRONG COMMAND")) {
            return null;
        }
        int start = message.indexOf('$');
        if (start < 0) {
            LOGGER.warn("AIS-140 packet rejected: start character '$' is missing");
            return null;
        }
        String[] values = message.substring(start).split(",", -1);
        if (values.length < 2) {
            LOGGER.warn("AIS-140 packet rejected: header is missing");
            return null;
        }

        for (int i = 0; i < values.length; i++) {
            values[i] = values[i].trim();
        }

        String firstField = value(values, 0);
        String header = value(values, 1);
        if (firstField.startsWith("$") && firstField.length() > 1) {
            header = firstField.substring(1);
        } else if ("$LGN".equals(firstField)) {
            header = "LGN";
        } else if ("$NMP".equals(firstField)) {
            header = "NMP";
        } else if ("$HLP".equals(firstField)) {
            header = "HLP";
        }

        Object decoded = switch (header) {
            case "CP" -> decodeLocation(channel, remoteAddress, values);
            case "EPB" -> decodeEmergency(channel, remoteAddress, values);
            case "HP" -> decodeHealth(channel, remoteAddress, values);
            case "NMP" -> decodeNmp(channel, remoteAddress, values);
            case "LGN" -> decodeLgn(channel, remoteAddress, values);
            case "HLP" -> decodeHlp(channel, remoteAddress, values);
            default -> {
                if (values.length >= 8) {
                    yield decodeLogin(channel, remoteAddress, values);
                }
                yield null;
            }
        };

        if (channel != null && decoded != null) {
            boolean isAspl = "ASPL".equalsIgnoreCase(value(values, 1))
                    || "ASPL".equalsIgnoreCase(value(values, 2));
            boolean isCommaDelimitedLogin = values.length <= 10
                    && !"LGN".equals(header) && !"HP".equals(header)
                    && !"HLP".equals(header) && !"EPB".equals(header) && !"NMP".equals(header);
            boolean isLogin = isCommaDelimitedLogin || ("LGN".equals(header) && !isAspl);
            if (isLogin) {
                SimpleDateFormat dateFormat = new SimpleDateFormat("ddMMyyyyHHmmss");
                dateFormat.setTimeZone(TimeZone.getTimeZone("UTC"));
                String response = "$LGN," + dateFormat.format(new Date()) + "*\r\n";
                channel.writeAndFlush(new NetworkMessage(response, remoteAddress));
            } else if ("CP".equals(header) || "EPB".equals(header)) {
                if (decoded instanceof Position pos && pos.getAttributes().containsKey(Position.KEY_INDEX)) {
                    int idx = (Integer) pos.getAttributes().get(Position.KEY_INDEX);
                    if (idx > 0) {
                        String ack = "$ACK," + String.format("%06d", idx) + "*\r\n";
                        channel.writeAndFlush(new NetworkMessage(ack, remoteAddress));
                    } else {
                        channel.writeAndFlush(new NetworkMessage("$ACK*\r\n", remoteAddress));
                    }
                } else {
                    channel.writeAndFlush(new NetworkMessage("$ACK*\r\n", remoteAddress));
                }
            } else if ("HP".equals(header) || ("HLP".equals(header) && !isAspl)) {
                channel.writeAndFlush(new NetworkMessage("$ACK*\r\n", remoteAddress));
            }
        }

        logDecoded(header, values, decoded);
        return decoded;
    }
}
