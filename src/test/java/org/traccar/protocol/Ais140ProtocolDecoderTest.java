package org.traccar.protocol;

import org.junit.jupiter.api.Test;
import org.traccar.ProtocolTest;
import org.traccar.model.Position;

public class Ais140ProtocolDecoderTest extends ProtocolTest {

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

        verifyPosition(decoder, text(
                "$,EPB,EMR,864495039252728,NM,21082018104217,A,28.359692,N,76.927437,E,269,0.0,"
                        + "0.00,G,DL1RQ2455,0"));
        verifyAttribute(decoder, text(
                "$,HP,ASPL,V1.1.1,864495039252728,100,15,12,10,60,1111,11.6,7.5"),
                Position.KEY_BATTERY_LEVEL, 100);
    }
}
