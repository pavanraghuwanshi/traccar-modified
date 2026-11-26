/*
 * Copyright 2026 Anton Tananaev (anton@traccar.org)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.traccar.protocol;

import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import org.traccar.BaseFrameDecoder;
import org.traccar.helper.BufferUtil;

public class Ais140FrameDecoder extends BaseFrameDecoder {

    private static final int MINIMUM_LENGTH = 15;

    private static boolean isHexDigit(byte b) {
        return (b >= '0' && b <= '9')
                || (b >= 'A' && b <= 'F')
                || (b >= 'a' && b <= 'f');
    }

    private ByteBuf sliceOrSkip(ByteBuf buf, int start, int end, int skip) {
        int length = end - start;
        if (length < MINIMUM_LENGTH) {
            buf.readerIndex(end + skip);
            return null;
        }
        ByteBuf frame = buf.readRetainedSlice(length);
        buf.skipBytes(skip);
        return frame;
    }

    @Override
    protected Object decode(
            ChannelHandlerContext ctx, Channel channel, ByteBuf buf) throws Exception {

        while (buf.isReadable()) {
            while (buf.isReadable() && buf.getByte(buf.readerIndex()) != '$') {
                buf.skipBytes(1);
            }

            if (!buf.isReadable()) {
                return null;
            }

            int start = buf.readerIndex();
            int writer = buf.writerIndex();

            int crlf = BufferUtil.indexOf("\r\n", buf, start, writer);
            int lf = buf.indexOf(start, writer, (byte) '\n');
            int amp = BufferUtil.indexOf("&&", buf, start, writer);
            int nextDollar = buf.indexOf(start + 1, writer, (byte) '$');
            if (nextDollar > start && nextDollar + 1 < writer && buf.getByte(nextDollar + 1) == '*') {
                nextDollar = buf.indexOf(nextDollar + 1, writer, (byte) '$');
            }

            int bestEnd = -1;
            int skip = 0;

            if (crlf >= 0) {
                bestEnd = crlf;
                skip = 2;
            }
            if (lf >= 0 && (bestEnd < 0 || lf < bestEnd)) {
                bestEnd = lf;
                skip = 1;
            }
            if (amp >= 0 && (bestEnd < 0 || amp < bestEnd)) {
                bestEnd = amp;
                skip = 2;
            }
            if (nextDollar > start && (bestEnd < 0 || nextDollar < bestEnd)) {
                bestEnd = nextDollar;
                skip = 0;
            }

            if (bestEnd >= 0) {
                ByteBuf frame = sliceOrSkip(buf, start, bestEnd, skip);
                if (frame != null) {
                    return frame;
                }
                continue;
            }

            int searchFrom = start;
            while (searchFrom < writer) {
                int star = buf.indexOf(searchFrom, writer, (byte) '*');
                if (star < 0) {
                    break;
                }
                int afterStar = writer - (star + 1);
                if (afterStar == 0) {
                    if (star - start >= 2 && buf.getByte(star - 2) == ',') {
                        byte prev = buf.getByte(star - 1);
                        if (prev < 0x20 || prev >= 0x7F) {
                            ByteBuf frame = sliceOrSkip(buf, start, star - 1, 2);
                            if (frame != null) {
                                return frame;
                            }
                            break;
                        }
                    }
                    ByteBuf frame = sliceOrSkip(buf, start, writer, 0);
                    if (frame != null) {
                        return frame;
                    }
                    break;
                }

                byte b1 = buf.getByte(star + 1);
                if (b1 == '*') {
                    if (afterStar == 1) {
                        ByteBuf frame = sliceOrSkip(buf, start, writer, 0);
                        if (frame != null) {
                            return frame;
                        }
                        break;
                    }
                    if (afterStar == 2 && buf.getByte(star + 2) == '\r') {
                        ByteBuf frame = sliceOrSkip(buf, start, star + 2, 1);
                        if (frame != null) {
                            return frame;
                        }
                        break;
                    }
                } else if (b1 == '\r' && afterStar == 1) {
                    ByteBuf frame = sliceOrSkip(buf, start, star + 1, 1);
                    if (frame != null) {
                        return frame;
                    }
                    break;
                } else if (afterStar == 1 && isHexDigit(b1)) {
                    // Wait for the second checksum hex digit or newline to avoid splitting *XX.
                    return null;
                } else if (afterStar >= 2 && isHexDigit(b1) && isHexDigit(buf.getByte(star + 2))) {
                    if (afterStar == 2) {
                        ByteBuf frame = sliceOrSkip(buf, start, writer, 0);
                        if (frame != null) {
                            return frame;
                        }
                        break;
                    }
                    if (afterStar == 3 && buf.getByte(star + 3) == '\r') {
                        ByteBuf frame = sliceOrSkip(buf, start, star + 3, 1);
                        if (frame != null) {
                            return frame;
                        }
                        break;
                    }
                }

                searchFrom = star + 1;
            }

            return null;
        }

        return null;
    }
}
