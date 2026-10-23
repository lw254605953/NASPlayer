/*
 * Copyright (c) 2007 Benjamin Zores <ben@geexbox.org>
 * Copyright 2026 MegatronPlayer contributors
 * APE header/packet rules adapted from FFmpeg 6.1.4 libavformat/ape.c.
 * SPDX-License-Identifier: LGPL-2.1-or-later
 *
 * This file may be redistributed and modified under the GNU Lesser General
 * Public License, version 2.1 or later. It is provided without warranty.
 * See decoder-ffmpeg/COPYING.LGPLv2.1 for the full license.
 */
package androidx.media3.decoder.ffmpeg;

import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.ParserException;
import androidx.media3.common.util.ParsableByteArray;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.extractor.Extractor;
import androidx.media3.extractor.ExtractorInput;
import androidx.media3.extractor.ExtractorOutput;
import androidx.media3.extractor.PositionHolder;
import androidx.media3.extractor.SeekMap;
import androidx.media3.extractor.SeekPoint;
import androidx.media3.extractor.TrackOutput;
import java.io.EOFException;
import java.io.IOException;
import java.util.Arrays;

/**
 * Streams Monkey's Audio 3.80-3.99 files to the FFmpeg audio renderer.
 *
 * <p>The file's own seek table supplies exact byte positions. Only one compressed frame is buffered;
 * all bytes, including seeks, are read through the caller's Media3 data source. This class opens no
 * sockets and has no knowledge of WebDAV addresses or credentials.
 *
 * <p>Packet layout follows the public APE format: little-endian block count and alignment skip,
 * then the aligned compressed frame. FFmpeg extradata is version/compression/flags (six bytes).
 * A separate initialization-data entry carries coded bit depth for the JNI codec context.
 */
@UnstableApi
public final class ApeExtractor implements Extractor {
  public static final String MIME_TYPE = "audio/ape";
  private static final int MAX_HEADER_BYTES = 16 * 1024 * 1024;
  private static final int MAX_FRAMES = 1_000_000;
  private static final int MAX_PACKET_BYTES = 32 * 1024 * 1024;
  private static final int READ_CHUNK = 64 * 1024;

  private TrackOutput track;
  private ExtractorOutput output;
  private long headerPosition = -1;
  private long[] positions;
  private int[] sizes;
  private int[] skips;
  private int frameCount;
  private int frameIndex;
  private long blocksPerFrame;
  private long finalFrameBlocks;
  private int sampleRate;
  private long durationUs;
  private byte[] packet = new byte[0];
  private int packetBytes;
  private int packetTarget;
  private final byte[] tail = new byte[4];
  private int tailSize;
  private long tailEndPosition = -1;

  public ApeExtractor() {}

  @Override
  public boolean sniff(ExtractorInput input) throws IOException {
    input.resetPeekPosition();
    try {
      byte[] start = new byte[10];
      if (!input.peekFully(start, 0, 10, true)) return false;
      long offset = 0;
      if (start[0] == 'I' && start[1] == 'D' && start[2] == '3') {
        for (int i = 6; i < 10; i++) if ((start[i] & 0x80) != 0) return false;
        int size = ((start[6] & 127) << 21) | ((start[7] & 127) << 14)
            | ((start[8] & 127) << 7) | (start[9] & 127);
        if (size > MAX_HEADER_BYTES) return false;
        int footer = (start[5] & 0x10) != 0 ? 10 : 0;
        input.advancePeekPosition(size + footer);
        input.peekFully(start, 0, 6);
        offset = 10L + size + footer;
      }
      int version = u16(start, 4);
      if (start[0] != 'M' || start[1] != 'A' || start[2] != 'C' || start[3] != ' '
          || version < 3800 || version > 3990) return false;
      headerPosition = input.getPosition() + offset;
      return true;
    } catch (EOFException e) {
      return false;
    } finally {
      input.resetPeekPosition();
    }
  }

  @Override
  public void init(ExtractorOutput output) {
    this.output = output;
    track = output.track(0, C.TRACK_TYPE_AUDIO);
    output.endTracks();
  }

