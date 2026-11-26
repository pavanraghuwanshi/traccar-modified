package org.traccar.handler;

import org.junit.jupiter.api.Test;
import org.traccar.config.Config;
import org.traccar.config.Keys;
import org.traccar.model.Position;
import org.traccar.session.cache.CacheManager;

import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class DistanceHandlerTest {

    @Test
    public void testCalculateDistance() {

        DistanceHandler distanceHandler = new DistanceHandler(new Config(), mock(CacheManager.class));

        Position position = new Position();
        distanceHandler.handlePosition(position, p -> {});

        assertEquals(0.0, position.getAttributes().get(Position.KEY_DISTANCE));
        assertEquals(0.0, position.getAttributes().get(Position.KEY_TOTAL_DISTANCE));

        position.set(Position.KEY_DISTANCE, 100);

        distanceHandler.handlePosition(position, p -> {});

        assertEquals(100.0, position.getAttributes().get(Position.KEY_DISTANCE));
        assertEquals(100.0, position.getAttributes().get(Position.KEY_TOTAL_DISTANCE));

    }

    @Test
    public void testOutOfOrderPositionDoesNotJumpDistanceOrFilterCoordinates() {
        Config config = new Config();
        config.setString(Keys.COORDINATES_FILTER, "true");
        config.setString(Keys.COORDINATES_MAX_ERROR, "1000");

        CacheManager cacheManager = mock(CacheManager.class);
        Position last = new Position();
        last.setDeviceId(2L);
        last.setValid(true);
        last.setTime(new Date(1_700_003_600_000L));
        last.setLatitude(19.97082);
        last.setLongitude(79.25768);
        last.set(Position.KEY_TOTAL_DISTANCE, 134140.90);
        when(cacheManager.getPosition(2L)).thenReturn(last);

        DistanceHandler distanceHandler = new DistanceHandler(config, cacheManager);

        Position archived = new Position();
        archived.setDeviceId(2L);
        archived.setValid(true);
        archived.setTime(new Date(1_700_000_000_000L));
        archived.setLatitude(19.9908447);
        archived.setLongitude(79.2648621);

        distanceHandler.handlePosition(archived, p -> {});

        assertEquals(0.0, archived.getDouble(Position.KEY_DISTANCE));
        assertEquals(134140.90, archived.getDouble(Position.KEY_TOTAL_DISTANCE));
        assertEquals(19.9908447, archived.getLatitude(), 0.000001);
        assertEquals(79.2648621, archived.getLongitude(), 0.000001);
    }

}
