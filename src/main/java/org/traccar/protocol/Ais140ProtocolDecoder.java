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
import org.traccar.session.DeviceSession;

import java.net.SocketAddress;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.TimeZone;

public class Ais140ProtocolDecoder extends BaseProtocolDecoder {

    private static final Logger LOGGER = LoggerFactory.getLogger(Ais140ProtocolDecoder.class);
    // Fields 0..52 are required through the checksum/end marker.
    private static final int AIS140_CP_MINIMUM_FIELDS = 53;
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
        position.setLatitude(lat);
        position.setLongitude(lon);
    }

    private Object decodeLocation(Channel channel, SocketAddress remoteAddress, String[] values) {
        if (values.length < AIS140_CP_MINIMUM_FIELDS) {
            LOGGER.warn(
                    "AIS-140 CP rejected: expected at least {} fields, received {}",
                    AIS140_CP_MINIMUM_FIELDS,
                    values.length);
            return null;
        }
        DeviceSession deviceSession = getDeviceSession(channel, remoteAddress, value(values, 7));
        if (deviceSession == null) {
            LOGGER.warn("AIS-140 CP rejected: device not found for IMEI {}", value(values, 7));
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
        position.set(Position.KEY_INPUT, value(values, 46));
        position.set(Position.KEY_OUTPUT, value(values, 47));
        position.set(Position.KEY_INDEX, integer(value(values, 48)));
        position.set(Position.KEY_ODOMETER, decimal(value(values, 49)) * 1000);
        position.set("fenceId", value(values, 50));
        position.set("fuelData", value(values, 51));
        position.set("packetStatus", value(values, 6));
        position.set("vehicleRegistration", value(values, 8));
        position.set("mainPower", enabled(value(values, 24)));
        position.set("panic", enabled(value(values, 27)));
        position.set("tamper", value(values, 28));
        String checksum = value(values, 52);
        if (!checksum.isBlank() && !"?".equals(checksum)) {
            position.set("checksum", checksum);
        }
        return position;
    }

    private Object decodeEmergency(Channel channel, SocketAddress remoteAddress, String[] values) {
        if (values.length < 17) {
            LOGGER.warn("AIS-140 EPB rejected: expected at least 17 fields, received {}", values.length);
            return null;
        }
        DeviceSession deviceSession = getDeviceSession(channel, remoteAddress, value(values, 3));
        if (deviceSession == null) {
            LOGGER.warn("AIS-140 EPB rejected: device not found for IMEI {}", value(values, 3));
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
        position.set(Position.KEY_ODOMETER, decimal(value(values, 13)) * 1000);
        if ("EMR".equals(value(values, 2))) {
            position.set(Position.KEY_ALARM, Position.ALARM_SOS);
        }
        position.set("emergencyState", value(values, 2));
        position.set("packetStatus", value(values, 4));
        position.set("gpsProvider", value(values, 14));
        position.set("vehicleRegistration", value(values, 15));
        return position;
    }

    private Object decodeHealth(Channel channel, SocketAddress remoteAddress, String[] values) {
        if (values.length < 13) {
            LOGGER.warn("AIS-140 HP rejected: expected at least 13 fields, received {}", values.length);
            return null;
        }
        DeviceSession deviceSession = getDeviceSession(channel, remoteAddress, value(values, 4));
        if (deviceSession == null) {
            LOGGER.warn("AIS-140 HP rejected: device not found for IMEI {}", value(values, 4));
            return null;
        }
        Position position = lastPosition(deviceSession);
        position.set(Position.KEY_BATTERY_LEVEL, integer(value(values, 5)));
        position.set("lowBatteryThreshold", integer(value(values, 6)));
        position.set("memoryPercent", integer(value(values, 7)));
        position.set("ignitionInterval", integer(value(values, 8)));
        position.set("ignitionOffInterval", integer(value(values, 9)));
        position.set(Position.KEY_INPUT, value(values, 10));
        position.set(Position.KEY_POWER, decimal(value(values, 11)));
        position.set(Position.KEY_BATTERY, decimal(value(values, 12)));
        return position;
    }

    private Object decodeLogin(Channel channel, SocketAddress remoteAddress, String[] values) {
        if (values.length < 9) {
            LOGGER.warn("AIS-140 login rejected: expected at least 9 fields, received {}", values.length);
            return null;
        }
        DeviceSession deviceSession = getDeviceSession(channel, remoteAddress, value(values, 2));
        if (deviceSession == null) {
            LOGGER.warn("AIS-140 login rejected: device not found for IMEI {}", value(values, 2));
            return null;
        }
        Position position = lastPosition(deviceSession);
        position.set("vehicleRegistration", value(values, 1));
        position.set("firmware", value(values, 3));
        position.set("protocolVersion", value(values, 4));
        setCoordinates(position, value(values, 5), value(values, 6), value(values, 7), value(values, 8));
        return position;
    }

    private Object decodeHlp(Channel channel, SocketAddress remoteAddress, String[] values) {
        if (values.length < 10) {
            LOGGER.warn("AIS-140 HLP rejected: expected at least 10 fields, received {}", values.length);
            return null;
        }
        DeviceSession deviceSession = getDeviceSession(channel, remoteAddress, value(values, 3));
        if (deviceSession == null) {
            LOGGER.warn("AIS-140 HLP rejected: device not found for IMEI {}", value(values, 3));
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
            String power = value(values, 10);
            if (power.contains("*")) {
                power = power.substring(0, power.indexOf('*'));
            }
            position.set(Position.KEY_POWER, decimal(power));
        }
        if (values.length > 11) {
            String battery = value(values, 11);
            if (battery.contains("*")) {
                battery = battery.substring(0, battery.indexOf('*'));
            }
            position.set(Position.KEY_BATTERY, decimal(battery));
        }
        return position;
    }

    private Object decodeLgn(Channel channel, SocketAddress remoteAddress, String[] values) {
        if (values.length < 9) {
            LOGGER.warn("AIS-140 login rejected: expected at least 9 fields, received {}", values.length);
            return null;
        }
        DeviceSession deviceSession = getDeviceSession(channel, remoteAddress, value(values, 3));
        if (deviceSession == null) {
            LOGGER.warn("AIS-140 login rejected: device not found for IMEI {}", value(values, 3));
            return null;
        }
        Position position = lastPosition(deviceSession);
        position.set("vehicleRegistration", value(values, 2));
        position.set("firmware", value(values, 4));
        position.set("protocolVersion", value(values, 5));
        String lonHemisphere = value(values, 9);
        if (lonHemisphere.contains("*")) {
            lonHemisphere = lonHemisphere.substring(0, lonHemisphere.indexOf('*'));
        }
        setCoordinates(position, value(values, 6), value(values, 7), value(values, 8), lonHemisphere);
        return position;
    }

    private Object decodeNmp(Channel channel, SocketAddress remoteAddress, String[] values) {
        if (values.length < 15) {
            LOGGER.warn("AIS-140 NMP rejected: expected at least 15 fields, received {}", values.length);
            return null;
        }
        DeviceSession deviceSession = getDeviceSession(channel, remoteAddress, value(values, 6));
        if (deviceSession == null) {
            LOGGER.warn("AIS-140 NMP rejected: device not found for IMEI {}", value(values, 6));
            return null;
        }
        Position position = new Position(getProtocolName());
        position.setDeviceId(deviceSession.getDeviceId());
        position.setTime(parseUtc(value(values, 9), value(values, 10)));
        position.setValid(enabled(value(values, 8)));
        if (position.getValid()) {
            String lonHemisphere = value(values, 14);
            if (lonHemisphere.contains("*")) {
                lonHemisphere = lonHemisphere.substring(0, lonHemisphere.indexOf('*'));
            }
            setCoordinates(position, value(values, 11), value(values, 12), value(values, 13), lonHemisphere);
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
        position.set("vehicleRegistration", value(values, 7));

        position.set(Position.KEY_INPUT, value(values, 45));
        position.set(Position.KEY_OUTPUT, value(values, 46));
        position.set(Position.KEY_ODOMETER, decimal(value(values, 47)) * 1000);
        position.set(Position.KEY_INDEX, integer(value(values, 50)));

        if (values.length > 51) {
            String fuelAndChecksum = value(values, 51);
            if (fuelAndChecksum.contains("*")) {
                position.set("fuelData", fuelAndChecksum.substring(0, fuelAndChecksum.indexOf('*')));
                position.set("checksum", fuelAndChecksum.substring(fuelAndChecksum.indexOf('*') + 1));
            } else {
                position.set("fuelData", fuelAndChecksum);
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
            default -> value(values, 2);
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
        String header = value(values, 1);
        String firstField = value(values, 0);
        if (firstField.startsWith("$LGN")) {
            header = "LGN";
        } else if (firstField.startsWith("$NMP")) {
            header = "NMP";
        } else if (firstField.startsWith("$HLP")) {
            header = "HLP";
        }

        Object decoded = switch (header) {
            case "CP" -> decodeLocation(channel, remoteAddress, values);
            case "EPB" -> decodeEmergency(channel, remoteAddress, values);
            case "HP" -> decodeHealth(channel, remoteAddress, values);
            case "NMP" -> decodeNmp(channel, remoteAddress, values);
            case "LGN" -> decodeLgn(channel, remoteAddress, values);
            case "HLP" -> decodeHlp(channel, remoteAddress, values);
            default -> decodeLogin(channel, remoteAddress, values);
        };
        logDecoded(header, values, decoded);
        return decoded;
    }
}