  @Override
  public int read(ExtractorInput input, PositionHolder seekPosition) throws IOException {
    if (positions == null) {
      try {
        readHeader(input);
      } catch (IOException error) {
        // Header parsing is atomic; a retried HTTP request must begin at the header again.
        input.setRetryPosition(Math.max(0, headerPosition), error);
        throw error;
      }
    }
    if (frameIndex >= frameCount) return RESULT_END_OF_INPUT;
    if (packetTarget == 0) {
      long position = positions[frameIndex];
      long overlap = input.getPosition() - position;
      if (overlap != 0 && !(overlap > 0 && overlap <= tailSize
          && input.getPosition() == tailEndPosition)) {
        // Return control to Media3, which reopens the authenticated data source with Range.
        seekPosition.position = position;
        return RESULT_SEEK;
      }
      packetTarget = sizes[frameIndex] + 8;
      if (packet.length < packetTarget) packet = new byte[packetTarget];
      put32(packet, 0, frameIndex == frameCount - 1 ? finalFrameBlocks : blocksPerFrame);
      put32(packet, 4, skips[frameIndex]);
      packetBytes = 8;
      if (overlap > 0) {
        System.arraycopy(tail, tailSize - (int) overlap, packet, 8, (int) overlap);
        packetBytes += (int) overlap;
      }
    }
    int count = input.read(packet, packetBytes, Math.min(READ_CHUNK, packetTarget - packetBytes));
    if (count == C.RESULT_END_OF_INPUT) {
      // Word alignment may extend the last frame by up to three bytes past EOF.
      if (frameIndex != frameCount - 1 || packetTarget - packetBytes > 3) {
        throw malformed("APE 音频帧被截断");
      }
      packetTarget = packetBytes;
    } else {
      packetBytes += count;
    }
    if (packetBytes < packetTarget) return RESULT_CONTINUE;
    track.sampleData(new ParsableByteArray(packet, packetBytes), packetBytes);
    track.sampleMetadata(frameTimeUs(frameIndex), C.BUFFER_FLAG_KEY_FRAME, packetBytes, 0, null);
    tailSize = Math.min(4, packetBytes - 8);
    System.arraycopy(packet, packetBytes - tailSize, tail, 0, tailSize);
    tailEndPosition = input.getPosition();
    frameIndex++;
    packetBytes = packetTarget = 0;
    return RESULT_CONTINUE;
  }

