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
import org.traccar.model.CellTower;
import org.traccar.model.Network;
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
        ALARMS.put("WD", Position.ALARM_SOS);
        ALARMS.put("HB", Position.ALARM_BRAKING);
        ALARMS.put("HA", Position.ALARM_ACCELERATION);
        ALARMS.put("RT", Position.ALARM_CORNERING);
        ALARMS.put("DT", Position.ALARM_TAMPERING);
        ALARMS.put("OD", Position.ALARM_OVERSPEED);
        ALARMS.put("OS", Position.ALARM_OVERSPEED);
        ALARMS.put("PC", Position.ALARM_POWER_CUT);
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

    private static Integer parseDecimalInt(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException error) {
            return null;
        }
    }

    private static Integer parseHexInt(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Integer.parseInt(value.trim(), 16);
        } catch (NumberFormatException error) {
            return null;
        }
    }

    private static Long parseHexLong(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(value.trim(), 16);
        } catch (NumberFormatException error) {
            return null;
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

    private Date parseDateTime(String date, String time, DeviceSession deviceSession) {
        if (time.length() != 6 || (date.length() != 8 && date.length() != 6)) {
            return new Date();
        }
        String pattern = date.length() == 8 ? "ddMMyyyyHHmmss" : "ddMMyyHHmmss";
        SimpleDateFormat format = new SimpleDateFormat(pattern);
        format.setLenient(false);
        TimeZone timeZone = getCacheManager() != null && deviceSession != null
                ? getTimeZone(deviceSession.getDeviceId())
                : TimeZone.getTimeZone("UTC");
        format.setTimeZone(timeZone != null ? timeZone : TimeZone.getTimeZone("UTC"));
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
            String longitude, String longitudeHemisphere, boolean useLastLocationOnZero) {
        double lat = decimal(cleanField(latitude));
        double lon = decimal(cleanField(longitude));
        if ("S".equalsIgnoreCase(latitudeHemisphere)) {
            lat = -lat;
        }
        if ("W".equalsIgnoreCase(longitudeHemisphere)) {
            lon = -lon;
        }
        if (!Double.isFinite(lat) || !Double.isFinite(lon)
                || lat < -90 || lat > 90 || lon < -180 || lon > 180) {
            LOGGER.warn("AIS-140 invalid coordinates rejected: {}, {}", latitude, longitude);
            if (useLastLocationOnZero) {
                getLastLocation(position, null);
            }
            position.setValid(false);
        } else if (lat == 0.0 && lon == 0.0) {
            if (useLastLocationOnZero) {
                getLastLocation(position, null);
            }
            position.setValid(false);
        } else {
            position.setLatitude(lat);
            position.setLongitude(lon);
        }
    }

    private static int findInputIndex(String[] values, int cellBaseIndex) {
        int maxScan = Math.min(values.length - 2, cellBaseIndex + 18);
        for (int i = maxScan; i >= cellBaseIndex; i--) {
            if ((i - cellBaseIndex) % 3 == 0
                    && value(values, i).matches("[012]{4,6}")
                    && value(values, i + 1).matches("[01]{2}")
                    && (i + 2 >= values.length || cleanField(value(values, i + 2)).matches("\\d+(\\.\\d+)?"))) {
                return i;
            }
        }
        for (int i = maxScan; i >= cellBaseIndex; i--) {
            if (value(values, i).matches("[012]{4,6}")
                    && value(values, i + 1).matches("[01]{2}")
                    && (i + 2 >= values.length || cleanField(value(values, i + 2)).matches("\\d+"))
                    && (i + 3 >= values.length || !value(values, i + 3).matches("[0-9a-fA-F]{4}"))) {
                return i;
            }
        }
        return -1;
    }

    private void parseNetwork(
            Position position, String[] values, int rssiIndex, int mccIndex,
            int neighborStart, int neighborEnd, boolean cidFirstNeighbors, String operator) {
        String mccStr = value(values, mccIndex);
        String mncStr = value(values, mccIndex + 1);
        if (!mccStr.matches("\\d{1,3}") || !mncStr.matches("\\d{1,3}")) {
            return;
        }
        Integer mcc = parseDecimalInt(mccStr);
        Integer mnc = parseDecimalInt(mncStr);
        Integer lac = parseHexInt(value(values, mccIndex + 2));
        Long cid = parseHexLong(value(values, mccIndex + 3));
        if (mcc == null || mnc == null || mcc <= 0 || mcc > 999 || mnc < 0 || mnc > 999
                || lac == null || lac < 1 || lac > 65535
                || cid == null || cid < 0 || cid > 268435455L) {
            return;
        }

        Network network = new Network();
        if (operator != null && !operator.isBlank()) {
            network.setCarrier(operator);
        }
        Integer rssi = parseDecimalInt(value(values, rssiIndex));
        network.addCellTower(rssi != null
                ? CellTower.from(mcc, mnc, lac, cid, rssi)
                : CellTower.from(mcc, mnc, lac, cid));

        for (int i = neighborStart; i + 2 < neighborEnd; i += 3) {
            String f0 = value(values, i);
            String f1 = value(values, i + 1);
            String f2 = value(values, i + 2);
            if (f0.isEmpty() || f1.isEmpty() || f2.isEmpty()
                    || "x".equalsIgnoreCase(f0) || "x".equalsIgnoreCase(f1) || "x".equalsIgnoreCase(f2)) {
                continue;
            }
            Integer nRssi;
            Integer nLac;
            Long nCid;
            if (cidFirstNeighbors
                    && !(f0.matches("-?\\d{1,3}") && f2.matches("[0-9a-fA-F]{3,8}") && !f2.matches("\\d{1,2}"))) {
                nCid = parseHexLong(f0);
                nLac = parseHexInt(f1);
                nRssi = parseDecimalInt(f2);
            } else {
                nRssi = parseDecimalInt(f0);
                nLac = parseHexInt(f1);
                nCid = parseHexLong(f2);
            }
            if (nLac != null && nLac >= 1 && nLac <= 65535 && nCid != null && nCid >= 0 && nCid <= 268435455L) {
                network.addCellTower(nRssi != null
                        ? CellTower.from(mcc, mnc, nLac, nCid, nRssi)
                        : CellTower.from(mcc, mnc, nLac, nCid));
            }
        }
        position.setNetwork(network);
    }

    private void parseFuelAndChecksum(Position position, String[] values, int fuelStart) {
        if (values.length <= fuelStart) {
            return;
        }
        String firstFuel = value(values, fuelStart);
        if (firstFuel.startsWith("(") || values.length == fuelStart + 1) {
            String tail = String.join(",", Arrays.copyOfRange(values, fuelStart, values.length));
            if (tail.contains("*")) {
                position.set("fuelData", tail.substring(0, tail.indexOf('*')).trim());
                String chk = tail.substring(tail.indexOf('*') + 1).trim();
                if (!chk.isBlank() && !"?".equals(chk)) {
                    position.set("checksum", chk);
                }
            } else {
                position.set("fuelData", tail.trim());
            }
        } else {
            if (firstFuel.contains("*")) {
                position.set("fuelData", firstFuel.substring(0, firstFuel.indexOf('*')).trim());
                String chk = firstFuel.substring(firstFuel.indexOf('*') + 1).trim();
                if (!chk.isBlank() && !"?".equals(chk)) {
                    position.set("checksum", chk);
                }
            } else {
                position.set("fuelData", firstFuel);
                if (fuelStart + 1 < values.length) {
                    String chk = cleanField(value(values, fuelStart + 1));
                    if (!chk.isBlank() && !"?".equals(chk)) {
                        position.set("checksum", chk);
                    }
                }
            }
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
        position.setTime(parseDateTime(value(values, 10), value(values, 11), deviceSession));
        position.setValid(enabled(value(values, 9)));
        setCoordinates(
                position, value(values, 12), value(values, 13),
                value(values, 14), cleanField(value(values, 15)), false);
        position.setSpeed(UnitsConverter.knotsFromKph(decimal(value(values, 16))));
        position.setCourse(decimal(value(values, 17)));
        position.setAltitude(decimal(value(values, 19)));
        position.set(Position.KEY_SATELLITES, integer(value(values, 18)));
        position.set(Position.KEY_PDOP, decimal(value(values, 20)));
        position.set(Position.KEY_HDOP, decimal(value(values, 21)));
        String operator = value(values, 22);
        position.set("operator", operator);
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

        int inputIndex = findInputIndex(values, 34);
        int neighborEnd = inputIndex >= 0 ? inputIndex : Math.min(values.length, 46);
        parseNetwork(position, values, 29, 30, 34, neighborEnd, true, operator);

        if (inputIndex >= 0) {
            position.set(Position.KEY_INPUT, value(values, inputIndex));
            position.set(Position.KEY_OUTPUT, value(values, inputIndex + 1));
            if (inputIndex + 2 < values.length) {
                position.set(Position.KEY_INDEX, integer(cleanField(value(values, inputIndex + 2))));
            }
            if (inputIndex + 3 < values.length) {
                position.set(Position.KEY_ODOMETER, parseOdometer(cleanField(value(values, inputIndex + 3))));
            }
            if (inputIndex + 4 < values.length) {
                position.set("fenceId", cleanField(value(values, inputIndex + 4)));
            }
            parseFuelAndChecksum(position, values, inputIndex + 5);
        } else {
            if (values.length > 46) {
                position.set(Position.KEY_INPUT, value(values, 46));
            }
            if (values.length > 47) {
                position.set(Position.KEY_OUTPUT, value(values, 47));
            }
            if (values.length > 48) {
                position.set(Position.KEY_INDEX, integer(cleanField(value(values, 48))));
            }
            if (values.length > 49) {
                position.set(Position.KEY_ODOMETER, parseOdometer(cleanField(value(values, 49))));
            }
            if (values.length > 50) {
                position.set("fenceId", cleanField(value(values, 50)));
            }
            parseFuelAndChecksum(position, values, 51);
        }

        return position;
    }

    private Object decodeEmergency(Channel channel, SocketAddress remoteAddress, String[] values) {
        int offset = ("EMR".equalsIgnoreCase(value(values, 3))
                || "SEM".equalsIgnoreCase(value(values, 3))) ? 1 : 0;
        if (values.length < 16 + offset) {
            LOGGER.warn("AIS-140 EPB rejected: expected at least {} fields, received {}",
                    16 + offset, values.length);
            return null;
        }
        String imei = findImei(values, 3 + offset);
        DeviceSession deviceSession = getDeviceSession(channel, remoteAddress, imei);
        if (deviceSession == null) {
            LOGGER.warn("AIS-140 EPB rejected: device not found for IMEI {}", imei);
            return null;
        }
        Position position = new Position(getProtocolName());
        position.setDeviceId(deviceSession.getDeviceId());
        String dateTime = value(values, 5 + offset);
        if (dateTime.length() == 14) {
            position.setTime(parseDateTime(
                    dateTime.substring(0, 8), dateTime.substring(8, 14), deviceSession));
        } else {
            position.setTime(new Date());
        }
        position.setValid("A".equalsIgnoreCase(value(values, 6 + offset)));
        setCoordinates(
                position, value(values, 7 + offset), value(values, 8 + offset),
                value(values, 9 + offset), cleanField(value(values, 10 + offset)), false);
        position.setAltitude(decimal(value(values, 11 + offset)));
        position.setSpeed(UnitsConverter.knotsFromKph(decimal(value(values, 12 + offset))));
        position.set(Position.KEY_ODOMETER, parseOdometer(cleanField(value(values, 13 + offset))));
        String emergencyState = value(values, 2 + offset);
        if ("EMR".equalsIgnoreCase(emergencyState)) {
            position.set(Position.KEY_ALARM, Position.ALARM_SOS);
        }
        position.set("emergencyState", emergencyState);
        String packetStatus = value(values, 4 + offset);
        position.set("packetStatus", packetStatus);
        if ("SP".equalsIgnoreCase(packetStatus) || "H".equalsIgnoreCase(packetStatus)) {
            position.set(Position.KEY_ARCHIVE, true);
        }
        position.set("gpsProvider", value(values, 14 + offset));
        position.set("vehicleRegistration", cleanField(value(values, 15 + offset)));
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
        setCoordinates(
                position, value(values, 5), value(values, 6),
                value(values, 7), cleanField(value(values, 8)), true);
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
            String field10 = cleanField(value(values, 10));
            if (field10.matches("[01]{2}") && (values.length == 11
                    || (values.length > 11 && cleanField(value(values, 11)).contains(".")))) {
                position.set(Position.KEY_OUTPUT, field10);
                if (values.length > 11) {
                    position.set(Position.KEY_POWER, decimal(cleanField(value(values, 11))));
                }
                if (values.length > 12) {
                    position.set(Position.KEY_BATTERY, decimal(cleanField(value(values, 12))));
                }
            } else {
                position.set(Position.KEY_POWER, decimal(field10));
                if (values.length > 11) {
                    position.set(Position.KEY_BATTERY, decimal(cleanField(value(values, 11))));
                }
            }
        }
        return position;
    }

    private Object decodeLgn(Channel channel, SocketAddress remoteAddress, String[] values) {
        if (values.length == 9 && value(values, 2).matches("\\d{15}")) {
            return decodeLogin(channel, remoteAddress, values);
        }
        if (values.length < 10) {
            LOGGER.warn("AIS-140 login rejected: expected at least 10 fields, received {}", values.length);
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
        setCoordinates(
                position, value(values, 6), value(values, 7),
                value(values, 8), cleanField(value(values, 9)), true);
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
        position.setTime(parseDateTime(value(values, 9), value(values, 10), deviceSession));
        position.setValid(enabled(value(values, 8)));
        setCoordinates(
                position, value(values, 11), value(values, 12),
                value(values, 13), cleanField(value(values, 14)), false);
        position.setSpeed(UnitsConverter.knotsFromKph(decimal(value(values, 15))));
        position.setCourse(decimal(value(values, 16)));
        position.set(Position.KEY_SATELLITES, integer(value(values, 17)));
        position.setAltitude(decimal(value(values, 18)));
        position.set(Position.KEY_PDOP, decimal(value(values, 19)));
        position.set(Position.KEY_HDOP, decimal(value(values, 20)));
        String operator = value(values, 21);
        position.set("operator", operator);
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

        String alarm = ALARMS.get(value(values, 3));
        if (alarm == null) {
            alarm = ALARMS.get(value(values, 4));
        }
        position.set(Position.KEY_ALARM, alarm);
        position.set("packetStatus", value(values, 3));
        if ("H".equalsIgnoreCase(value(values, 5))) {
            position.set(Position.KEY_ARCHIVE, true);
        }
        position.set("vehicleRegistration", value(values, 7));

        int inputIndex = findInputIndex(values, 33);
        int neighborEnd = inputIndex >= 0 ? inputIndex : Math.min(values.length, 45);
        parseNetwork(position, values, 28, 29, 33, neighborEnd, false, operator);

        if (inputIndex >= 0) {
            position.set(Position.KEY_INPUT, value(values, inputIndex));
            position.set(Position.KEY_OUTPUT, value(values, inputIndex + 1));
            if (inputIndex + 2 < values.length) {
                position.set(Position.KEY_INDEX, integer(cleanField(value(values, inputIndex + 2))));
            }
            if (inputIndex + 3 < values.length) {
                position.set(Position.PREFIX_ADC + 1, decimal(cleanField(value(values, inputIndex + 3))));
            }
            if (inputIndex + 4 < values.length) {
                position.set(Position.PREFIX_ADC + 2, decimal(cleanField(value(values, inputIndex + 4))));
            }
            if (inputIndex + 5 < values.length) {
                position.set(Position.KEY_ODOMETER, decimal(cleanField(value(values, inputIndex + 5))) * 1000);
            }
        } else {
            if (values.length > 45) {
                position.set(Position.KEY_INPUT, value(values, 45));
            }
            if (values.length > 46) {
                position.set(Position.KEY_OUTPUT, value(values, 46));
            }
            if (values.length > 47) {
                position.set(Position.KEY_INDEX, integer(cleanField(value(values, 47))));
            }
            if (values.length > 48) {
                position.set(Position.PREFIX_ADC + 1, decimal(cleanField(value(values, 48))));
            }
            if (values.length > 49) {
                position.set(Position.PREFIX_ADC + 2, decimal(cleanField(value(values, 49))));
            }
            if (values.length > 50) {
                position.set(Position.KEY_ODOMETER, decimal(cleanField(value(values, 50))) * 1000);
            }
        }

        int fuelStart = inputIndex >= 0 ? inputIndex + 6 : 51;
        if (values.length > fuelStart) {
            String tail = String.join(",", Arrays.copyOfRange(values, fuelStart, values.length));
            if (tail.contains("*")) {
                position.set("fuelData", tail.substring(0, tail.indexOf('*')).trim());
                String chk = tail.substring(tail.indexOf('*') + 1).trim();
                if (!chk.isBlank() && !"?".equals(chk)) {
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
            case "CP" -> findImei(values, 7);
            case "EPB" -> findImei(values, 3);
            case "HP" -> findImei(values, 4);
            case "NMP" -> findImei(values, 6);
            case "LGN", "HLP" -> findImei(values, 3);
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

    private static boolean isCommaDelimitedLoginPacket(String[] values) {
        return "$".equals(value(values, 0))
                && (values.length == 9 || values.length == 10)
                && value(values, 2).matches("\\d{15}")
                && value(values, 6).matches("(?i)[NS]")
                && cleanField(value(values, 8)).matches("(?i)[EW]");
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
            if ("CP".equals(header) || "EPB".equals(header) || "HP".equals(header)) {
                String[] shifted = new String[values.length + 1];
                shifted[0] = "$";
                shifted[1] = header;
                System.arraycopy(values, 1, shifted, 2, values.length - 1);
                values = shifted;
                firstField = "$";
            }
        } else if ("$".equals(firstField)
                && ("LGN".equals(header) || "NMP".equals(header) || "HLP".equals(header))) {
            String[] shifted = Arrays.copyOfRange(values, 1, values.length);
            shifted[0] = "$" + header;
            values = shifted;
            firstField = shifted[0];
        }

        String[] finalValues = values;
        Object decoded = switch (header) {
            case "CP" -> decodeLocation(channel, remoteAddress, finalValues);
            case "EPB" -> decodeEmergency(channel, remoteAddress, finalValues);
            case "HP" -> decodeHealth(channel, remoteAddress, finalValues);
            case "NMP" -> decodeNmp(channel, remoteAddress, finalValues);
            case "LGN" -> decodeLgn(channel, remoteAddress, finalValues);
            case "HLP" -> decodeHlp(channel, remoteAddress, finalValues);
            default -> isCommaDelimitedLoginPacket(finalValues)
                    ? decodeLogin(channel, remoteAddress, finalValues) : null;
        };

        if (channel != null && decoded != null) {
            boolean isAsplAny = "ASPL".equalsIgnoreCase(value(finalValues, 1))
                    || "ASPL".equalsIgnoreCase(value(finalValues, 2));
            boolean isCommaDelimitedLogin = isCommaDelimitedLoginPacket(finalValues) && !isAsplAny;
            boolean isLogin = isCommaDelimitedLogin || ("LGN".equals(header) && !isAsplAny);
            if (isLogin) {
                SimpleDateFormat dateFormat = new SimpleDateFormat("ddMMyyyyHHmmss");
                dateFormat.setTimeZone(TimeZone.getTimeZone("UTC"));
                String response = "$LGN," + dateFormat.format(new Date()) + "*\r\n";
                channel.writeAndFlush(new NetworkMessage(response, remoteAddress));
            } else if ("CP".equals(header) || ("EPB".equals(header) && !isAsplAny)) {
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
            } else if ("HP".equals(header) || ("HLP".equals(header) && !isAsplAny)) {
                channel.writeAndFlush(new NetworkMessage("$ACK*\r\n", remoteAddress));
            }
        }

        logDecoded(header, finalValues, decoded);
        return decoded;
    }
}
