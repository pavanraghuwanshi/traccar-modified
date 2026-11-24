/*
 * AIS-140 ATL-140 protocol support.
 */
package org.traccar.protocol;

import io.netty.handler.codec.string.StringDecoder;
import io.netty.handler.codec.string.StringEncoder;
import org.traccar.BaseProtocol;
import org.traccar.PipelineBuilder;
import org.traccar.TrackerServer;
import org.traccar.config.Config;

import java.nio.charset.StandardCharsets;

import jakarta.inject.Inject;

/** Server definition for the comma-separated AIS-140 protocol. */
public class Ais140Protocol extends BaseProtocol {

    @Inject
    public Ais140Protocol(Config config) {
        addServer(new TrackerServer(config, getName(), false) {
            @Override
            protected void addProtocolHandlers(PipelineBuilder pipeline, Config config) {
                pipeline.addLast(new Ais140FrameDecoder());
                pipeline.addLast(new StringEncoder(StandardCharsets.US_ASCII));
                pipeline.addLast(new StringDecoder(StandardCharsets.US_ASCII));
                pipeline.addLast(new Ais140ProtocolDecoder(Ais140Protocol.this));
            }
        });
    }
}