  private void readHeader(ExtractorInput input) throws IOException {
    if (headerPosition < 0 && !sniff(input)) throw malformed("不是支持的 APE 文件");
    skip(input, headerPosition - input.getPosition());
    byte[] first = new byte[8];
    input.readFully(first, 0, first.length);
    int version = u16(first, 4);
    if (first[0] != 'M' || first[1] != 'A' || first[2] != 'C' || first[3] != ' '
        || version < 3800 || version > 3990) throw malformed("APE 文件头无效");
    long descriptorLength;
    long headerLength;
    long seekLength;
    long wavHeaderLength;
    long wavTailLength;
    long declaredAudioLength = -1;
    int compression;
    int flags;
    int channels;
    int bits;
    if (version >= 3980) {
      byte[] descriptor = new byte[44];
      input.readFully(descriptor, 0, descriptor.length);
      descriptorLength = u32(descriptor, 0);
      headerLength = u32(descriptor, 4);
      seekLength = u32(descriptor, 8);
      wavHeaderLength = u32(descriptor, 12);
      long high = u32(descriptor, 20);
      if (high > 0x7fffffffL) throw malformed("APE 音频长度超出范围");
      declaredAudioLength = u32(descriptor, 16) | (high << 32);
      wavTailLength = u32(descriptor, 24);
      if (descriptorLength < 52 || descriptorLength > MAX_HEADER_BYTES
          || headerLength < 24 || headerLength > MAX_HEADER_BYTES) {
        throw malformed("APE 描述块长度无效");
      }
      skip(input, descriptorLength - 52);
      byte[] header = new byte[24];
      input.readFully(header, 0, header.length);
      compression = u16(header, 0);
      flags = u16(header, 2);
      blocksPerFrame = u32(header, 4);
      finalFrameBlocks = u32(header, 8);
      frameCount = checkedFrames(u32(header, 12));
      bits = u16(header, 16);
      channels = u16(header, 18);
      sampleRate = checkedRate(u32(header, 20));
      skip(input, headerLength - 24);
    } else {
      descriptorLength = 0;
      headerLength = 32;
      compression = u16(first, 6);
      byte[] header = new byte[24];
      input.readFully(header, 0, header.length);
      flags = u16(header, 0);
      channels = u16(header, 2);
      sampleRate = checkedRate(u32(header, 4));
      wavHeaderLength = u32(header, 8);
      wavTailLength = u32(header, 12);
      frameCount = checkedFrames(u32(header, 16));
      finalFrameBlocks = u32(header, 20);
      if ((flags & 4) != 0) { skip(input, 4); headerLength += 4; }
      if ((flags & 16) != 0) {
        byte[] length = new byte[4];
        input.readFully(length, 0, 4);
        seekLength = u32(length, 0) * 4;
        headerLength += 4;
      } else {
        seekLength = frameCount * 4L;
      }
      bits = (flags & 1) != 0 ? 8 : (flags & 8) != 0 ? 24 : 16;
      blocksPerFrame = version >= 3950 ? 73728L * 4
          : version >= 3900 || compression >= 4000 ? 73728 : 9216;
      if ((flags & 32) == 0) skip(input, wavHeaderLength);
    }
    if ((bits != 8 && bits != 16 && bits != 24) || channels < 1 || channels > 2
        || blocksPerFrame < 1 || blocksPerFrame > 4_194_304
        || finalFrameBlocks < 1 || finalFrameBlocks > blocksPerFrame
        || compression < 1000 || compression > 5000 || compression % 1000 != 0
        || seekLength < frameCount * 4L || seekLength > MAX_HEADER_BYTES) {
      throw malformed("APE 音频参数或定位表无效");
    }
    long firstFrame = headerPosition + descriptorLength + headerLength + seekLength
        + wavHeaderLength + (version < 3810 ? frameCount : 0);
    if (firstFrame < 0 || (input.getLength() != C.LENGTH_UNSET && firstFrame >= input.getLength())) {
      throw malformed("APE 首帧位置超出文件范围");
    }
    long[] framePositions = new long[frameCount];
    int[] frameSizes = new int[frameCount];
    int[] frameSkips = new int[frameCount];
    framePositions[0] = firstFrame;
    byte[] entry = new byte[4];
    input.readFully(entry, 0, 4);
    for (int i = 1; i < frameCount; i++) {
      input.readFully(entry, 0, 4);
      framePositions[i] = headerPosition + u32(entry, 0);
      long delta = framePositions[i] - framePositions[i - 1];
      if (delta <= 0 || delta > MAX_PACKET_BYTES) throw malformed("APE 定位表顺序或帧长度无效");
      frameSizes[i - 1] = (int) delta;
      frameSkips[i] = (int) ((framePositions[i] - firstFrame) & 3);
    }
    skip(input, seekLength - frameCount * 4L);
    long audioEnd;
    if (declaredAudioLength > 0 && declaredAudioLength <= Long.MAX_VALUE - firstFrame) {
      audioEnd = firstFrame + declaredAudioLength;
      if (input.getLength() != C.LENGTH_UNSET && audioEnd > input.getLength()) {
        throw malformed("APE 文件短于声明的音频长度");
      }
    } else if (input.getLength() != C.LENGTH_UNSET) {
      audioEnd = input.getLength() - wavTailLength;
    } else {
      throw malformed("此旧版 APE 需要服务器提供文件长度");
    }
    long lastSize = audioEnd - framePositions[frameCount - 1];
    if (lastSize <= 0 || lastSize > MAX_PACKET_BYTES - 4) throw malformed("APE 末帧长度无效");
    frameSizes[frameCount - 1] = (int) lastSize;
    for (int i = 0; i < frameCount; i++) {
      framePositions[i] -= frameSkips[i];
      long size = ((long) frameSizes[i] + frameSkips[i] + 3) & ~3L;
      if (size <= 0 || size > MAX_PACKET_BYTES) throw malformed("APE 对齐后帧长度无效");
      frameSizes[i] = (int) size;
    }
    if (version < 3810) {
      byte[] bit = new byte[1];
      for (int i = 0; i < frameCount; i++) {
        input.readFully(bit, 0, 1);
        int offset = bit[0] & 255;
        if (offset > 31) throw malformed("APE 位定位表无效");
        if (i > 0 && offset != 0) {
          if (frameSizes[i - 1] > MAX_PACKET_BYTES - 4) throw malformed("APE 帧长度超出范围");
          frameSizes[i - 1] += 4;
        }
        frameSkips[i] = (frameSkips[i] << 3) + offset;
      }
    }
    durationUs = ((frameCount - 1L) * blocksPerFrame + finalFrameBlocks) * 1_000_000 / sampleRate;
    byte[] extra = new byte[6];
    put16(extra, 0, version);
    put16(extra, 2, compression);
    put16(extra, 4, flags);
    track.format(new Format.Builder().setSampleMimeType(MIME_TYPE).setSampleRate(sampleRate)
        .setChannelCount(channels).setMaxInputSize(max(frameSizes) + 8)
        .setInitializationData(Arrays.asList(extra, new byte[] {(byte) bits})).build());
    // Publish the immutable seek arrays only after header parsing has completed.
    positions = framePositions;
    sizes = frameSizes;
    skips = frameSkips;
    output.seekMap(new ApeSeekMap());
  }

