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
import org.traccar.handler.network.NetworkMessageHandler;
import org.traccar.model.Position;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketAddress;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
}
