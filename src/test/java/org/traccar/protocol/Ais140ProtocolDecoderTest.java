package org.traccar.protocol;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.string.StringDecoder;
import io.netty.handler.codec.string.StringEncoder;
import org.junit.jupiter.api.Test;
import org.traccar.NetworkMessage;
import org.traccar.ProtocolTest;
import org.traccar.WrapperInboundHandler;
import org.traccar.WrapperOutboundHandler;
import org.traccar.config.Config;
import org.traccar.config.Keys;
import org.traccar.handler.network.NetworkMessageHandler;
import org.traccar.model.Device;
import org.traccar.model.Position;
import org.traccar.session.cache.CacheManager;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketAddress;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Map;
import java.util.TimeZone;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class Ais140ProtocolDecoderTest extends ProtocolTest {

    private static final SocketAddress REMOTE_ADDRESS = new InetSocketAddress("127.0.0.1", 5270);

    private NetworkMessage readNetworkMessage(EmbeddedChannel channel) {
        Object msg;
        while ((msg = channel.readOutbound()) != null) {
            if (msg instanceof NetworkMessage nm) {
                return nm;
            }
        }
        return null;
    }

    @Test
    public void testDecode() throws Exception {

        var decoder = inject(new Ais140ProtocolDecoder(null));

        String location = "$,CP,ASPL,V1.1.1,IN,07,L,864495039252728,DL1RQ2455,1,21082018,102209,"
                + "28.359692,N,76.927437,E,0.0,36.00,8,269.0,1.2,1.7,Airtel,1,1,12.2,4.0,0,C,25,"
                + "404,10,209,c342,6d17,209,24,d2fb,209,20,d2fc,209,19,c344,209,19,0111,00,"
                + "002165,125.4,A,t=25 N=12.5,?";

        verifyPosition(decoder, text(location));
        verifyAttribute(decoder, text(location), Position.KEY_IGNITION, true);
        verifyAttribute(decoder, text(location), Position.KEY_ODOMETER, 125400.0);
        verifyAttribute(decoder, text(location), "fenceId", "A");
        verifyAttribute(decoder, text(location), "fuelData", "t=25 N=12.5");

        verifyPosition(decoder, text(
                "$,EPB,EMR,864495039252728,NM,21082018104217,A,28.359692,N,76.927437,E,269,0.0,"
                        + "0.00,G,DL1RQ2455,0"));

        verifyAttribute(decoder, text(
                "$,HP,ASPL,V1.1.1,864495039252728,100,15,12,10,60,1111,11.6,7.5"),
                Position.KEY_BATTERY_LEVEL, 100);

        verifyPosition(decoder, text(
                "$,GA01AA1234,864495039240301,V1.1.2,VERSION1.0,28.359791,N,76.927086,E"));
    }

    @Test
    public void testFullPipelineAndAckResponses() throws Exception {
        var decoder = inject(new Ais140ProtocolDecoder(null));
        var channel = new EmbeddedChannel(
                new WrapperInboundHandler(new Ais140FrameDecoder()),
                new WrapperInboundHandler(new StringDecoder(StandardCharsets.US_ASCII)),
                decoder
        );

        // 1. Test Login packet without '*' followed immediately by Location packet
        String loginAndLocationStream =
                "$,GA01AA1234,864495039240301,V1.1.2,VERSION1.0,28.359791,N,76.927086,E\r\n"
                + "$,CP,ASPL,V1.1.1,NR,01,L,864495039240301,GA01AA1234,1,21082018,102209,"
                + "28.359692,N,76.927437,E,25.5,36.00,8,269.0,1.2,1.7,Airtel,1,1,12.2,4.0,0,C,25,"
                + "404,10,209,c342,6d17,209,24,d2fb,209,20,d2fc,209,19,c344,209,19,0111,00,"
                + "002165,125.4,A,t=25 N=12.5,*";

        channel.writeInbound(new NetworkMessage(
                Unpooled.copiedBuffer(loginAndLocationStream, StandardCharsets.US_ASCII), REMOTE_ADDRESS));

        // Verify Position 1 (Login)
        Position loginPos = channel.readInbound();
        assertNotNull(loginPos, "Login position should be decoded");
        assertEquals("GA01AA1234", loginPos.getAttributes().get("vehicleRegistration"));

        // Verify Position 2 (Location)
        Position locPos = channel.readInbound();
        assertNotNull(locPos, "Location position should be decoded");
        assertEquals(28.359692, locPos.getLatitude(), 0.0001);
        assertEquals(76.927437, locPos.getLongitude(), 0.0001);
        assertEquals(125400.0, locPos.getAttributes().get(Position.KEY_ODOMETER));
        assertEquals("A", locPos.getAttributes().get("fenceId"));

        // Verify Server ACKs sent to channel
        NetworkMessage loginAck = readNetworkMessage(channel);
        assertNotNull(loginAck, "Server should send ACK for Login");
        assertTrue(((String) loginAck.getMessage()).startsWith("$LGN,"));

        NetworkMessage locAck = readNetworkMessage(channel);
        assertNotNull(locAck, "Server should send ACK for Location");
        assertEquals("$ACK,002165*\r\n", locAck.getMessage());
    }

    @Test
    public void testFragmentedStreamReassembly() throws Exception {
        var decoder = inject(new Ais140ProtocolDecoder(null));
        var channel = new EmbeddedChannel(
                new WrapperInboundHandler(new Ais140FrameDecoder()),
                new WrapperInboundHandler(new StringDecoder(StandardCharsets.US_ASCII)),
                decoder
        );

        String fullPacket = "$,CP,ASPL,V1.1.1,NR,01,L,864495039240301,GA01AA1234,1,21082018,102209,"
                + "28.359692,N,76.927437,E,25.5,36.00,8,269.0,1.2,1.7,Airtel,1,1,12.2,4.0,0,C,25,"
                + "404,10,209,c342,6d17,209,24,d2fb,209,20,d2fc,209,19,c344,209,19,0111,00,"
                + "000045,10.0,A,t=30 N=10.0,*\r\n";

        // Split packet in half to simulate network fragmentation
        int half = fullPacket.length() / 2;
        String part1 = fullPacket.substring(0, half);
        String part2 = fullPacket.substring(half);

        channel.writeInbound(new NetworkMessage(
                Unpooled.copiedBuffer(part1, StandardCharsets.US_ASCII), REMOTE_ADDRESS));
        // No packet yet
        assertTrue(channel.inboundMessages().isEmpty(), "Partial chunk should not produce position");

        channel.writeInbound(new NetworkMessage(
                Unpooled.copiedBuffer(part2, StandardCharsets.US_ASCII), REMOTE_ADDRESS));
        // Packet reassembled
        Position pos = channel.readInbound();
        assertNotNull(pos, "Reassembled packet should be decoded");
        assertEquals(28.359692, pos.getLatitude(), 0.0001);
        assertEquals(10000.0, pos.getAttributes().get(Position.KEY_ODOMETER));

        NetworkMessage ack = readNetworkMessage(channel);
        assertNotNull(ack);
        assertEquals("$ACK,000045*\r\n", ack.getMessage());
    }

    @Test
    public void testPacketWithoutNeighborCells() throws Exception {
        var decoder = inject(new Ais140ProtocolDecoder(null));
        var channel = new EmbeddedChannel(
                new WrapperInboundHandler(new Ais140FrameDecoder()),
                new WrapperInboundHandler(new StringDecoder(StandardCharsets.US_ASCII)),
                decoder
        );

        // Packet where NMR is missing/empty (only MCC, MNC, LAC, CellID followed immediately by inputs)
        String noNmrPacket = "$,CP,ASPL,V1.1.1,NR,01,L,864495039240301,GA01AA1234,1,21082018,102209,"
                + "28.359692,N,76.927437,E,0.0,0.0,8,250.0,1.0,1.0,Airtel,1,1,12.0,4.0,0,C,20,"
                + "404,10,209,c342,0111,00,000100,55.5,B,*\r\n";

        channel.writeInbound(new NetworkMessage(
                Unpooled.copiedBuffer(noNmrPacket, StandardCharsets.US_ASCII), REMOTE_ADDRESS));
        Position pos = channel.readInbound();
        assertNotNull(pos, "Packet without NMR should be decoded dynamically");
        assertEquals("0111", pos.getAttributes().get(Position.KEY_INPUT));
        assertEquals("00", pos.getAttributes().get(Position.KEY_OUTPUT));
        assertEquals(55500.0, pos.getAttributes().get(Position.KEY_ODOMETER));
        assertEquals("B", pos.getAttributes().get("fenceId"));

        NetworkMessage ack = readNetworkMessage(channel);
        assertNotNull(ack);
        assertEquals("$ACK,000100*\r\n", ack.getMessage());
    }

    @Test
    public void testLiveTcpSocket() throws Exception {
        EventLoopGroup bossGroup = new NioEventLoopGroup(1);
        EventLoopGroup workerGroup = new NioEventLoopGroup(1);
        try {
            ServerBootstrap b = new ServerBootstrap();
            b.group(bossGroup, workerGroup)
             .channel(NioServerSocketChannel.class)
             .childHandler(new ChannelInitializer<SocketChannel>() {
                 @Override
                 protected void initChannel(SocketChannel ch) throws Exception {
                     ch.pipeline().addLast(new NetworkMessageHandler());
                     ch.pipeline().addLast(new WrapperOutboundHandler(new StringEncoder(StandardCharsets.US_ASCII)));
                     ch.pipeline().addLast(new WrapperInboundHandler(new Ais140FrameDecoder()));
                     ch.pipeline().addLast(new WrapperInboundHandler(new StringDecoder(StandardCharsets.US_ASCII)));
                     ch.pipeline().addLast(inject(new Ais140ProtocolDecoder(null)));
                 }
             });

            Channel serverChannel = b.bind(0).sync().channel();
            int port = ((InetSocketAddress) serverChannel.localAddress()).getPort();

            try (Socket socket = new Socket("127.0.0.1", port)) {
                socket.setSoTimeout(5000);
                OutputStream out = socket.getOutputStream();
                BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII));

                // 1. Send Login via live TCP socket
                out.write("$,GA01AA1234,864495039240301,V1.1.2,VERSION1.0,28.359791,N,76.927086,E\r\n".getBytes(StandardCharsets.US_ASCII));
                out.flush();

                String loginResponse = in.readLine();
                assertNotNull(loginResponse, "Live socket should receive login response");
                assertTrue(loginResponse.startsWith("$LGN,"), "Login response format check: " + loginResponse);

                // 2. Send Location via live TCP socket
                out.write(("$,CP,ASPL,V1.1.1,NR,01,L,864495039240301,GA01AA1234,1,21082018,102209,"
                        + "28.359692,N,76.927437,E,25.5,36.00,8,269.0,1.2,1.7,Airtel,1,1,12.2,4.0,0,C,25,"
                        + "404,10,209,c342,6d17,209,24,d2fb,209,20,d2fc,209,19,c344,209,19,0111,00,"
                        + "002165,125.4,A,t=25 N=12.5,*\r\n").getBytes(StandardCharsets.US_ASCII));
                out.flush();

                String locResponse = in.readLine();
                assertNotNull(locResponse, "Live socket should receive location ACK");
                assertEquals("$ACK,002165*", locResponse, "Location ACK format check: " + locResponse);
            } finally {
                serverChannel.close().sync();
            }
        } finally {
            bossGroup.shutdownGracefully().sync();
            workerGroup.shutdownGracefully().sync();
        }
    }

    @Test
    public void testAsplNmpLgnHlp() throws Exception {
        var decoder = inject(new Ais140ProtocolDecoder(null));
        var channel = new EmbeddedChannel(
                new WrapperInboundHandler(new Ais140FrameDecoder()),
                new WrapperInboundHandler(new StringDecoder(StandardCharsets.US_ASCII)),
                decoder
        );

        String asplStream =
                "$LGN,ASPL,0,865820071990100,2.1.1,AIS140,19.9638977,N,79.2579193,E*3F\r\n"
                + "$HLP,ASPL,2.1.1,865820071990100,79,15,34,10,30,010000,00*\r\n"
                + "$NMP,ASPL,2.1.1,NR,2,H,865820071990100,0,1,26112026,023521,19.9911137,N,79.2525101,E,"
                + "020.0,312.00,31,182.0,1.1,0.5,Vi India,1,1,13.6,4.1,0,C,24,404,90,0dca,8c0f,17,0dca,"
                + "a9d7,22,0dca,8c13,x,x,x,x,x,x,1100,00,140943,00.0,00.0,4471,(0,0,0)*15\r\n"
                + "$NMP,ASPL,2.1.1,NR,1,L,863070047999262,0,1,26112026,023453,20.7332458,N,78.5821838,E,"
                + "017.1,094.00,31,273.0,1.5,0.7,Airtel,1,1,13.5,2.7,0,C,22,404,90,0c90,10df,15,0c90,"
                + "e6b2,x,x,x,x,x,x,x,x,x,1000,00,030160,00.0,00.0,1940,(0,0,0)*1B\r\n";

        channel.writeInbound(new NetworkMessage(
                Unpooled.copiedBuffer(asplStream, StandardCharsets.US_ASCII), REMOTE_ADDRESS));

        Position lgnPos = channel.readInbound();
        assertNotNull(lgnPos);
        assertFalse(lgnPos.getOutdated());
        assertEquals(19.9638977, lgnPos.getLatitude(), 0.0001);
        assertEquals(79.2579193, lgnPos.getLongitude(), 0.0001);

        Position hlpPos = channel.readInbound();
        assertNotNull(hlpPos);
        assertTrue(hlpPos.getOutdated());
        assertEquals(79, hlpPos.getAttributes().get(Position.KEY_BATTERY_LEVEL));

        Position nmpHist = channel.readInbound();
        assertNotNull(nmpHist);
        assertTrue(Boolean.TRUE.equals(nmpHist.getAttributes().get(Position.KEY_ARCHIVE)));
        assertEquals(140943, nmpHist.getAttributes().get(Position.KEY_INDEX));
        assertEquals(4471000.0, nmpHist.getAttributes().get(Position.KEY_ODOMETER));
        assertEquals("(0,0,0)", nmpHist.getAttributes().get("fuelData"));
        assertEquals("15", nmpHist.getAttributes().get("checksum"));

        Position nmpLive = channel.readInbound();
        assertNotNull(nmpLive);
        assertFalse(nmpLive.getAttributes().containsKey(Position.KEY_ARCHIVE));
        assertEquals(30160, nmpLive.getAttributes().get(Position.KEY_INDEX));
        assertEquals(1940000.0, nmpLive.getAttributes().get(Position.KEY_ODOMETER));
        assertEquals("(0,0,0)", nmpLive.getAttributes().get("fuelData"));
        assertEquals("1B", nmpLive.getAttributes().get("checksum"));

        assertNull(readNetworkMessage(channel), "ASPL LGN/HLP/NMP should not trigger server replies");
    }

    @Test
    public void testLiveCom3NmpInvalidGpsAndNetworkTowers() throws Exception {
        var decoder = inject(new Ais140ProtocolDecoder(null));
        var channel = new EmbeddedChannel(
                new WrapperInboundHandler(new Ais140FrameDecoder()),
                new WrapperInboundHandler(new StringDecoder(StandardCharsets.US_ASCII)),
                decoder
        );

        String com3Packet = "$NMP,ASPL,2.1.1,NR,1,L,863070045877510,0,0,01011970,000000,"
                + "00.0000000,N,00.0000000,E,000.0,000.00,00,0.0,0.0,0.0,Airtel,0,1,11.9,3.4,0,O,18,"
                + "404,90,158a,e25e,15,1585,6366,11,1585,9eb8,10,158a,e263,13,158a,e267,"
                + "0000,00,818981,00.0,00.0,20929,(0,0,0)*02\r\r\n";

        channel.writeInbound(new NetworkMessage(
                Unpooled.copiedBuffer(com3Packet, StandardCharsets.US_ASCII), REMOTE_ADDRESS));

        Position pos = channel.readInbound();
        assertNotNull(pos);
        assertFalse(pos.getValid());
        assertFalse(pos.getOutdated());
        assertEquals(0L, pos.getFixTime().getTime());
        assertEquals(0.0, pos.getLatitude(), 0.0001);
        assertEquals(0.0, pos.getLongitude(), 0.0001);
        assertNotNull(pos.getNetwork());
        assertEquals(5, pos.getNetwork().getCellTowers().size());
        assertEquals(11.9, pos.getAttributes().get(Position.KEY_POWER));
        assertEquals(3.4, pos.getAttributes().get(Position.KEY_BATTERY));
        assertEquals(818981, pos.getAttributes().get(Position.KEY_INDEX));
        assertEquals(20929000.0, pos.getAttributes().get(Position.KEY_ODOMETER));
        assertEquals("02", pos.getAttributes().get("checksum"));
        assertNull(readNetworkMessage(channel));
    }

    @Test
    public void testAsplNmpAlarmHlpOutputAndTowerHexScan() throws Exception {
        var decoder = inject(new Ais140ProtocolDecoder(null));
        var channel = new EmbeddedChannel(
                new WrapperInboundHandler(new Ais140FrameDecoder()),
                new WrapperInboundHandler(new StringDecoder(StandardCharsets.US_ASCII)),
                decoder
        );

        String tamperPacket = "$NMP,ASPL,2.1.1,TA,16,H,863070047999262,0,0,26112026,021630,"
                + "00.0000000,N,00.0000000,E,000.0,000.00,00,0.0,0.0,0.0,Airtel,1,1,13.5,0.0,0,C,0,"
                + "x,x,x,x,x,x,x,x,x,x,x,x,x,x,x,x,1000,00,030123,00.0,00.0,1937,(0,0,0)*52\r\n";
        String hlpPacket = "$HLP,ASPL,2.1.1,863070047999262,0,15,0,30,60,100000,00*\r\n";
        String hexCollisionNmp = "$NMP,ASPL,2.1.1,NR,1,L,863070047999262,0,1,26112026,021752,"
                + "20.7414360,N,78.5699463,E,000.0,000.00,08,233.0,2.6,1.1,Airtel,1,1,13.5,0.0,0,C,18,"
                + "404,90,0c90,5c78,15,0c90,1010,11,0c90,1633,09,0c90,d010,12,0c90,f139,"
                + "2000,00,030126,12.5,04.2,1937,(0,0,0)*6A\r\n";

        channel.writeInbound(new NetworkMessage(
                Unpooled.copiedBuffer(tamperPacket + hlpPacket + hexCollisionNmp, StandardCharsets.US_ASCII),
                REMOTE_ADDRESS));

        Position taPos = channel.readInbound();
        assertNotNull(taPos);
        assertEquals(Position.ALARM_TAMPERING, taPos.getAttributes().get(Position.KEY_ALARM));
        assertTrue(Boolean.TRUE.equals(taPos.getAttributes().get(Position.KEY_ARCHIVE)));
        assertFalse(taPos.getValid());
        assertFalse(taPos.getOutdated());
        SimpleDateFormat fmt = new SimpleDateFormat("ddMMyyyyHHmmss");
        fmt.setTimeZone(TimeZone.getTimeZone("UTC"));
        assertEquals("26112026021630", fmt.format(taPos.getFixTime()));
        assertNull(taPos.getNetwork());
        assertEquals(30123, taPos.getAttributes().get(Position.KEY_INDEX));
        assertEquals(1937000.0, taPos.getAttributes().get(Position.KEY_ODOMETER));

        Position hlpPos = channel.readInbound();
        assertNotNull(hlpPos);
        assertTrue(hlpPos.getOutdated());
        assertEquals("100000", hlpPos.getAttributes().get(Position.KEY_INPUT));
        assertEquals("00", hlpPos.getAttributes().get(Position.KEY_OUTPUT));
        assertFalse(hlpPos.hasAttribute(Position.KEY_POWER));

        Position hexPos = channel.readInbound();
        assertNotNull(hexPos);
        assertEquals("2000", hexPos.getAttributes().get(Position.KEY_INPUT));
        assertEquals("00", hexPos.getAttributes().get(Position.KEY_OUTPUT));
        assertEquals(30126, hexPos.getAttributes().get(Position.KEY_INDEX));
        assertEquals(12.5, hexPos.getAttributes().get(Position.PREFIX_ADC + 1));
        assertEquals(4.2, hexPos.getAttributes().get(Position.PREFIX_ADC + 2));
        assertEquals(1937000.0, hexPos.getAttributes().get(Position.KEY_ODOMETER));
        assertNotNull(hexPos.getNetwork());
        assertEquals(5, hexPos.getNetwork().getCellTowers().size());
        assertNull(readNetworkMessage(channel));
    }

    @Test
    public void testFrameDecoderShortFramesAmpersandAndFragmentedChecksum() throws Exception {
        var decoder = inject(new Ais140ProtocolDecoder(null));
        var channel = new EmbeddedChannel(
                new WrapperInboundHandler(new Ais140FrameDecoder()),
                new WrapperInboundHandler(new StringDecoder(StandardCharsets.US_ASCII)),
                decoder
        );

        // Standalone && frame must be consumed immediately and dropped without reply
        channel.writeInbound(new NetworkMessage(
                Unpooled.copiedBuffer("$863070047999262,WRONG COMMAND&&", StandardCharsets.US_ASCII),
                REMOTE_ADDRESS));
        assertNull(channel.readInbound());
        assertNull(readNetworkMessage(channel));

        // 150 short <15B frames must not wedge or trigger TooLongFrameException (>1024B)
        StringBuilder shortBurst = new StringBuilder();
        for (int i = 0; i < 150; i++) {
            shortBurst.append("$HBT*1A\r\n");
        }
        shortBurst.append("$ACK*\r\n$OK&&");
        channel.writeInbound(new NetworkMessage(
                Unpooled.copiedBuffer(shortBurst.toString(), StandardCharsets.US_ASCII), REMOTE_ADDRESS));
        assertNull(channel.readInbound());

        // Fragmented *6 + A\r\n and *6A + \r\n must preserve checksum
        String nmpBody = "$NMP,ASPL,2.1.1,NR,1,L,863070047999262,0,1,26112026,021752,"
                + "20.7414360,N,78.5699463,E,000.0,000.00,08,233.0,2.6,1.1,Airtel,1,1,13.5,0.0,0,C,18,"
                + "404,90,0c90,5c78,15,0c90,1355,11,0c90,1633,09,0c90,d010,12,0c90,f139,"
                + "1000,00,030126,00.0,00.0,1937,(0,0,0)";
        channel.writeInbound(new NetworkMessage(
                Unpooled.copiedBuffer(nmpBody + "*6", StandardCharsets.US_ASCII), REMOTE_ADDRESS));
        assertNull(channel.readInbound());
        channel.writeInbound(new NetworkMessage(
                Unpooled.copiedBuffer("A\r\n", StandardCharsets.US_ASCII), REMOTE_ADDRESS));
        Position frag1 = channel.readInbound();
        assertNotNull(frag1);
        assertEquals("6A", frag1.getAttributes().get("checksum"));

        // Trailing *XX without \r\n must preserve checksum, and leftover \r\n must be skipped cleanly
        channel.writeInbound(new NetworkMessage(
                Unpooled.copiedBuffer(nmpBody + "*3E", StandardCharsets.US_ASCII), REMOTE_ADDRESS));
        Position frag2 = channel.readInbound();
        assertNotNull(frag2);
        assertEquals("3E", frag2.getAttributes().get("checksum"));
        channel.writeInbound(new NetworkMessage(
                Unpooled.copiedBuffer("\r\n", StandardCharsets.US_ASCII), REMOTE_ADDRESS));
        assertNull(channel.readInbound());

        // Trailing $...* without \r\n and ,?* without stripping '?'
        String hlpTrailing = "$HLP,ASPL,2.1.1,863070047999262,75,15,0,30,60,100000,00*";
        channel.writeInbound(new NetworkMessage(
                Unpooled.copiedBuffer(hlpTrailing, StandardCharsets.US_ASCII), REMOTE_ADDRESS));
        Position hlpPos = channel.readInbound();
        assertNotNull(hlpPos);
        assertEquals(75, hlpPos.getAttributes().get(Position.KEY_BATTERY_LEVEL));

        String cpQuestionStar = "$,CP,ASPL,V1.1.1,IN,07,L,864495039252728,DL1RQ2455,1,21082018,102209,"
                + "28.359692,N,76.927437,E,0.0,36.00,8,269.0,1.2,1.7,Airtel,1,1,12.2,4.0,0,C,25,"
                + "404,10,209,c342,6d17,209,24,d2fb,209,20,d2fc,209,19,c344,209,19,0111,00,"
                + "002165,125.4,A,t=25 N=12.5,?*";
        channel.writeInbound(new NetworkMessage(
                Unpooled.copiedBuffer(cpQuestionStar, StandardCharsets.US_ASCII), REMOTE_ADDRESS));
        Position cpPos = channel.readInbound();
        assertNotNull(cpPos);
        assertEquals("t=25 N=12.5", cpPos.getAttributes().get("fuelData"));
        assertFalse(cpPos.getAttributes().containsKey("checksum"));
    }

    @Test
    public void testUnhandledHeadersBadCoordsEpbDateAndTimezone() throws Exception {
        var decoder = inject(new Ais140ProtocolDecoder(null));
        var channel = new EmbeddedChannel(
                new WrapperInboundHandler(new Ais140FrameDecoder()),
                new WrapperInboundHandler(new StringDecoder(StandardCharsets.US_ASCII)),
                decoder
        );

        // Unhandled >=8-field packets ($PVT, $,01) must not crash or trigger $LGN reply
        String pvtPacket = "$PVT,ASPL,2.1.1,NR,01,L,DL1RQ2455,863070047999262,1,26112026,021752,"
                + "20.7414360,N,78.5699463,E*\r\n";
        String numberedPacket = "$,01,ASPL,2.1.1,NR,01,L,863070047999262,DL1RQ2455,1,26112026,021752,"
                + "20.7414360,N,78.5699463,E*\r\n";
        channel.writeInbound(new NetworkMessage(
                Unpooled.copiedBuffer(pvtPacket + numberedPacket, StandardCharsets.US_ASCII), REMOTE_ADDRESS));
        assertNull(channel.readInbound());
        assertNull(readNetworkMessage(channel));

        // DDMM.MMMM / out-of-range coordinates must not throw IllegalArgumentException
        String badCoordsNmp = "$NMP,ASPL,2.1.1,NR,1,L,863070047999262,0,1,26112026,021752,"
                + "2044.4861,N,07834.1967,E,000.0,000.00,08,233.0,2.6,1.1,Airtel,1,1,13.5,0.0,0,C,18,"
                + "404,90,0c90,5c78,1000,00,030126,00.0,00.0,1937,(0,0,0)*6A\r\n";
        channel.writeInbound(new NetworkMessage(
                Unpooled.copiedBuffer(badCoordsNmp, StandardCharsets.US_ASCII), REMOTE_ADDRESS));
        Position badCoordPos = channel.readInbound();
        assertNotNull(badCoordPos);
        assertFalse(badCoordPos.getValid());

        // 9-field $LGN without trailing hemisphere rejected; 9-field no-vendor $LGN decoded safely
        assertNull(decoder.decode(null, REMOTE_ADDRESS,
                "$LGN,ASPL,0,863070047999262,2.1.1,AIS140,20.7414570,N,78.5699921"));
        Position noVendorLgn = (Position) decoder.decode(null, REMOTE_ADDRESS,
                "$LGN,DL1RQ2455,863070047999262,2.1.1,AIS140,20.7414570,N,78.5699921,E*36");
        assertNotNull(noVendorLgn);
        assertEquals(20.7414570, noVendorLgn.getLatitude(), 0.0001);
        assertEquals(78.5699921, noVendorLgn.getLongitude(), 0.0001);

        // EPB with invalid date sets non-null fixTime; ASPL EPB sends no ACK
        channel.writeInbound(new NetworkMessage(Unpooled.copiedBuffer(
                "$EPB,ASPL,EMR,863070047999262,NM,INVALID,A,20.7414,N,78.5699,E,233,0.0,0.00,G,DL1RQ2455,0*3E\r\n",
                StandardCharsets.US_ASCII), REMOTE_ADDRESS));
        Position epbPos = channel.readInbound();
        assertNotNull(epbPos);
        assertNotNull(epbPos.getFixTime());
        assertTrue(epbPos.getValid());
        assertEquals(20.7414, epbPos.getLatitude(), 0.0001);
        assertEquals(78.5699, epbPos.getLongitude(), 0.0001);
        assertEquals(Position.ALARM_SOS, epbPos.getAttributes().get(Position.KEY_ALARM));
        assertNull(readNetworkMessage(channel));

        // DECODER_TIMEZONE honored by parseDateTime
        var istDecoder = inject(new Ais140ProtocolDecoder(null));
        var device = mock(Device.class);
        when(device.getId()).thenReturn(1L);
        when(device.getAttributes()).thenReturn(Map.of(Keys.DECODER_TIMEZONE.getKey(), "Asia/Kolkata"));
        var cacheManager = mock(CacheManager.class);
        when(cacheManager.getConfig()).thenReturn(new Config());
        when(cacheManager.getObject(eq(Device.class), anyLong())).thenReturn(device);
        istDecoder.setCacheManager(cacheManager);

        Position istPos = (Position) istDecoder.decode(null, REMOTE_ADDRESS,
                "$NMP,ASPL,2.1.1,NR,1,L,863070047999262,0,1,26112026,080521,"
                + "20.7414360,N,78.5699463,E,000.0,000.00,08,233.0,2.6,1.1,Airtel,1,1,13.5,0.0,0,C,18,"
                + "404,90,0c90,5c78,1000,00,030126,00.0,00.0,1937,(0,0,0)*6A");
        assertNotNull(istPos);
        SimpleDateFormat utcFmt = new SimpleDateFormat("ddMMyyyyHHmmss");
        utcFmt.setTimeZone(TimeZone.getTimeZone("UTC"));
        assertEquals("26112026023521", utcFmt.format(istPos.getFixTime()));
    }
}