  @Override
  public void seek(long position, long timeUs) {
    if (positions != null) {
      int exact = Arrays.binarySearch(positions, position);
      frameIndex = position == 0 ? 0 : exact >= 0 ? exact : indexAt(timeUs);
    }
    packetBytes = packetTarget = 0;
    tailSize = 0;
    tailEndPosition = -1;
  }

  @Override
  public void release() {
    packet = new byte[0];
  }

  private int indexAt(long timeUs) {
    return (int) Math.min(frameCount - 1L,
        Math.min(durationUs, Math.max(0, timeUs)) * sampleRate / 1_000_000 / blocksPerFrame);
  }

  private long frameTimeUs(int index) {
    return index * blocksPerFrame * 1_000_000 / sampleRate;
  }

  private final class ApeSeekMap implements SeekMap {
    @Override public boolean isSeekable() { return true; }
    @Override public long getDurationUs() { return durationUs; }
    @Override public SeekPoints getSeekPoints(long timeUs) {
      int index = indexAt(Math.min(durationUs, timeUs));
      SeekPoint before = new SeekPoint(frameTimeUs(index), positions[index]);
      if (before.timeUs >= timeUs || index == frameCount - 1) return new SeekPoints(before);
      return new SeekPoints(before, new SeekPoint(frameTimeUs(index + 1), positions[index + 1]));
    }
  }

  private static int checkedFrames(long value) throws ParserException {
    if (value < 1 || value > MAX_FRAMES) throw malformed("APE 帧数无效或定位表过大");
    return (int) value;
  }
  private static int checkedRate(long value) throws ParserException {
    if (value < 1 || value > 768_000) throw malformed("APE 采样率无效");
    return (int) value;
  }
  private static void skip(ExtractorInput input, long count) throws IOException {
    if (count < 0 || count > MAX_HEADER_BYTES) throw malformed("APE 文件头长度超出范围");
    input.skipFully((int) count);
  }
  private static int max(int[] values) {
    int max = 0;
    for (int value : values) max = Math.max(max, value);
    return max;
  }
  private static int u16(byte[] b, int o) {
    return (b[o] & 255) | ((b[o + 1] & 255) << 8);
  }
  private static long u32(byte[] b, int o) {
    return (b[o] & 255L) | ((b[o + 1] & 255L) << 8) | ((b[o + 2] & 255L) << 16)
        | ((b[o + 3] & 255L) << 24);
  }
  private static void put16(byte[] b, int o, int value) {
    b[o] = (byte) value;
    b[o + 1] = (byte) (value >> 8);
  }
  private static void put32(byte[] b, int o, long value) {
    for (int i = 0; i < 4; i++) b[o + i] = (byte) (value >> (8 * i));
  }
  private static ParserException malformed(String message) {
    return ParserException.createForMalformedContainer(message, null);
  }
}
